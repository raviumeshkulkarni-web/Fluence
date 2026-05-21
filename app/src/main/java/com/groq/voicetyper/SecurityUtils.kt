package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityUtils {
    private const val PREFS_NAME = "groq_voice_typer_secure_prefs"
    private const val KEY_API_KEY = "groq_api_key"

    private fun getSharedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveApiKey(context: Context, apiKey: String) {
        getSharedPrefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getApiKey(context: Context): String? {
        return getSharedPrefs(context).getString(KEY_API_KEY, null)
    }

    fun clearApiKey(context: Context) {
        getSharedPrefs(context).edit().remove(KEY_API_KEY).apply()
    }
}
