package com.example.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.permissions.PermissionSnapshot
import com.example.settings.ClearPathSettings
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.WarmAmber
import com.example.voice.VoiceUiState

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(permissions.hasCamera, settings.showCameraPreview, lifecycleOwner) {
        if (permissions.hasCamera) {
            onBindCamera(lifecycleOwner, if (settings.showCameraPreview) previewView else null)
        }
        onDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VoiceStatusBanner(voiceState = voiceState)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Latest spoken answer & origin honesty card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.5.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = WarmAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Latest Guidance",
                                style = MaterialTheme.typography.titleMedium,
                                color = WarmAmber
                            )
                        }
                        assistState.lastOrigin?.let { origin ->
                            Text(
                                text = origin.badgeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = RestrainedCyan
                            )
                        }
                    }

                    Text(
                        text = assistState.latestAnswer,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HighContrastText,
                        modifier = Modifier.testTag("latest_answer_text")
                    )

                    assistState.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlertCoral,
                            modifier = Modifier.testTag("assist_error_text")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRepeatLast,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("repeat_answer_button"),
                            border = BorderStroke(1.5.dp, RestrainedCyan)
                        ) {
                            Icon(Icons.Rounded.Replay, contentDescription = null, tint = RestrainedCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Repeat", color = HighContrastText)
                        }

                        OutlinedButton(
                            onClick = onReadText,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("read_text_button"),
                            border = BorderStroke(1.5.dp, WarmAmber)
                        ) {
                            Icon(Icons.Rounded.DocumentScanner, contentDescription = null, tint = WarmAmber)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Read Text", color = HighContrastText)
                        }
                    }

                    OutlinedButton(
                        onClick = onDescribeScene,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("describe_scene_button"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = ObsidianSurfaceElevated
                        ),
                        border = BorderStroke(1.5.dp, WarmAmber)
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = WarmAmber)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Describe What Is Ahead",
                            style = MaterialTheme.typography.labelLarge,
                            color = HighContrastText
                        )
                    }
                }
            }

            // Camera Preview + Privacy / Scene Watch Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Camera View",
                                style = MaterialTheme.typography.titleMedium,
                                color = HighContrastText
                            )
                            Text(
                                text = when {
                                    !permissions.hasCamera -> "Camera permission requested only when you ask a visual question."
                                    isCameraReady -> "Captures single frame on request (no continuous video upload)."
                                    else -> "Preparing camera..."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = HighContrastSecondaryText
                            )
                        }
                        OutlinedButton(
                            onClick = { onTogglePreviewVisible(!settings.showCameraPreview) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_preview_button")
                                .semantics {
                                    contentDescription = if (settings.showCameraPreview) {
                                        "Hide camera preview to save battery"
                                    } else {
                                        "Show camera preview"
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = if (settings.showCameraPreview) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                                tint = RestrainedCyan
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (settings.showCameraPreview) "Hide" else "Show")
                        }
                    }

                    if (permissions.hasCamera && settings.showCameraPreview) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .semantics {
                                    contentDescription = "Live back camera viewfinder"
                                }
                        ) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Scene Watch (Major Change)",
                                style = MaterialTheme.typography.labelLarge,
                                color = HighContrastText
                            )
                            Text(
                                text = "Checks on-device light changes every 9+ seconds; off by default.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HighContrastSecondaryText
                            )
                        }
                        Switch(
                            checked = settings.sceneWatchEnabled,
                            onCheckedChange = onToggleSceneWatch,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scene_watch_switch")
                                .semantics {
                                    contentDescription = "Toggle automatic major scene change alerts"
                                }
                        )
                    }
                }
            }
        }

        PrimaryVoiceActionButton(
            voiceState = voiceState,
            onClick = onPrimaryVoiceTap,
            onEndSession = onEndSession
        )
    }
}
