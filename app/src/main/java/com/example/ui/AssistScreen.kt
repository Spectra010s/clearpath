package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.permissions.PermissionSnapshot
import com.example.settings.ClearPathSettings
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnWarmAmber
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.WarmAmber
import com.example.voice.GeminiLiveState
import com.example.voice.VoicePhase
import com.example.voice.VoiceUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistScreen(
    voiceState: VoiceUiState,
    assistState: AssistUiState,
    settings: ClearPathSettings,
    permissions: PermissionSnapshot,
    isCameraReady: Boolean,
    onBindCamera: (androidx.lifecycle.LifecycleOwner, PreviewView?) -> Unit,
    onPrimaryVoiceTap: () -> Unit,
    onEndSession: () -> Unit,
    onRepeatLast: () -> Unit,
    onDescribeScene: () -> Unit,
    onReadText: () -> Unit,
    onToggleSceneWatch: (Boolean) -> Unit,
    onTogglePreviewVisible: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    liveState: GeminiLiveState = GeminiLiveState(),
    decibelBands: List<Float> = emptyList(),
    audioLevel: Float = 0f,
    isLiveCameraShareActive: Boolean = false,
    useBackCamera: Boolean = true,
    onToggleLiveCameraShare: () -> Unit = {},
    onOpenFullScreenCamera: () -> Unit = {},
    onFlipCamera: (androidx.lifecycle.LifecycleOwner, PreviewView?) -> Unit = { _, _ -> },
    onToggleInterpreterMode: () -> Unit = {},
    onScenerySummary: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(permissions.hasCamera, settings.showCameraPreview, useBackCamera, lifecycleOwner) {
        if (permissions.hasCamera) {
            onBindCamera(lifecycleOwner, if (settings.showCameraPreview) previewView else null)
        }
        onDispose { }
    }

    val liveBorderColor by animateColorAsState(
        targetValue = if (isLiveCameraShareActive) ReadyEmerald else ObsidianBorder,
        label = "cameraBorder"
    )
    val pulseTransition = rememberInfiniteTransition(label = "liveDotPulse")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (liveState.isActive ||
                liveState.isConnecting ||
                voiceState.phase == VoicePhase.SPEAKING ||
                voiceState.phase == VoicePhase.LISTENING
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MusicDecibelEqualizer(
                            decibelBands = decibelBands,
                            audioLevel = audioLevel,
                            isAiSpeaking = if (liveState.isActive) liveState.isAiSpeaking else voiceState.phase == VoicePhase.SPEAKING,
                            isUserSpeaking = if (liveState.isActive) (liveState.isUserSpeaking || !liveState.isAiSpeaking) else voiceState.phase == VoicePhase.LISTENING,
                            isConnecting = liveState.isConnecting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .testTag("assist_decibel_visualizer")
                        )

                        Text(
                            text = assistState.latestAnswer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HighContrastText,
                            modifier = Modifier.testTag("latest_answer_text")
                        )
                        assistState.errorMessage?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = AlertCoral,
                                modifier = Modifier.testTag("assist_error_text")
                            )
                        }

                        Text(
                            text = if (liveState.isActive) {
                                "Speak anytime to interrupt"
                            } else if (liveState.isConnecting) {
                                "Connecting real-time audio..."
                            } else {
                                "Audio active"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = HighContrastSecondaryText
                        )
                    }
                }
            }


            // Gemini-Live Style Camera Share Viewfinder Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(if (isLiveCameraShareActive) 2.dp else 1.dp, liveBorderColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Camera",
                            style = MaterialTheme.typography.titleMedium,
                            color = HighContrastText,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    onFlipCamera(
                                        lifecycleOwner,
                                        if (settings.showCameraPreview) previewView else null
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("flip_camera_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Cameraswitch,
                                    contentDescription = if (useBackCamera) "Switch to front camera" else "Switch to back camera"
                                )
                            }
                            IconButton(
                                onClick = onOpenFullScreenCamera,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("fullscreen_camera_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Fullscreen,
                                    contentDescription = "Open full-screen camera"
                                )
                            }
                            OutlinedButton(
                                onClick = { onTogglePreviewVisible(!settings.showCameraPreview) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("toggle_preview_button")
                                    .semantics {
                                        contentDescription = if (settings.showCameraPreview) {
                                            "Collapse visual viewfinder"
                                        } else {
                                            "Expand visual viewfinder"
                                        }
                                    }
                            ) {
                                Icon(
                                    imageVector = if (settings.showCameraPreview) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = RestrainedCyan
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (settings.showCameraPreview) "Collapse" else "Expand")
                            }
                        }
                    }

                    // Simple square camera viewfinder with a Share / Stop control at the bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .semantics {
                                contentDescription = if (isLiveCameraShareActive) {
                                    "Live continuous camera stream active"
                                } else {
                                    "Camera preview"
                                }
                            }
                    ) {
                        if (permissions.hasCamera && settings.showCameraPreview) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ObsidianBackground),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when {
                                        !permissions.hasCamera -> "Camera access needed for live stream"
                                        isLiveCameraShareActive -> "Preview collapsed"
                                        else -> "Visual preview hidden"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HighContrastSecondaryText,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                                    )
                                )
                        )

                        // Bottom floating Gemini-Live style Share Camera pill inside viewfinder
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onToggleLiveCameraShare,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLiveCameraShareActive) AlertCoral else ReadyEmerald,
                                    contentColor = OnWarmAmber
                                ),
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("live_camera_share_button")
                            ) {
                                Icon(
                                    imageVector = if (isLiveCameraShareActive) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isLiveCameraShareActive) "Stop" else "Share",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                }
            }


        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            val sessionActive = liveState.isActive || liveState.isConnecting ||
                voiceState.isSessionActive || voiceState.phase == VoicePhase.SPEAKING ||
                voiceState.phase == VoicePhase.LISTENING || voiceState.phase == VoicePhase.PROCESSING
            if (sessionActive) {
                SmallFloatingActionButton(
                    onClick = onEndSession,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(bottom = 8.dp)
                        .testTag("end_session_button"),
                    containerColor = AlertCoral,
                    contentColor = OnWarmAmber
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop Speaking & End Session")
                }
            }
            val (fabIcon, fabContainer) = when {
                liveState.isConnecting -> Icons.Rounded.HourglassTop to RestrainedCyan
                liveState.isActive -> Icons.Rounded.GraphicEq to ReadyEmerald
                voiceState.phase == VoicePhase.SPEAKING -> Icons.Rounded.Mic to WarmAmber
                voiceState.phase == VoicePhase.LISTENING -> Icons.Rounded.Mic to ReadyEmerald
                voiceState.phase == VoicePhase.PROCESSING -> Icons.Rounded.HourglassTop to RestrainedCyan
                else -> Icons.Rounded.Mic to WarmAmber
            }
            SmallFloatingActionButton(
                onClick = onPrimaryVoiceTap,
                modifier = Modifier.testTag("primary_voice_button"),
                containerColor = fabContainer,
                contentColor = OnWarmAmber
            ) {
                Icon(fabIcon, contentDescription = "Toggle live voice")
            }
        }
    }
}

