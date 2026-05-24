package com.groq.voicetyper

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
    private lateinit var composeView: ComposeView

    // IME State
    private var apiKey by mutableStateOf<String?>(null)
    private var recordingState by mutableStateOf(RecordingState.IDLE)
    private var errorMessage by mutableStateOf<String?>(null)
    private val errorHandler = Handler(Looper.getMainLooper())

    // Backspace Swipe-to-Delete state
    private var initialCursorPos = -1
    private var swipeSelectLength = 0
    private var swipeTextBefore = ""

    private fun getCharsForWords(text: String, wordCount: Int): Int {
        if (text.isEmpty() || wordCount <= 0) return 0
        var count = 0
        var wordsFound = 0
        var i = text.length - 1
        
        while (i >= 0 && text[i].isWhitespace()) {
            count++
            i--
        }
        
        while (i >= 0 && wordsFound < wordCount) {
            while (i >= 0 && !text[i].isWhitespace()) {
                count++
                i--
            }
            wordsFound++
            while (i >= 0 && text[i].isWhitespace()) {
                count++
                i--
            }
        }
        return count
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing VoiceInputIME service")
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        audioRecorder = AudioRecorder(this)
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView: Creating Compose input view")
        composeView = ComposeView(this).apply {
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        }

        // Set the window background to transparent so the app behind is visible around the floating pill
        window?.window?.let { win ->
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                win.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                win.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = (
                    win.decorView.systemUiVisibility
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
        }

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
                    val conn = currentInputConnection
                    if (conn != null) {
                        val selectedText = conn.getSelectedText(0)
                        if (!selectedText.isNullOrEmpty()) {
                            conn.commitText("", 1)
                        } else {
                            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                        }
                    }
                },
                onBackspaceSelect = { words ->
                    val conn = currentInputConnection ?: return@IMEScreen
                    if (initialCursorPos == -1) {
                        val extracted = conn.getExtractedText(ExtractedTextRequest(), 0)
                        initialCursorPos = extracted?.selectionStart ?: -1
                        swipeTextBefore = conn.getTextBeforeCursor(300, 0)?.toString() ?: ""
                    }
                    val lengthToSelect = getCharsForWords(swipeTextBefore, words)
                    swipeSelectLength = lengthToSelect
                    if (initialCursorPos != -1 && lengthToSelect > 0) {
                        val start = (initialCursorPos - lengthToSelect).coerceAtLeast(0)
                        conn.setSelection(start, initialCursorPos)
                    }
                },
                onBackspaceDeleteSelected = {
                    val conn = currentInputConnection
                    if (conn != null && swipeSelectLength > 0) {
                        conn.commitText("", 1)
                    }
                    initialCursorPos = -1
                    swipeSelectLength = 0
                    swipeTextBefore = ""
                },
                onBackspaceCancelSelect = {
                    val conn = currentInputConnection
                    if (conn != null && initialCursorPos != -1) {
                        conn.setSelection(initialCursorPos, initialCursorPos)
                    }
                    initialCursorPos = -1
                    swipeSelectLength = 0
                    swipeTextBefore = ""
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
                },
                onSwitchKeyboard = {
                    val token = window?.window?.attributes?.token
                    if (token != null) {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        try {
                            imm.switchToNextInputMethod(token, false)
                        } catch (e: Exception) {
                            imm.showInputMethodPicker()
                        }
                    } else {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    }
                }
            )
        }
        return composeView
    }

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null) return

        if (!::composeView.isInitialized) return
        val windowHeight = composeView.height
        if (windowHeight <= 0) return

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density

        val pillWidth = (240 * density).toInt()
        val pillHeight = (64 * density).toInt()
        val windowWidth = displayMetrics.widthPixels

        val left = (windowWidth - pillWidth) / 2
        val right = (windowWidth + pillWidth) / 2

        // The top of the pill bar is estimated from the bottom.
        // We add some margin for safety (e.g. 16dp bottom padding + 64dp pill height)
        val bottomMargin = (16 * density).toInt()
        val isStatusVisible = recordingState != RecordingState.IDLE || errorMessage != null
        val topOffset = if (isStatusVisible) {
            (48 * density).toInt()
        } else {
            0
        }

        // Get actual navigation bar height since the window now layouts under it
        val insetsBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            composeView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            composeView.rootWindowInsets?.stableInsetBottom
        } else {
            null
        }
        val navBarHeight = insetsBottom ?: run {
            val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        }

        val top = windowHeight - navBarHeight - pillHeight - bottomMargin - topOffset
        val bottom = windowHeight - navBarHeight

        val rect = android.graphics.Rect(left, top.coerceAtLeast(0), right, bottom.coerceAtLeast(0))

        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(rect)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView: Starting input view, restarting=$restarting, inputType=${info?.inputType}")
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
