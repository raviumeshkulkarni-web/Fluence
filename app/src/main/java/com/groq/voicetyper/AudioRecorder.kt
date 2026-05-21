package com.groq.voicetyper

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startRecording() {
        if (isRecording) return

        // Use cache directory for privacy (files remain internal to the app)
        val cacheFile = File(context.cacheDir, "groq_voice_record_${System.currentTimeMillis()}.m4a")
        outputFile = cacheFile

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(cacheFile.absolutePath)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96000)

            try {
                prepare()
                start()
                isRecording = true
            } catch (e: IOException) {
                Log.e("AudioRecorder", "Recording preparation failed", e)
                cleanup()
            } catch (e: IllegalStateException) {
                Log.e("AudioRecorder", "Recording start failed", e)
                cleanup()
            }
        }

        if (isRecording) {
            startAmplitudePolling()
        }
    }

    private fun startAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isRecording) {
                val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                // Normalize 0 to 32767 to a 0.0 to 1.0 float
                val normalized = (maxAmp.toFloat() / 32767f).coerceIn(0f, 1f)
                _amplitude.value = normalized
                delay(50)
            }
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        
        isRecording = false
        amplitudeJob?.cancel()
        _amplitude.value = 0f

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: RuntimeException) {
            // stop() can fail if recording duration was too short
            Log.e("AudioRecorder", "Stop recording failed (recording too short?)", e)
            outputFile?.delete()
            outputFile = null
        } finally {
            mediaRecorder = null
        }

        return outputFile
    }

    fun cancelRecording() {
        isRecording = false
        amplitudeJob?.cancel()
        _amplitude.value = 0f

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Cancel recording failed", e)
        } finally {
            mediaRecorder = null
            outputFile?.delete()
            outputFile = null
        }
    }

    private fun cleanup() {
        isRecording = false
        mediaRecorder?.release()
        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
    }
}
