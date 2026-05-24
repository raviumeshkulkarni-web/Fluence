package com.groq.voicetyper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

object BubbleController {
    private const val TAG = "BubbleController"

    private val _isBubbleVisible = MutableStateFlow(false)
    val isBubbleVisible: StateFlow<Boolean> = _isBubbleVisible.asStateFlow()

    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded: StateFlow<Boolean> = _isBubbleExpanded.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /**
     * Strong reference to the currently-focused editable accessibility node.
     * We use obtain() when caching and recycle the previous node to avoid leaks.
     */
    private var activeNode: AccessibilityNodeInfo? = null
    private val nodeLock = Any()
    private var audioRecorder: AudioRecorder? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var amplitudeCollectJob: kotlinx.coroutines.Job? = null

    fun initRecorder(context: Context) {
        if (audioRecorder == null) {
            val appCtx = context.applicationContext
            val recorder = AudioRecorder(appCtx)
            audioRecorder = recorder
            
            // Monitor amplitude from AudioRecorder and update our state
            amplitudeCollectJob?.cancel()
            amplitudeCollectJob = scope.launch {
                recorder.amplitude.collect {
                    _amplitude.value = it
                }
            }
        }
    }

    fun showBubble(context: Context, node: AccessibilityNodeInfo) {
        initRecorder(context)

        // Cache a strong reference to the focused node.
        // obtain() creates a copy so the original can be recycled by the caller.
        @Suppress("DEPRECATION")
        synchronized(nodeLock) {
            val newNode = AccessibilityNodeInfo.obtain(node)
            activeNode?.recycle()
            activeNode = newNode
        }

        // Only start the foreground service if bubble wasn't already visible.
        // Avoids redundant startForegroundService calls which can crash on some OEMs.
        val wasVisible = _isBubbleVisible.value
        _isBubbleVisible.value = true

        if (!wasVisible) {
            try {
                val intent = Intent(context, FloatingBubbleService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FloatingBubbleService", e)
            }
        }
    }

    fun hideBubble() {
        // Only cancel if actively recording — don't discard an in-flight transcription
        if (_recordingState.value == RecordingState.RECORDING) {
            cancelRecording()
        }
        _isBubbleVisible.value = false
        _isBubbleExpanded.value = false
        @Suppress("DEPRECATION")
        synchronized(nodeLock) {
            activeNode?.recycle()
            activeNode = null
        }
    }

    /**
     * Hides the bubble AND stops the FloatingBubbleService entirely.
     * Call when the feature is disabled or the accessibility service is destroyed.
     */
    fun stopService(context: Context) {
        hideBubble()
        try {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingBubbleService", e)
        }
    }

    fun startRecording(context: Context) {
        initRecorder(context)
        _errorMessage.value = null
        _recordingState.value = RecordingState.RECORDING
        _isBubbleExpanded.value = true
        audioRecorder?.startRecording()
    }

    fun stopRecording(context: Context) {
        _recordingState.value = RecordingState.TRANSCRIBING
        val file = audioRecorder?.stopRecording()
        if (file != null) {
            transcribeAudio(context, file)
        } else {
            _recordingState.value = RecordingState.IDLE
            _isBubbleExpanded.value = false
        }
    }

    fun cancelRecording() {
        audioRecorder?.cancelRecording()
        _recordingState.value = RecordingState.IDLE
        _isBubbleExpanded.value = false
    }

    private fun transcribeAudio(context: Context, file: File) {
        val apiKey = SecurityUtils.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            showError("API Key is missing. Set it in the app.")
            file.delete()
            return
        }

        _recordingState.value = RecordingState.TRANSCRIBING

        scope.launch {
            val result = GroqClient.transcribe(apiKey, file)
            result.fold(
                onSuccess = { text ->
                    if (text.isNotBlank()) {
                        injectText(text)
                    }
                    _recordingState.value = RecordingState.IDLE
                    _isBubbleExpanded.value = false
                },
                onFailure = { error ->
                    showError(error.localizedMessage ?: "Transcription failed")
                }
            )
        }
    }

    private fun showError(message: String) {
        _errorMessage.value = message
        _recordingState.value = RecordingState.ERROR
        Log.e(TAG, "Error: $message")

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (_recordingState.value == RecordingState.ERROR) {
                _recordingState.value = RecordingState.IDLE
                _errorMessage.value = null
                _isBubbleExpanded.value = false
            }
        }, 4000)
    }

    /**
     * Injects text into the active node at the current cursor position.
     */
    fun injectText(text: String) {
        val node = synchronized(nodeLock) { activeNode } ?: return

        // Attempt to refresh the node to get up-to-date text/cursor state.
        // If refresh fails (common in WebViews or after window changes), we
        // still try to inject — the cached node often remains functional.
        val refreshed = try { node.refresh() } catch (e: Exception) { false }
        if (!refreshed) {
            Log.w(TAG, "Node refresh returned false — attempting injection anyway")
        }

        val currentText = if (node.isShowingHintText) "" else (node.text ?: "")
        val selectionStart = if (node.isShowingHintText) 0 else node.textSelectionStart
        val selectionEnd = if (node.isShowingHintText) 0 else node.textSelectionEnd
        val textToInsert = "${text.trim()} "

        val bundle = Bundle()
        if (selectionStart >= 0 && selectionEnd >= 0) {
            val newText = StringBuilder(currentText)
                .replace(selectionStart, selectionEnd, textToInsert)
                .toString()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val newCursorPos = selectionStart + textToInsert.length
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            } else {
                Log.w(TAG, "ACTION_SET_TEXT failed — trying append fallback")
                val appendBundle = Bundle()
                appendBundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    currentText.toString() + textToInsert
                )
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, appendBundle)
            }
        } else {
            // Fallback: Append text
            val newText = currentText.toString() + textToInsert
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        }
    }

    /**
     * Deletes one character (or selection) before the cursor.
     */
    fun performBackspace() {
        val node = synchronized(nodeLock) { activeNode } ?: return
        try { node.refresh() } catch (_: Exception) {}

        if (node.isShowingHintText) {
            return
        }

        val currentText = node.text ?: ""
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd

        val bundle = Bundle()
        if (selectionStart > 0 && selectionStart == selectionEnd) {
            val newText = StringBuilder(currentText).deleteAt(selectionStart - 1).toString()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val newCursorPos = selectionStart - 1
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        } else if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val newText = StringBuilder(currentText).delete(selectionStart, selectionEnd).toString()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionStart)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionStart)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        }
    }
}
