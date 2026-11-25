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
    fun navigationScreen_rendersAccessibleControls() {
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
    fun onboardingScreen_rendersMusicEqualizerWithoutTapToSpeakAndBottomActions() {
        composeRule.setContent {
            ClearPathTheme {
                com.example.ui.OnboardingScreen(
                    onboardingState = com.example.onboarding.OnboardingUiState(
                        isPreferencesLoaded = true,
                        hasCompletedOnboarding = false
                    ),
                    voiceState = VoiceUiState(),
                    liveState = com.example.voice.GeminiLiveState(isActive = true, isAiSpeaking = true),
                    decibelBands = List(com.example.voice.DECIBEL_BAND_COUNT) { 0.5f },
                    audioLevel = 0.6f,
                    settings = ClearPathSettings(),
                    permissions = PermissionSnapshot(hasMicrophone = true),
                    onGrantAllPermissions = {},
                    onGrantSinglePermission = {},
                    onSwitchToManual = {},
                    onSwitchToVoice = {},
                    onSkip = {},
                    onManualStepChange = {},
                    onSelectAndSampleLiveVoice = {},
                    onSelectAndSampleVoice = {},
                    onSpeechRateChange = {},
                    onContinuousConvoChange = {},
                    onHapticsChange = {},
                    onWakePhraseModeChange = {},
                    onAutoListenOnOpenChange = {},
                    onFinishManualSetup = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_decibel_visualizer").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_manual_button").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_skip_button").assertIsDisplayed()
        composeRule.onAllNodes(androidx.compose.ui.test.hasText("TAP TO SPEAK", ignoreCase = true))
            .let { nodes ->
                org.junit.Assert.assertEquals(0, nodes.fetchSemanticsNodes().size)
            }
    }

    @Test
    fun assistScreen_displaysLiveCameraShareControls() {
        var shareTapped = false
        var fullscreenTapped = false
        var interpreterTapped = false
        var sceneryTapped = false
        composeRule.setContent {
            ClearPathTheme {
                AssistScreen(
                    voiceState = VoiceUiState(),
                    assistState = AssistUiState(),
                    settings = ClearPathSettings(),
                    permissions = PermissionSnapshot(hasCamera = true, hasMicrophone = true),
                    isCameraReady = true,
                    onBindCamera = { _, _ -> },
                    onPrimaryVoiceTap = {},
                    onEndSession = {},
                    onRepeatLast = {},
                    onDescribeScene = {},
                    onReadText = {},
                    onToggleSceneWatch = {},
                    onTogglePreviewVisible = {},
                    isLiveCameraShareActive = true,
                    onToggleLiveCameraShare = { shareTapped = true },
                    onOpenFullScreenCamera = { fullscreenTapped = true },
                    onToggleInterpreterMode = { interpreterTapped = true },
                    onScenerySummary = { sceneryTapped = true }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_camera_share_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("flip_camera_button").assertIsDisplayed()
        composeRule.onNodeWithTag("fullscreen_camera_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("listen_translate_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("scenery_summary_button").assertIsDisplayed().performClick()
        assertTrue(shareTapped)
        assertTrue(fullscreenTapped)
        assertTrue(interpreterTapped)
        assertTrue(sceneryTapped)
    }
}
