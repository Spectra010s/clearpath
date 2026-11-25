package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.translation.LanguageCatalog
import com.example.translation.SupportedLanguage
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.OnWarmAmber
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.WarmAmber
import com.example.voice.TtsSupportStatus
import com.example.voice.VoiceUiState

@Composable
fun TranslationScreen(
    translationState: TranslationUiState,
    voiceState: VoiceUiState,
    onSelectSource: (SupportedLanguage) -> Unit,
    onSelectTarget: (SupportedLanguage) -> Unit,
    onInputChanged: (String) -> Unit,
    onTranslateText: () -> Unit,
    onReadAndTranslateCamera: () -> Unit,
    onRepeatOriginal: () -> Unit,
    onRepeatTranslation: () -> Unit,
    onPrimaryVoiceTap: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val targetReport = translationState.languageReports.firstOrNull {
        it.language.code == translationState.targetLanguage.code
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
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.5.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionHeading("Target Language")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguageCatalog.languages.forEach { lang ->
                            val selected = lang.code == translationState.targetLanguage.code
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectTarget(lang) },
                                label = {
                                    Text(
                                        text = lang.displayName,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("target_lang_${lang.code}"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WarmAmber,
                                    selectedLabelColor = OnWarmAmber
                                )
                            )
                        }
                    }

                    targetReport?.let { report ->
                        val isNativeTtsReady = report.ttsStatus == TtsSupportStatus.AVAILABLE
                        Text(
                            text = report.honestySummary(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isNativeTtsReady) RestrainedCyan else WarmAmber,
                            modifier = Modifier.testTag("language_capability_note")
                        )
                    }

                    Text(
                        text = "Source Language (or Auto-Detect)",
                        style = MaterialTheme.typography.labelLarge,
                        color = HighContrastText
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        (listOf(LanguageCatalog.autoDetect) + LanguageCatalog.languages).forEach { lang ->
                            val selected = lang.code == translationState.sourceLanguage.code
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectSource(lang) },
                                label = { Text(lang.displayName) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("source_lang_${lang.code}")
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = translationState.inputText,
                        onValueChange = onInputChanged,
                        label = { Text("Text to translate (or speak via Listen)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("translation_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onTranslateText,
                            enabled = !translationState.isTranslating && translationState.inputText.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("translate_text_button")
                        ) {
                            Icon(Icons.Rounded.Translate, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Translate")
                        }

                        OutlinedButton(
                            onClick = onReadAndTranslateCamera,
                            enabled = !translationState.isTranslating,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("camera_translate_button"),
                            border = BorderStroke(1.5.dp, WarmAmber)
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = WarmAmber)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("From Camera", color = HighContrastText)
                        }
                    }

                    Text(
                        text = translationState.statusNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HighContrastSecondaryText
                    )

                    translationState.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlertCoral,
                            modifier = Modifier.testTag("translation_error_text")
                        )
                    }
                }
            }

            translationState.lastOutput?.let { out ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(1.5.dp, RestrainedCyan)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${out.detectedLanguage} -> ${out.targetLanguage.displayName} (${out.origin.badgeLabel})",
                            style = MaterialTheme.typography.labelLarge,
                            color = RestrainedCyan
                        )
                        Text(
                            text = out.translatedText,
                            style = MaterialTheme.typography.titleLarge,
                            color = HighContrastText,
                            modifier = Modifier.testTag("translated_output_text")
                        )
                        Text(
                            text = "Original: ${out.originalText}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HighContrastSecondaryText
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRepeatOriginal,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .testTag("repeat_original_button")
                            ) {
                                Icon(Icons.Rounded.Replay, contentDescription = null, tint = RestrainedCyan)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Original")
                            }
                            OutlinedButton(
                                onClick = onRepeatTranslation,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .testTag("repeat_translation_button")
                            ) {
                                Icon(Icons.Rounded.Replay, contentDescription = null, tint = WarmAmber)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Translation")
                            }
                        }
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
