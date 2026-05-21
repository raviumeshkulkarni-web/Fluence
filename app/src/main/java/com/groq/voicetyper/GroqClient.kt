package com.groq.voicetyper

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GroqClient {
    private const val TAG = "GroqClient"
    private const val TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL_WHISPER = "whisper-large-v3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the audio file directly to the Groq Whisper API for transcription.
     * Guaranteed to delete the temporary file on completion.
     */
    suspend fun transcribe(apiKey: String, audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(Exception("Audio file is empty or does not exist"))
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL_WHISPER)
            .addFormDataPart("language", "en")
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/m4a".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    val errorMessage = parseErrorMessage(bodyString) ?: "HTTP Error ${response.code}"
                    Log.e(TAG, "Transcription failed: $errorMessage")
                    return@withContext Result.failure(Exception(errorMessage))
                }

                if (bodyString.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Response body is empty"))
                }

                try {
                    val jsonObject = JSONObject(bodyString)
                    val text = jsonObject.getString("text")
                    Result.success(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JSON response: $bodyString", e)
                    Result.failure(Exception("Failed to parse transcription response"))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network request failed", e)
            Result.failure(Exception("Network error. Please check your internet connection."))
        } finally {
            // Explicitly delete the audio file after request is completed or failed
            if (audioFile.exists()) {
                val deleted = audioFile.delete()
                Log.d(TAG, "Temporary audio file deleted: $deleted")
            }
        }
    }

    private fun parseErrorMessage(responseBody: String?): String? {
        if (responseBody.isNullOrEmpty()) return null
        return try {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val errorObj = json.getJSONObject("error")
                errorObj.optString("message", null)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
