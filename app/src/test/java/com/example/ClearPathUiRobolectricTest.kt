package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.navigation.NavigationUiState
import com.example.permissions.PermissionSnapshot
import com.example.settings.ClearPathSettings
import com.example.ui.AssistScreen
import com.example.ui.AssistUiState
import com.example.ui.NavigationScreen
import com.example.ui.TranslationScreen
import com.example.ui.TranslationUiState
import com.example.ui.theme.ClearPathTheme
import com.example.voice.VoicePhase
import com.example.voice.VoiceUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClearPathUiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun assistScreen_displaysPrimaryVoiceButtonAndHandlesTap() {
        var tapped = false
        composeRule.setContent {
            ClearPathTheme {
                AssistScreen(
                    voiceState = VoiceUiState(phase = VoicePhase.IDLE),
                    assistState = AssistUiState(),
                    settings = ClearPathSettings(),
                    permissions = PermissionSnapshot(),
                    isCameraReady = false,
                    onBindCamera = { _, _ -> },
                    onPrimaryVoiceTap = { tapped = true },
                    onEndSession = {},
                    onRepeatLast = {},
                    onDescribeScene = {},
                    onReadText = {},
                    onToggleSceneWatch = {},
                    onTogglePreviewVisible = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("primary_voice_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("latest_answer_text").assertIsDisplayed()
        assertTrue(tapped)
    }

    @Test
    fun navigationAndTranslationScreens_renderAccessibleControls() {
        composeRule.setContent {
            ClearPathTheme {
                NavigationScreen(
                    navState = NavigationUiState(),
                    voiceState = VoiceUiState(),
                    onStartCompass = {},
                    onWhereAmI = {},
                    onDestinationChange = {},
                    onSearchAndRoute = {},
                    onSelectCandidate = {},
                    onOpenInMapsApp = {},
                    onCheckPathAhead = {},
                    onCheckRouteStatus = {},
                    onStopNavigation = {},
                    onPrimaryVoiceTap = {},
                    onEndSession = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("where_am_i_button").assertIsDisplayed()
        composeRule.onNodeWithTag("nearest_pharmacy_button").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_check_path_camera_button").assertIsDisplayed()
        composeRule.onNodeWithTag("open_phone_maps_button").assertIsDisplayed()
        composeRule.onNodeWithTag("compass_heading_text").assertIsDisplayed()
    }

    @Test
    fun translationScreen_showsNigerianLanguageChips() {
        composeRule.setContent {
            ClearPathTheme {
                TranslationScreen(
                    translationState = TranslationUiState(),
                    voiceState = VoiceUiState(),
                    onSelectSource = {},
                    onSelectTarget = {},
                    onInputChanged = {},
                    onTranslateText = {},
                    onReadAndTranslateCamera = {},
                    onRepeatOriginal = {},
                    onRepeatTranslation = {},
                    onPrimaryVoiceTap = {},
                    onEndSession = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("target_lang_yo").assertIsDisplayed()
        composeRule.onNodeWithTag("target_lang_ig").assertIsDisplayed()
        composeRule.onNodeWithTag("target_lang_ha").assertIsDisplayed()
        composeRule.onNodeWithTag("target_lang_pcm").assertIsDisplayed()
    }
}
