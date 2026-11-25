package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.navigation.RoutingProviderMode
import com.example.settings.ClearPathSettings
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.WarmAmber
import com.example.voice.GeminiLiveState
import com.example.voice.LiveVoiceCatalog
import com.example.voice.VoiceUiState

@Composable
fun SettingsScreen(
    settings: ClearPathSettings,
    voiceState: VoiceUiState,
    preferOnDevice: Boolean,
    routingMode: RoutingProviderMode,
    onSpeechRateChange: (Float) -> Unit,
    onTestVoice: () -> Unit,
    onContinuousConvoChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onSceneWatchChange: (Boolean) -> Unit,
    onShowPreviewChange: (Boolean) -> Unit,
    onWakePhraseModeChange: (Boolean) -> Unit = {},
    onAutoListenOnOpenChange: (Boolean) -> Unit = {},
    onPreferOnDeviceChange: (Boolean) -> Unit,
    onSelectLiveVoice: (String) -> Unit = {},
    onSelectVoice: (String) -> Unit,
    onRoutingModeChange: (RoutingProviderMode) -> Unit,
    onSaveRoutingConfig: (String, String) -> Unit,
    onRerunOnboarding: () -> Unit = {},
    onPrimaryVoiceTap: () -> Unit = {},
    onEndSession: () -> Unit = {},
    modifier: Modifier = Modifier,
    liveState: GeminiLiveState = GeminiLiveState()
) {
    var orsKeyDraft by remember(settings.orsApiKey) { mutableStateOf(settings.orsApiKey) }
    var customUrlDraft by remember(settings.customRoutingBaseUrl) { mutableStateOf(settings.customRoutingBaseUrl) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeading("Voice & Feedback Preferences")

            Text(
                text = "All settings below can also be listed, toggled, or edited by voice (for example: \"What settings are available?\" or \"Turn off vibration\").",
                style = MaterialTheme.typography.bodyMedium,
                color = RestrainedCyan
            )

        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Speech Speed: ${"%.1f".format(settings.speechRate)}x",
                    style = MaterialTheme.typography.titleMedium,
                    color = WarmAmber,
                    modifier = Modifier.testTag("speech_rate_label")
                )
                Slider(
                    value = settings.speechRate,
                    onValueChange = onSpeechRateChange,
                    valueRange = 0.6f..1.8f,
                    steps = 5,
                    modifier = Modifier.testTag("speech_rate_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSpeechRateChange((settings.speechRate - 0.2f).coerceAtLeast(0.6f)) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("slower_button")
                    ) {
                        Text("Slower")
                    }
                    OutlinedButton(
                        onClick = { onSpeechRateChange((settings.speechRate + 0.2f).coerceAtMost(1.8f)) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("faster_button")
                    ) {
                        Text("Faster")
                    }
                    Button(
                        onClick = onTestVoice,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("test_voice_button")
                    ) {
                        Text("Test")
                    }
                }

                SettingToggleRow(
                    title = "Continuous Session Listening",
                    subtitle = "Automatically listen for follow-up questions after ClearPath finishes speaking.",
                    checked = settings.continuousConversation,
                    onCheckedChange = onContinuousConvoChange,
                    testTag = "continuous_convo_switch"
                )

                SettingToggleRow(
                    title = "Haptic Accessibility Cues",
                    subtitle = "Distinct vibrations when listening starts, stops, processing completes, or turns approach.",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = onHapticsChange,
                    testTag = "haptics_switch"
                )

                SettingToggleRow(
                    title = "Scene Watch (Major Change)",
                    subtitle = "Checks on-device light changes every 9+ seconds while idle.",
                    checked = settings.sceneWatchEnabled,
                    onCheckedChange = onSceneWatchChange,
                    testTag = "settings_scene_watch_switch"
                )

                SettingToggleRow(
                    title = "Show Camera Viewfinder",
                    subtitle = "Visual camera preview on screen (hiding saves battery without disabling voice camera checks).",
                    checked = settings.showCameraPreview,
                    onCheckedChange = onShowPreviewChange,
                    testTag = "settings_show_preview_switch"
                )

                SettingToggleRow(
                    title = "Wake-Phrase Mode (\"ClearPath activate\")",
                    subtitle = "Runs a foreground microphone service with an ongoing notification so you can say \"ClearPath activate\" or \"Hey ClearPath\" hands-free. Android restricts closed-app hardware hotword to pre-installed system apps.",
                    checked = settings.wakePhraseModeEnabled,
                    onCheckedChange = onWakePhraseModeChange,
                    testTag = "wake_phrase_switch"
                )

                SettingToggleRow(
                    title = "Auto-Listen When App Opens",
                    subtitle = "Starts listening automatically when ClearPath opens (ideal with \"Hey Google, open ClearPath\" or Assistant launch).",
                    checked = settings.autoListenOnOpen,
                    onCheckedChange = onAutoListenOnOpenChange,
                    testTag = "auto_listen_on_open_switch"
                )

                SettingToggleRow(
                    title = "Prefer On-Device AI (Supported Hardware)",
                    subtitle = "Attempts Gemini Nano via AICore for single-turn English queries; falls back to Cloud Gemini 3.8 Flash.",
                    checked = preferOnDevice,
                    onCheckedChange = onPreferOnDeviceChange,
                    testTag = "prefer_on_device_switch"
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "HD ClearPath Live Voice",
                    style = MaterialTheme.typography.titleMedium,
                    color = WarmAmber
                )
                LiveVoiceCatalog.voices.take(8).forEach { liveVoice ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = liveVoice.name.equals(settings.preferredLiveVoice, ignoreCase = true),
                            onClick = { onSelectLiveVoice(liveVoice.name) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = liveVoice.spokenLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HighContrastText
                        )
                    }
                }
            }
        }

        if (voiceState.availableVoices.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Offline Fallback TTS Voice",
                        style = MaterialTheme.typography.titleMedium,
                        color = HighContrastText
                    )
                    voiceState.availableVoices.take(5).forEach { voice ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = voice.name == settings.preferredVoiceName,
                                onClick = { onSelectVoice(voice.name) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = voice.displayLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = HighContrastText
                            )
                        }
                    }
                }
            }
        }

        SectionHeading("OpenStreetMap Routing Configuration")
        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoutingProviderMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == routingMode,
                            onClick = { onRoutingModeChange(mode) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("routing_mode_${mode.name.lowercase()}")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.labelLarge, color = HighContrastText)
                            Text(mode.policyNote, style = MaterialTheme.typography.bodyMedium, color = HighContrastSecondaryText)
                        }
                    }
                }

                OutlinedTextField(
                    value = orsKeyDraft,
                    onValueChange = { orsKeyDraft = it },
                    label = { Text("OpenRouteService Free API Key (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ors_key_input")
                )

                OutlinedTextField(
                    value = customUrlDraft,
                    onValueChange = { customUrlDraft = it },
                    label = { Text("Custom Self-Hosted OSRM Base URL (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_osrm_input")
                )

                Button(
                    onClick = { onSaveRoutingConfig(orsKeyDraft, customUrlDraft) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("save_routing_config_button")
                ) {
                    Text("Save Routing Settings")
                }
            }
        }

        SectionHeading("Offline vs Online Capabilities & Safety")
        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, RestrainedCyan)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Available Offline: ML Kit Latin OCR text reading, device compass heading, haptic cues, and installed offline TTS voices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HighContrastText
                )
                Text(
                    text = "Supported Devices Only: Gemini Nano on-device AI via Android AICore (foreground single-turn English/Korean).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HighContrastText
                )
                Text(
                    text = "Requires Internet: Gemini 3.8 Flash scene understanding, follow-up chat, Yoruba/Igbo/Hausa/Pidgin translation, and OpenStreetMap pedestrian routing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HighContrastText
                )
                Text(
                    text = "Privacy & Wake Phrase: Microphone and camera never record silently in the background. When Wake-Phrase Mode is explicitly turned on, Android requires a visible persistent microphone notification and ClearPath can also be launched via your phone's Assistant gesture.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RestrainedCyan
                )
            }
        }

        OutlinedButton(
            onClick = onRerunOnboarding,
            border = BorderStroke(1.dp, WarmAmber),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("rerun_onboarding_button")
        ) {
            Text(
                text = "Re-run First-Time Voice Setup",
                style = MaterialTheme.typography.labelLarge,
                color = WarmAmber
            )
        }
        }

        PrimaryVoiceActionButton(
            voiceState = voiceState,
            onClick = onPrimaryVoiceTap,
            onEndSession = onEndSession,
            liveState = liveState
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = HighContrastText)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = HighContrastSecondaryText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag)
        )
    }
}
