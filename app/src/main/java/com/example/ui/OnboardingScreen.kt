package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.onboarding.ManualSetupStep
import com.example.onboarding.OnboardingUiState
import com.example.onboarding.SetupMode
import com.example.onboarding.VoiceSetupStep
import com.example.permissions.ClearPathPermission
import com.example.permissions.PermissionSnapshot
import com.example.settings.ClearPathSettings
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnWarmAmber
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldContainer
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.WarmAmberContainer
import com.example.voice.DECIBEL_BAND_COUNT
import com.example.voice.GeminiLiveState
import com.example.voice.LiveVoiceCatalog
import com.example.voice.LiveVoiceOption
import com.example.voice.VoiceOption
import com.example.voice.VoicePhase
import com.example.voice.VoiceUiState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun OnboardingScreen(
    onboardingState: OnboardingUiState,
    voiceState: VoiceUiState,
    liveState: GeminiLiveState,
    decibelBands: List<Float>,
    audioLevel: Float,
    settings: ClearPathSettings,
    permissions: PermissionSnapshot,
    onGrantAllPermissions: () -> Unit,
    onGrantSinglePermission: (ClearPathPermission) -> Unit,
    onSwitchToManual: () -> Unit,
    onSwitchToVoice: () -> Unit,
    onSkip: () -> Unit,
    onManualStepChange: (ManualSetupStep) -> Unit,
    onSelectAndSampleLiveVoice: (LiveVoiceOption) -> Unit,
    onSelectAndSampleVoice: (VoiceOption) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onContinuousConvoChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onWakePhraseModeChange: (Boolean) -> Unit,
    onAutoListenOnOpenChange: (Boolean) -> Unit,
    onFinishManualSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAiSpeaking = if (liveState.isActive) liveState.isAiSpeaking else voiceState.phase == VoicePhase.SPEAKING
    val isUserSpeaking = if (liveState.isActive) liveState.isUserSpeaking || !liveState.isAiSpeaking else voiceState.phase == VoicePhase.LISTENING

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ObsidianBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            when {
                                isAiSpeaking -> WarmAmber.copy(alpha = 0.14f)
                                isUserSpeaking -> ReadyEmerald.copy(alpha = 0.12f)
                                else -> RestrainedCyan.copy(alpha = 0.09f)
                            },
                            ObsidianBackground
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            when (onboardingState.mode) {
                SetupMode.VOICE_FIRST -> VoiceFirstSetupLayer(
                    onboardingState = onboardingState,
                    voiceState = voiceState,
                    liveState = liveState,
                    decibelBands = decibelBands,
                    audioLevel = audioLevel,
                    permissions = permissions,
                    onGrantAllPermissions = onGrantAllPermissions,
                    onSwitchToManual = onSwitchToManual,
                    onSkip = onSkip,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                )

                SetupMode.MANUAL -> ManualSetupLayer(
                    onboardingState = onboardingState,
                    voiceState = voiceState,
                    audioLevel = audioLevel,
                    settings = settings,
                    permissions = permissions,
                    onSwitchToVoice = onSwitchToVoice,
                    onSkip = onSkip,
                    onManualStepChange = onManualStepChange,
                    onGrantAllPermissions = onGrantAllPermissions,
                    onGrantSinglePermission = onGrantSinglePermission,
                    onSelectAndSampleLiveVoice = onSelectAndSampleLiveVoice,
                    onSelectAndSampleVoice = onSelectAndSampleVoice,
                    onSpeechRateChange = onSpeechRateChange,
                    onContinuousConvoChange = onContinuousConvoChange,
                    onHapticsChange = onHapticsChange,
                    onWakePhraseModeChange = onWakePhraseModeChange,
                    onAutoListenOnOpenChange = onAutoListenOnOpenChange,
                    onFinishManualSetup = onFinishManualSetup,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceFirstSetupLayer(
    onboardingState: OnboardingUiState,
    voiceState: VoiceUiState,
    liveState: GeminiLiveState,
    decibelBands: List<Float>,
    audioLevel: Float,
    permissions: PermissionSnapshot,
    onGrantAllPermissions: () -> Unit,
    onSwitchToManual: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stepIndex = onboardingState.voiceStep.stepNumber.coerceIn(1, 4)
    val effectiveSpeaking = if (liveState.isActive) liveState.isAiSpeaking else voiceState.phase == VoicePhase.SPEAKING
    val effectiveListening = if (liveState.isActive) (liveState.isUserSpeaking || !liveState.isAiSpeaking) else voiceState.phase == VoicePhase.LISTENING

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Minimal top header (no main nav menu during first-time setup)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ClearPath Setup",
                style = MaterialTheme.typography.titleLarge,
                color = HighContrastText,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Surface(
                color = ObsidianSurfaceElevated,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                val badgeText = if (liveState.isActive) {
                    "Live · ${liveState.currentVoiceName}"
                } else {
                    "Step $stepIndex of 4"
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = WarmAmber,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // Center Pure Music-Playing Decibel Equalizer (no visual text clutter)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val statusA11yText = when {
                liveState.isConnecting -> "Connecting real-time voice"
                effectiveSpeaking -> "ClearPath is speaking"
                effectiveListening -> "Listening to you"
                else -> onboardingState.voiceStep.title
            }
            MusicDecibelEqualizer(
                decibelBands = decibelBands,
                audioLevel = audioLevel,
                isAiSpeaking = effectiveSpeaking,
                isUserSpeaking = effectiveListening,
                isConnecting = liveState.isConnecting || voiceState.phase == VoicePhase.PROCESSING,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 240.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = statusA11yText
                    }
                    .testTag("onboarding_decibel_visualizer")
            )

            if (!permissions.hasMicrophone) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onGrantAllPermissions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarmAmber,
                        contentColor = OnWarmAmber
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("onboarding_grant_access_button")
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Grant Access & Start Voice Setup",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Bottom Actions ONLY: Manual Setup & Skip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onSwitchToManual,
                border = BorderStroke(1.5.dp, RestrainedCyan),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("onboarding_manual_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = RestrainedCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Manual Setup",
                    style = MaterialTheme.typography.labelLarge,
                    color = HighContrastText
                )
            }

            OutlinedButton(
                onClick = onSkip,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .heightIn(min = 54.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("onboarding_skip_button")
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelLarge,
                    color = HighContrastSecondaryText
                )
            }
        }
    }
}

