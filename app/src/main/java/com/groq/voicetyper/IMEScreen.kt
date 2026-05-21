package com.groq.voicetyper

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

enum class RecordingState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    ERROR
}

@Composable
fun IMEScreen(
    audioRecorder: AudioRecorder,
    apiKey: String?,
    onInsertText: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onEnter: () -> Unit,
    onSendRecording: (File) -> Unit,
    recordingState: RecordingState,
    errorMessage: String?,
    onCancelRecording: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val context = LocalContext.current
    val amplitude by audioRecorder.amplitude.collectAsState()
    
    // Waveform historical points
    val amplitudes = remember { mutableStateListOf<Float>() }
    
    // Manage amplitude queue length
    LaunchedEffect(amplitude, recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            amplitudes.add(amplitude)
            if (amplitudes.size > 35) {
                amplitudes.removeAt(0)
            }
        } else {
            amplitudes.clear()
        }
    }

    // Recording duration timer
    var recordTimeSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            recordTimeSeconds = 0
            while (isActive) {
                delay(1000)
                recordTimeSeconds++
            }
        }
    }

    val minutes = recordTimeSeconds / 60
    val seconds = recordTimeSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    // Keyboard container layout
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F12)) // Premium Dark Theme Background
            .padding(vertical = 8.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status & Timer Display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status text
            Text(
                text = when (recordingState) {
                    RecordingState.IDLE -> if (apiKey.isNullOrBlank()) "API Key Required in App Settings" else "Tap or Hold to Speak"
                    RecordingState.RECORDING -> "Listening..."
                    RecordingState.TRANSCRIBING -> "Transcribing..."
                    RecordingState.ERROR -> errorMessage ?: "An error occurred"
                },
                color = when (recordingState) {
                    RecordingState.ERROR -> Color(0xFFFF5252)
                    RecordingState.RECORDING -> Color(0xFF00E676)
                    RecordingState.TRANSCRIBING -> Color(0xFF4FACFE)
                    else -> Color(0xFF8E8E9A)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            // Timer
            if (recordingState == RecordingState.RECORDING) {
                Text(
                    text = timeText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }

        // Waveform / Visualization Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (recordingState == RecordingState.RECORDING && amplitudes.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f
                    val barWidth = 6.dp.toPx()
                    val barGap = 4.dp.toPx()
                    val totalBars = amplitudes.size

                    val startX = (width - (totalBars * (barWidth + barGap))) / 2f
                    
                    val gradient = Brush.linearGradient(
                        colors = listOf(Color(0xFF00F2FE), Color(0xFF4FACFE), Color(0xFFF355DA)),
                        start = Offset(0f, 0f),
                        end = Offset(width, 0f)
                    )

                    for (i in 0 until totalBars) {
                        val amp = amplitudes[i]
                        // Scale amplitude to max 80% height, min 6px height
                        val barHeight = (amp * height * 0.8f).coerceAtLeast(6f)
                        val x = startX + i * (barWidth + barGap)
                        val y1 = centerY - barHeight / 2f
                        val y2 = centerY + barHeight / 2f

                        drawRoundRect(
                            brush = gradient,
                            topLeft = Offset(x, y1),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            } else if (recordingState == RecordingState.TRANSCRIBING) {
                // Circular Progress Indicator
                CircularProgressIndicator(
                    color = Color(0xFF4FACFE),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                // Silent baseline
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)) {
                    drawLine(
                        color = Color(0xFF26262B),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2f
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Main controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Action: Keyboard switcher
            IconButton(
                onClick = {
                    val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imeManager.showInputMethodPicker()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF1E1E24), CircleShape)
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    // Custom Keyboard Icon
                    val path = Path().apply {
                        moveTo(2f, 5f)
                        lineTo(22f, 5f)
                        lineTo(22f, 17f)
                        lineTo(2f, 17f)
                        close()
                        // Space bar line
                        moveTo(7f, 14f)
                        lineTo(17f, 14f)
                        // Keys
                        moveTo(5f, 8f); lineTo(7f, 8f)
                        moveTo(9f, 8f); lineTo(11f, 8f)
                        moveTo(13f, 8f); lineTo(15f, 8f)
                        moveTo(17f, 8f); lineTo(19f, 8f)
                        
                        moveTo(6f, 11f); lineTo(8f, 11f)
                        moveTo(10f, 11f); lineTo(12f, 11f)
                        moveTo(14f, 11f); lineTo(16f, 11f)
                        moveTo(18f, 11f); lineTo(20f, 11f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // Center Action: Voice button (Tap & Hold)
            val isEnabled = !apiKey.isNullOrBlank() && recordingState != RecordingState.TRANSCRIBING
            
            // Pulse animation when recording
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (recordingState == RecordingState.RECORDING) 1.15f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            
            val micBgColor by animateColorAsState(
                targetValue = when (recordingState) {
                    RecordingState.RECORDING -> Color(0xFFFF1744) // Red when recording
                    RecordingState.TRANSCRIBING -> Color(0xFF1E1E24)
                    else -> if (isEnabled) Color(0xFF6200EE) else Color(0xFF26262B)
                },
                label = "color"
            )

            Box(
                modifier = Modifier
                    .scale(pulseScale)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(micBgColor)
                    .pointerInput(isEnabled) {
                        if (!isEnabled) return@pointerInput
                        
                        detectTapGestures(
                            onPress = {
                                // Hold-to-speak logic
                                val startTime = System.currentTimeMillis()
                                onStartRecording()
                                
                                try {
                                    awaitRelease()
                                    // Stop recording on release
                                    val duration = System.currentTimeMillis() - startTime
                                    if (duration > 400) { // If held for more than 400ms, stop and transcribe
                                        onStopRecording()
                                    } else {
                                        // Keep recording running for tap-to-toggle behavior
                                    }
                                } catch (c: Exception) {
                                    onCancelRecording()
                                }
                            },
                            onTap = {
                                // Tap-to-toggle logic
                                if (recordingState == RecordingState.RECORDING) {
                                    onStopRecording()
                                } else {
                                    // Handled by onPress initially, so if tap completed quickly we just keep it recording
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // Microphone Icon SVG path
                Canvas(modifier = Modifier.size(32.dp)) {
                    val w = size.width
                    val h = size.height
                    
                    // Mic main cylinder
                    val micPath = Path().apply {
                        moveTo(w * 0.35f, h * 0.2f)
                        lineTo(w * 0.65f, h * 0.2f)
                        // Round corners
                        arcTo(
                            rect = androidx.compose.ui.geometry.Rect(w * 0.35f, h * 0.1f, w * 0.65f, h * 0.4f),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 180f,
                            forceMoveTo = false
                        )
                        lineTo(w * 0.65f, h * 0.55f)
                        arcTo(
                            rect = androidx.compose.ui.geometry.Rect(w * 0.35f, h * 0.45f, w * 0.65f, h * 0.65f),
                            startAngleDegrees = 0f,
                            sweepAngleDegrees = 180f,
                            forceMoveTo = false
                        )
                        close()
                    }
                    drawPath(
                        path = micPath,
                        color = if (recordingState == RecordingState.TRANSCRIBING) Color(0xFF6E6E7A) else Color.White
                    )
                    
                    // Mic U-shaped stand
                    val standPath = Path().apply {
                        moveTo(w * 0.25f, h * 0.45f)
                        arcTo(
                            rect = androidx.compose.ui.geometry.Rect(w * 0.25f, h * 0.35f, w * 0.75f, h * 0.75f),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = -180f,
                            forceMoveTo = true
                        )
                        // Base stem
                        moveTo(w * 0.5f, h * 0.75f)
                        lineTo(w * 0.5f, h * 0.85f)
                        // Base plate
                        moveTo(w * 0.35f, h * 0.85f)
                        lineTo(w * 0.65f, h * 0.85f)
                    }
                    drawPath(
                        path = standPath,
                        color = if (recordingState == RecordingState.TRANSCRIBING) Color(0xFF6E6E7A) else Color.White,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Right Action: Backspace button
            IconButton(
                onClick = onBackspace,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF1E1E24), CircleShape)
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    // Custom Backspace Icon (Triangle facing left + X inside rectangle)
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.35f, h * 0.2f)
                        lineTo(w * 0.9f, h * 0.2f)
                        lineTo(w * 0.9f, h * 0.8f)
                        lineTo(w * 0.35f, h * 0.8f)
                        lineTo(w * 0.1f, h * 0.5f)
                        close()
                        // X inner cross
                        moveTo(w * 0.5f, h * 0.38f)
                        lineTo(w * 0.75f, h * 0.62f)
                        moveTo(w * 0.75f, h * 0.38f)
                        lineTo(w * 0.5f, h * 0.62f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bottom row: Spacebar and Enter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Spacebar
            Button(
                onClick = onSpace,
                modifier = Modifier
                    .weight(2f)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF26262B)
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Text("Space", color = Color.White, fontSize = 14.sp)
            }

            // Enter key
            Button(
                onClick = onEnter,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3A40)
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Text("Enter", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
