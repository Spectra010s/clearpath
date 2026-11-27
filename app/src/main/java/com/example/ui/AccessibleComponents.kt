package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.permissions.ClearPathPermission
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AlertCoralContainer
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnWarmAmber
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldContainer
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.RestrainedCyanContainer
import com.example.ui.theme.WarmAmber
import com.example.voice.GeminiLiveState
import com.example.voice.VoicePhase
import com.example.voice.VoiceUiState

@Composable
fun VoiceStatusBanner(
    voiceState: VoiceUiState,
    liveState: GeminiLiveState = GeminiLiveState(),
    modifier: Modifier = Modifier
) {
    val (label, containerColor, accentColor, icon) = when {
        liveState.isConnecting -> Quadruple("CONNECTING LIVE", RestrainedCyanContainer, RestrainedCyan, Icons.Rounded.HourglassTop)
        liveState.isActive && liveState.isInterpreterMode -> Quadruple(
            "LIVE · INTERPRETER (${liveState.interpreterTargetLanguage})",
            RestrainedCyanContainer,
            RestrainedCyan,
            Icons.Rounded.GraphicEq
        )
        liveState.isActive && liveState.isAiSpeaking -> Quadruple(
            "LIVE · SPEAKING (INTERRUPTIBLE)",
            MaterialTheme.colorScheme.primaryContainer,
            WarmAmber,
            Icons.Rounded.VolumeUp
        )
        liveState.isActive -> Quadruple(
            "LIVE · LISTENING",
            ReadyEmeraldContainer,
            ReadyEmerald,
            Icons.Rounded.GraphicEq
        )
        voiceState.phase == VoicePhase.LISTENING -> Quadruple("LISTENING", ReadyEmeraldContainer, ReadyEmerald, Icons.Rounded.GraphicEq)
        voiceState.phase == VoicePhase.PROCESSING -> Quadruple("PROCESSING", RestrainedCyanContainer, RestrainedCyan, Icons.Rounded.HourglassTop)
        voiceState.phase == VoicePhase.SPEAKING -> Quadruple("SPEAKING", MaterialTheme.colorScheme.primaryContainer, WarmAmber, Icons.Rounded.VolumeUp)
        voiceState.phase == VoicePhase.ERROR -> Quadruple("ATTENTION", AlertCoralContainer, AlertCoral, Icons.Rounded.Stop)
        else -> Quadruple(
            if (voiceState.isSessionActive) "SESSION ACTIVE" else "READY",
            ObsidianSurfaceElevated,
            RestrainedCyan,
            Icons.Rounded.Mic
        )
    }

    val detailText = when {
        liveState.isConnecting -> "Connecting real-time ClearPath audio..."
        liveState.isActive && liveState.isInterpreterMode ->
            "Listening to interpret nearby speech into ${liveState.interpreterTargetLanguage}. Say 'stop translating' anytime."
        liveState.isActive && liveState.inputTranscript.isNotBlank() -> "You: ${liveState.inputTranscript}"
        liveState.isActive && liveState.isAiSpeaking -> "Speak anytime to interrupt (${liveState.currentVoiceName})"
        liveState.isActive -> "Speak naturally · ${liveState.currentVoiceName}"
        voiceState.partialTranscript.isNotBlank() -> voiceState.partialTranscript
        else -> voiceState.statusDetail
    }

    val liveText = "$label: $detailText"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = liveText
            },
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, accentColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HighContrastText
                )
            }
        }
    }
}

@Composable
fun PrimaryVoiceActionButton(
    voiceState: VoiceUiState,
    onClick: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
    liveState: GeminiLiveState = GeminiLiveState()
) {
    val haptic = LocalHapticFeedback.current
    val (headline, subtext, containerColor, contentColor, icon) = when {
        liveState.isConnecting -> Quintuple(
            "Connecting Live Voice...",
            "Tap to cancel",
            RestrainedCyan,
            OnWarmAmber,
            Icons.Rounded.HourglassTop
        )
        liveState.isActive -> Quintuple(
            "ClearPath Live Active — Tap to Pause",
            "Full-duplex hands-free mode · Speak anytime to interrupt",
            ReadyEmerald,
            OnWarmAmber,
            Icons.Rounded.GraphicEq
        )
        voiceState.phase == VoicePhase.LISTENING -> Quintuple(
            "Listening — Tap to Stop",
            "Speak your question or command naturally",
            ReadyEmerald,
            OnWarmAmber,
            Icons.Rounded.Stop
        )
        voiceState.phase == VoicePhase.SPEAKING -> Quintuple(
            "Tap to Interrupt & Speak",
            "Stops current voice playback immediately",
            WarmAmber,
            OnWarmAmber,
            Icons.Rounded.Mic
        )
        voiceState.phase == VoicePhase.PROCESSING -> Quintuple(
            "Processing — Tap to Re-listen",
            "Working on your request",
            RestrainedCyan,
            OnWarmAmber,
            Icons.Rounded.HourglassTop
        )
        else -> Quintuple(
            "Listen Live",
            "Tap to start hands-free duplex conversation",
            WarmAmber,
            OnWarmAmber,
            Icons.Rounded.Mic
        )
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .minimumInteractiveComponentSize()
                .testTag("primary_voice_button")
                .semantics(mergeDescendants = true) {
                    contentDescription = "$headline. $subtext"
                },
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge,
                        color = contentColor
                    )
                    Text(
                        text = subtext,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.88f)
                    )
                }
            }
        }

        if (liveState.isActive || voiceState.isSessionActive || voiceState.phase == VoicePhase.SPEAKING) {
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEndSession()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("end_session_button"),
                border = BorderStroke(1.5.dp, ObsidianBorder)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = null,
                    tint = AlertCoral,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Stop Speaking & End Session",
                    style = MaterialTheme.typography.labelLarge,
                    color = HighContrastText
                )
            }
        }
    }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = HighContrastText,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun PermissionRationaleDialog(
    permission: ClearPathPermission,
    isWaiting: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = permission.title,
                style = MaterialTheme.typography.titleLarge,
                color = HighContrastText
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = permission.rationale,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isWaiting) {
                    Text(
                        text = "Waiting for Android permission result...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HighContrastText,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "Waiting for Android permission result"
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isWaiting,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_permission_button")
            ) {
                Text(if (isWaiting) "Waiting..." else "Allow ${permission.title.substringBefore(" ")}")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("dismiss_permission_button")
            ) {
                Text("Not now")
            }
        }
    )
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
