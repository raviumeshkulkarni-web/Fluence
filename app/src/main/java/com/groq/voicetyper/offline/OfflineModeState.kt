package com.groq.voicetyper.offline

import android.content.Context

/**
 * State machine for the offline transcription subsystem.
 * Read by VoiceInputIME and BubbleController to determine routing.
 */
enum class OfflineModeState {
    DISABLED,              // User has not enabled offline mode
    MODEL_NOT_DOWNLOADED,  // Toggle ON but model files not present
    DOWNLOADING_MODEL,     // Download in progress
    READY,                 // Model downloaded + verified, ready for inference
    ENGINE_LOADING,        // sherpa-onnx engine being initialized (lazy load)
    TRANSCRIBING           // Active inference in progress
}

/**
 * Reads/writes the offline mode preference from SharedPreferences("fluence_prefs").
 * Stateless utility — no singletons, no caching.
 */
object OfflinePreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_OFFLINE_ENABLED = "offline_mode_enabled"

    fun isOfflineModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_ENABLED, false)
    }

    fun setOfflineModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_ENABLED, enabled)
            .apply()
    }
}