/**
 * Pure music-player-style decibel spectrum visualizer.
 * No buttons, no mic icons, no tap-to-speak labels inside — reacts smoothly to PCM decibel bands.
 */
@Composable
fun MusicDecibelEqualizer(
    decibelBands: List<Float>,
    audioLevel: Float,
    isAiSpeaking: Boolean,
    isUserSpeaking: Boolean,
    isConnecting: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "music_eq_motion")
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "eq_shimmer_phase"
    )
    val idleWave by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_idle_wave"
    )

    val animatedMasterLevel by animateFloatAsState(
        targetValue = when {
            isAiSpeaking -> audioLevel.coerceAtLeast(0.35f)
            isUserSpeaking -> audioLevel.coerceAtLeast(0.14f)
            isConnecting -> 0.24f
            else -> idleWave
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "eq_master_level"
    )

    val primaryColor = when {
        isAiSpeaking -> WarmAmber
        isUserSpeaking -> ReadyEmerald
        else -> RestrainedCyan
    }
    val secondaryColor = when {
        isAiSpeaking -> RestrainedCyan
        isUserSpeaking -> WarmAmber
        else -> WarmAmber.copy(alpha = 0.5f)
    }

    val a11yDescription = when {
        isConnecting -> "Connecting real-time audio"
        isAiSpeaking -> "Audio decibel visualizer active while ClearPath speaks"
        isUserSpeaking -> "Audio decibel visualizer listening to your voice"
        else -> "Audio decibel visualizer ready"
    }

    Box(
        modifier = modifier.semantics {
            contentDescription = a11yDescription
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f
            val count = DECIBEL_BAND_COUNT
            val totalSpacingRatio = 0.42f
            val slotWidth = w / count.toFloat()
            val barWidth = (slotWidth * (1f - totalSpacingRatio)).coerceIn(4.dp.toPx(), 12.dp.toPx())
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

            // Soft horizontal ambient glow like a live music visualizer stage
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.18f + 0.16f * animatedMasterLevel),
                        Color.Transparent
                    ),
                    center = Offset(w / 2f, centerY),
                    radius = (w * 0.48f)
                ),
                radius = w * 0.48f,
                center = Offset(w / 2f, centerY)
            )

            // Subtle baseline hair-line across the center
            drawLine(
                color = primaryColor.copy(alpha = 0.22f),
                start = Offset(12.dp.toPx(), centerY),
                end = Offset(w - 12.dp.toPx(), centerY),
                strokeWidth = 1.5.dp.toPx()
            )

            val centerIdx = (count - 1) / 2f
            val maxHalfBarHeight = (h * 0.44f)

            for (i in 0 until count) {
                val bandSample = decibelBands.getOrNull(i) ?: 0.06f
                val distNorm = abs(i - centerIdx) / centerIdx
                val bellEnvelope = (1f - distNorm * 0.36f)
                val ambientRipple = 0.55f + 0.45f * sin(shimmerPhase * 1.4f + i * 0.45f)

                val effectiveNorm = if (isAiSpeaking || isUserSpeaking) {
                    ((bandSample * 0.80f + animatedMasterLevel * ambientRipple * 0.20f) * bellEnvelope)
                        .coerceIn(0.06f, 1f)
                } else {
                    val restMotion = (0.08f + 0.14f * ambientRipple) * bellEnvelope
                    (if (isConnecting) restMotion * 1.7f else restMotion).coerceIn(0.05f, 0.35f)
                }

                val halfBar = (effectiveNorm * maxHalfBarHeight).coerceAtLeast(6.dp.toPx())
                val xLeft = i * slotWidth + (slotWidth - barWidth) / 2f
                val barColor = if (distNorm < 0.45f) {
                    primaryColor.copy(alpha = (0.65f + 0.35f * effectiveNorm).coerceIn(0f, 1f))
                } else {
                    androidx.compose.ui.graphics.lerp(primaryColor, secondaryColor, (distNorm - 0.45f) * 1.2f)
                        .copy(alpha = (0.55f + 0.40f * effectiveNorm).coerceIn(0f, 1f))
                }

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(xLeft, centerY - halfBar),
                    size = Size(barWidth, halfBar * 2f),
                    cornerRadius = corner
                )
            }
        }
    }
}

