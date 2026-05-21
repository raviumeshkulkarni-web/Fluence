package com.groq.voicetyper

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class VoiceInputIME : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val store by lazy { ViewModelStore() }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // Re-created in onCreate to ensure a fresh scope after destroy/re-create cycles
    private lateinit var scope: CoroutineScope
    private lateinit var audioRecorder: AudioRecorder

    // IME State
    private var apiKey by mutableStateOf<String?>(null)
    private var recordingState by mutableStateOf(RecordingState.IDLE)
    private var errorMessage by mutableStateOf<String?>(null)
    private val errorHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        audioRecorder = AudioRecorder(this)
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this)

        // Set lifecycle and VM store owners on the Window DecorView for correct tree resolution
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        // Set owners on the Compose View as well to be absolutely safe
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            IMEScreen(
                audioRecorder = audioRecorder,
                apiKey = apiKey,
                onBackspace = {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                },
                onSpace = {
                    currentInputConnection?.commitText(" ", 1)
                },
                onEnter = {
                    currentInputConnection?.let { conn ->
                        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                },
                recordingState = recordingState,
                errorMessage = errorMessage,
                onCancelRecording = {
                    audioRecorder.cancelRecording()
                    recordingState = RecordingState.IDLE
                },
                onStartRecording = {
                    errorMessage = null
                    recordingState = RecordingState.RECORDING
                    audioRecorder.startRecording()
                },
                onStopRecording = {
                    recordingState = RecordingState.TRANSCRIBING
                    val file = audioRecorder.stopRecording()
                    if (file != null) {
                        transcribeAudio(file)
                    } else {
                        recordingState = RecordingState.IDLE
                    }
                }
            )
        }
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Refresh API Key from EncryptedSharedPreferences on open
        apiKey = SecurityUtils.getApiKey(this)
        recordingState = RecordingState.IDLE
        errorMessage = null

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Stop any ongoing recording when keyboard is closed
        if (recordingState == RecordingState.RECORDING) {
            audioRecorder.cancelRecording()
        }
        recordingState = RecordingState.IDLE

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        errorHandler.removeCallbacksAndMessages(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Sends audio to GroqClient for transcription and commits the result to the input field.
     * File ownership is transferred to GroqClient — it handles deletion.
     */
    private fun transcribeAudio(file: File) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            showError("API Key is missing. Set it in the app.")
            // GroqClient owns file deletion, but since we're not calling it, delete here
            file.delete()
            return
        }

        recordingState = RecordingState.TRANSCRIBING

        scope.launch {
            val result = GroqClient.transcribe(key, file)
            result.fold(
                onSuccess = { text ->
                    if (text.isNotBlank()) {
                        currentInputConnection?.let { connection ->
                            // Trim and insert with a clean trailing space for easy formatting
                            val cleanText = text.trim()
                            connection.commitText("$cleanText ", 1)
                        }
                    }
                    recordingState = RecordingState.IDLE
                },
                onFailure = { error ->
                    showError(error.localizedMessage ?: "Transcription failed")
                }
            )
        }
    }

    private fun showError(message: String) {
        errorMessage = message
        recordingState = RecordingState.ERROR
        Log.e(TAG, "Error: $message")

        // Auto-clear error state back to IDLE after 4 seconds
        errorHandler.removeCallbacksAndMessages(null)
        errorHandler.postDelayed({
            if (recordingState == RecordingState.ERROR) {
                recordingState = RecordingState.IDLE
                errorMessage = null
            }
        }, 4000)
    }

    companion object {
        private const val TAG = "VoiceInputIME"
    }
}