/**
 * Full-screen camera experience: live camera stream,
 * audio-reactive equalizer bars, and floating translucent control dock.
 */
@Composable
fun GeminiLiveCameraOverlay(
    liveState: GeminiLiveState,
    decibelBands: List<Float>,
    audioLevel: Float,
    settings: ClearPathSettings,
    permissions: PermissionSnapshot,
    isLiveCameraShareActive: Boolean,
    useBackCamera: Boolean,
    onBindCamera: (androidx.lifecycle.LifecycleOwner, PreviewView?) -> Unit,
    onFlipCamera: (androidx.lifecycle.LifecycleOwner, PreviewView?) -> Unit,
    onToggleLiveCameraShare: () -> Unit,
    onTogglePreviewVisible: (Boolean) -> Unit,
    onReadText: () -> Unit,
    onToggleInterpreterMode: () -> Unit = {},
    onMinimize: () -> Unit,
    onEndLiveSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onMinimize() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(permissions.hasCamera, settings.showCameraPreview, useBackCamera, lifecycleOwner) {
        if (permissions.hasCamera) {
            onBindCamera(lifecycleOwner, if (settings.showCameraPreview) previewView else null)
        }
        onDispose { }
    }

    val pulseTransition = rememberInfiniteTransition(label = "fullLivePulse")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fullLiveDotAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (permissions.hasCamera && settings.showCameraPreview) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBackground),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLiveCameraShareActive) {
                        "Audio and camera active (preview hidden)"
                    } else {
                        "Camera preview hidden"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = HighContrastSecondaryText,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }

        // Top gradient + status bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = ObsidianBackground.copy(alpha = 0.85f),
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (isLiveCameraShareActive) ReadyEmerald else ObsidianBorder
                ),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .alpha(if (isLiveCameraShareActive) liveDotAlpha else 1f)
                            .clip(CircleShape)
                            .background(if (isLiveCameraShareActive) ReadyEmerald else HighContrastSecondaryText)
                    )
                    Text(
                        text = if (isLiveCameraShareActive) {
                            liveState.currentVoiceName
                        } else {
                            "Camera Paused · ${liveState.currentVoiceName}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = HighContrastText
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(
                    onClick = { onTogglePreviewVisible(!settings.showCameraPreview) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ObsidianBackground.copy(alpha = 0.82f),
                        contentColor = RestrainedCyan
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (settings.showCameraPreview) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (settings.showCameraPreview) "Hide visual preview" else "Show visual preview"
                    )
                }

                FilledIconButton(
                    onClick = onMinimize,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ObsidianBackground.copy(alpha = 0.82f),
                        contentColor = HighContrastText
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("minimize_fullscreen_camera_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FullscreenExit,
                        contentDescription = "Minimize camera view"
                    )
                }
            }
        }

        // Bottom gradient + equalizer + spoken words
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MusicDecibelEqualizer(
                decibelBands = decibelBands,
                audioLevel = audioLevel,
                isAiSpeaking = liveState.isAiSpeaking,
                isUserSpeaking = liveState.isUserSpeaking || !liveState.isAiSpeaking,
                isConnecting = liveState.isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .padding(horizontal = 16.dp)
                    .testTag("fullscreen_camera_equalizer")
            )

            val fullModeText = liveState.outputTranscript.ifBlank { liveState.inputTranscript }
            if (fullModeText.isNotBlank()) {
                Text(
                    text = fullModeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HighContrastText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}