@Composable
private fun ManualSetupLayer(
    onboardingState: OnboardingUiState,
    voiceState: VoiceUiState,
    audioLevel: Float,
    settings: ClearPathSettings,
    permissions: PermissionSnapshot,
    onSwitchToVoice: () -> Unit,
    onSkip: () -> Unit,
    onManualStepChange: (ManualSetupStep) -> Unit,
    onGrantAllPermissions: () -> Unit,
    onGrantSinglePermission: (ClearPathPermission) -> Unit,
    onSelectAndSampleLiveVoice: (LiveVoiceOption) -> Unit,
    onSelectAndSampleVoice: (VoiceOption) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onContinuousConvoChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onWakePhraseModeChange: (Boolean) -> Unit,
    onAutoListenOnOpenChange: (Boolean) -> Unit,
    onFinishManualSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentStep = onboardingState.manualStep

    BackHandler {
        when (currentStep) {
            ManualSetupStep.CONSENT_PERMISSIONS -> onSwitchToVoice()
            ManualSetupStep.CHOOSE_VOICE -> onManualStepChange(ManualSetupStep.CONSENT_PERMISSIONS)
            ManualSetupStep.PREFERENCES -> onManualStepChange(ManualSetupStep.CHOOSE_VOICE)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with Step pill and Return to Voice Setup
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = currentStep.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = HighContrastText
                )
                Text(
                    text = "Manual Setup · Step ${currentStep.stepNumber} of 3",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarmAmber
                )
            }
            TextButton(
                onClick = onSwitchToVoice,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("onboarding_switch_to_voice_button")
            ) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = RestrainedCyan)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Voice Setup", color = RestrainedCyan)
            }
        }

        // Body per Manual Step
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (currentStep) {
                ManualSetupStep.CONSENT_PERMISSIONS -> ManualConsentStep(
                    permissions = permissions,
                    onGrantAll = onGrantAllPermissions,
                    onGrantSingle = onGrantSinglePermission
                )

                ManualSetupStep.CHOOSE_VOICE -> ManualVoicePickerStep(
                    liveVoices = LiveVoiceCatalog.voices,
                    selectedLiveVoiceName = settings.preferredLiveVoice,
                    voices = onboardingState.voiceCandidates.ifEmpty { voiceState.availableVoices },
                    selectedVoiceName = settings.preferredVoiceName,
                    speechRate = settings.speechRate,
                    voicePhase = voiceState.phase,
                    audioLevel = audioLevel,
                    onSelectAndSampleLiveVoice = onSelectAndSampleLiveVoice,
                    onSelectAndSample = onSelectAndSampleVoice,
                    onSpeechRateChange = onSpeechRateChange
                )

                ManualSetupStep.PREFERENCES -> ManualPreferencesStep(
                    settings = settings,
                    onContinuousConvoChange = onContinuousConvoChange,
                    onHapticsChange = onHapticsChange,
                    onWakePhraseModeChange = onWakePhraseModeChange,
                    onAutoListenOnOpenChange = onAutoListenOnOpenChange
                )
            }
        }

        // Bottom Navigation Buttons for Manual Wizard (not main nav bar)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (currentStep) {
                ManualSetupStep.CONSENT_PERMISSIONS -> {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .heightIn(min = 54.dp)
                            .testTag("manual_skip_button")
                    ) {
                        Text("Skip", color = HighContrastSecondaryText)
                    }
                    Button(
                        onClick = { onManualStepChange(ManualSetupStep.CHOOSE_VOICE) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarmAmber,
                            contentColor = OnWarmAmber
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("manual_next_button")
                    ) {
                        Text("Next: Choose Voice", style = MaterialTheme.typography.labelLarge)
                    }
                }

                ManualSetupStep.CHOOSE_VOICE -> {
                    OutlinedButton(
                        onClick = { onManualStepChange(ManualSetupStep.CONSENT_PERMISSIONS) },
                        modifier = Modifier
                            .heightIn(min = 54.dp)
                            .testTag("manual_back_button")
                    ) {
                        Text("Back", color = HighContrastText)
                    }
                    Button(
                        onClick = { onManualStepChange(ManualSetupStep.PREFERENCES) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarmAmber,
                            contentColor = OnWarmAmber
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("manual_next_button")
                    ) {
                        Text("Next: Preferences", style = MaterialTheme.typography.labelLarge)
                    }
                }

                ManualSetupStep.PREFERENCES -> {
                    OutlinedButton(
                        onClick = { onManualStepChange(ManualSetupStep.CHOOSE_VOICE) },
                        modifier = Modifier
                            .heightIn(min = 54.dp)
                            .testTag("manual_back_button")
                    ) {
                        Text("Back", color = HighContrastText)
                    }
                    Button(
                        onClick = onFinishManualSetup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ReadyEmerald,
                            contentColor = OnWarmAmber
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .testTag("manual_finish_button")
                    ) {
                        Text("Finish Setup", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualConsentStep(
    permissions: PermissionSnapshot,
    onGrantAll: () -> Unit,
    onGrantSingle: (ClearPathPermission) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ClearPath uses your device sensors only when requested or when Wake-Phrase Mode is explicitly active. Review and grant access below:",
            style = MaterialTheme.typography.bodyLarge,
            color = HighContrastSecondaryText
        )

        Button(
            onClick = onGrantAll,
            colors = ButtonDefaults.buttonColors(
                containerColor = RestrainedCyan,
                contentColor = OnWarmAmber
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("manual_grant_all_button")
        ) {
            Text("Grant All Recommended Permissions", style = MaterialTheme.typography.labelLarge)
        }

        PermissionConsentCard(
            permission = ClearPathPermission.MICROPHONE,
            granted = permissions.hasMicrophone,
            onAllow = { onGrantSingle(ClearPathPermission.MICROPHONE) }
        )
        PermissionConsentCard(
            permission = ClearPathPermission.CAMERA,
            granted = permissions.hasCamera,
            onAllow = { onGrantSingle(ClearPathPermission.CAMERA) }
        )
        PermissionConsentCard(
            permission = ClearPathPermission.LOCATION,
            granted = permissions.hasLocation,
            onAllow = { onGrantSingle(ClearPathPermission.LOCATION) }
        )
        PermissionConsentCard(
            permission = ClearPathPermission.NOTIFICATIONS,
            granted = permissions.hasNotifications,
            onAllow = { onGrantSingle(ClearPathPermission.NOTIFICATIONS) }
        )
    }
}

@Composable
private fun PermissionConsentCard(
    permission: ClearPathPermission,
    granted: Boolean,
    onAllow: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, if (granted) ReadyEmerald else ObsidianBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(permission.title, style = MaterialTheme.typography.titleMedium, color = HighContrastText)
                Text(permission.rationale, style = MaterialTheme.typography.bodyMedium, color = HighContrastSecondaryText)
            }
            if (granted) {
                Surface(
                    color = ReadyEmeraldContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Granted",
                            tint = ReadyEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Granted", style = MaterialTheme.typography.labelMedium, color = ReadyEmerald)
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onAllow,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Allow", color = WarmAmber)
                }
            }
        }
    }
}

@Composable
private fun ManualVoicePickerStep(
    liveVoices: List<LiveVoiceOption>,
    selectedLiveVoiceName: String,
    voices: List<VoiceOption>,
    selectedVoiceName: String,
    speechRate: Float,
    voicePhase: VoicePhase,
    audioLevel: Float,
    onSelectAndSampleLiveVoice: (LiveVoiceOption) -> Unit,
    onSelectAndSample: (VoiceOption) -> Unit,
    onSpeechRateChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Choose ClearPath's live AI voice and optional offline fallback voice, then tap Next.",
            style = MaterialTheme.typography.bodyLarge,
            color = HighContrastSecondaryText
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Offline TTS Speed: ${"%.1f".format(speechRate)}x",
                        style = MaterialTheme.typography.titleMedium,
                        color = WarmAmber
                    )
                    if (voicePhase == VoicePhase.SPEAKING) {
                        Text(
                            text = "Playing sample · ${(audioLevel * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = ReadyEmerald
                        )
                    }
                }
                Slider(
                    value = speechRate,
                    onValueChange = onSpeechRateChange,
                    valueRange = 0.6f..1.8f,
                    steps = 5
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "HD ClearPath Live Voices",
                    style = MaterialTheme.typography.titleMedium,
                    color = WarmAmber,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            itemsIndexed(liveVoices, key = { _, lv -> "live_${lv.name}" }) { idx, liveVoice ->
                val isSelected = liveVoice.name.equals(selectedLiveVoiceName, ignoreCase = true)
                Surface(
                    color = if (isSelected) WarmAmberContainer else ObsidianSurface,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.5.dp, if (isSelected) WarmAmber else ObsidianBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAndSampleLiveVoice(liveVoice) }
                        .minimumInteractiveComponentSize()
                        .testTag("manual_live_voice_item_$idx")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectAndSampleLiveVoice(liveVoice) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${liveVoice.name} · ${liveVoice.style}",
                                style = MaterialTheme.typography.titleMedium,
                                color = HighContrastText
                            )
                            Text(
                                text = "${liveVoice.gender} · Real-time conversational voice",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) WarmAmber else HighContrastSecondaryText
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Select live voice",
                            tint = if (isSelected) WarmAmber else RestrainedCyan
                        )
                    }
                }
            }

            if (voices.isNotEmpty()) {
                item {
                    Text(
                        text = "Offline Fallback TTS Voices",
                        style = MaterialTheme.typography.titleMedium,
                        color = RestrainedCyan,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                itemsIndexed(voices, key = { _, v -> "tts_${v.name}" }) { idx, voice ->
                    val isSelected = voice.name == selectedVoiceName
                    Surface(
                        color = if (isSelected) WarmAmberContainer else ObsidianSurface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (isSelected) WarmAmber else ObsidianBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAndSample(voice) }
                            .minimumInteractiveComponentSize()
                            .testTag("manual_voice_item_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectAndSample(voice) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = voice.displayLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = HighContrastText
                                )
                                Text(
                                    text = if (isSelected && voicePhase == VoicePhase.SPEAKING) {
                                        "Playing sample now..."
                                    } else {
                                        "Tap to play offline sample & select"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) WarmAmber else HighContrastSecondaryText
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.VolumeUp,
                                contentDescription = "Play voice sample",
                                tint = if (isSelected) WarmAmber else RestrainedCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualPreferencesStep(
    settings: ClearPathSettings,
    onContinuousConvoChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onWakePhraseModeChange: (Boolean) -> Unit,
    onAutoListenOnOpenChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Customize how ClearPath listens and gives feedback. You can change any of these by voice later.",
            style = MaterialTheme.typography.bodyLarge,
            color = HighContrastSecondaryText
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ManualToggleItem(
                    title = "Continuous Session Listening",
                    subtitle = "Automatically listen for follow-up questions after ClearPath finishes speaking.",
                    checked = settings.continuousConversation,
                    onCheckedChange = onContinuousConvoChange
                )
                ManualToggleItem(
                    title = "Haptic Accessibility Cues",
                    subtitle = "Tactile vibration pulses when listening starts, speech plays, or turns approach.",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = onHapticsChange
                )
                ManualToggleItem(
                    title = "Wake-Phrase Mode (\"ClearPath activate\")",
                    subtitle = "Keep a persistent foreground notification active so saying \"ClearPath activate\" starts listening hands-free.",
                    checked = settings.wakePhraseModeEnabled,
                    onCheckedChange = onWakePhraseModeChange
                )
                ManualToggleItem(
                    title = "Auto-Listen When App Opens",
                    subtitle = "Open the microphone immediately whenever ClearPath launches.",
                    checked = settings.autoListenOnOpen,
                    onCheckedChange = onAutoListenOnOpenChange
                )
            }
        }
    }
}

@Composable
private fun ManualToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = HighContrastText)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = HighContrastSecondaryText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.minimumInteractiveComponentSize()
        )
    }
}
