package com.example

import com.example.ai.ClearPathIntent
import com.example.ai.VoiceIntentParser
import com.example.camera.SceneChangeDetector
import com.example.navigation.GeoPoint
import com.example.navigation.NavigationEngine
import com.example.navigation.OsmRoutingClient
import com.example.navigation.PedestrianRoute
import com.example.navigation.RouteStep
import com.example.translation.LanguageCatalog
import com.example.translation.LanguageCapabilityReport
import com.example.voice.TtsSupportStatus
import com.example.voice.WakePhraseDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearPathUnitTests {

    @Test
    fun voiceIntentParser_routesCoreCommandsAccurately() {
        assertEquals(
            ClearPathIntent.RepeatLast,
            VoiceIntentParser.parse("Repeat that", hasPriorSceneContext = true)
        )
        assertEquals(
            ClearPathIntent.StopSpeaking,
            VoiceIntentParser.parse("Stop talking", hasPriorSceneContext = true)
        )
        assertEquals(
            ClearPathIntent.EndSession,
            VoiceIntentParser.parse("End session", hasPriorSceneContext = true)
        )
        assertEquals(
            ClearPathIntent.WhereAmI,
            VoiceIntentParser.parse("Where am I right now?", hasPriorSceneContext = false)
        )
        assertEquals(
            ClearPathIntent.NavigationStatus,
            VoiceIntentParser.parse("Am I still on the correct route?", hasPriorSceneContext = false)
        )
        assertEquals(
            ClearPathIntent.StopNavigation,
            VoiceIntentParser.parse("Stop navigation", hasPriorSceneContext = false)
        )

        val navIntent = VoiceIntentParser.parse("Take me to the nearest pharmacy", hasPriorSceneContext = false)
        assertTrue(navIntent is ClearPathIntent.NavigateTo)
        assertEquals("the nearest pharmacy", (navIntent as ClearPathIntent.NavigateTo).query)

        val sceneIntent = VoiceIntentParser.parse("What is in front of me?", hasPriorSceneContext = true)
        assertTrue(sceneIntent is ClearPathIntent.DescribeScene)

        val followUpIntent = VoiceIntentParser.parse("What color is it?", hasPriorSceneContext = true)
        assertTrue(followUpIntent is ClearPathIntent.DescribeScene)

        val pureFollowUp = VoiceIntentParser.parse("Can you explain more?", hasPriorSceneContext = true)
        assertTrue(pureFollowUp is ClearPathIntent.FollowUpQuestion)

        val translateCamera = VoiceIntentParser.parse("Translate this sign to Yoruba", hasPriorSceneContext = false)
        assertTrue(translateCamera is ClearPathIntent.ReadCameraText)
        assertEquals("yo", (translateCamera as ClearPathIntent.ReadCameraText).targetLanguage?.code)
    }

    @Test
    fun sceneChangeDetector_enforcesIntervalThresholdAndCooldown() {
        val detector = SceneChangeDetector(
            meanDeltaThreshold = 20f,
            cooldownMs = 8_000L,
            sampleIntervalMs = 1_000L
        )
        val darkFrame = FloatArray(144) { 30f }
        val brightFrame = FloatArray(144) { 100f }

        // First baseline sample never triggers
        assertFalse(detector.evaluate(darkFrame, 1_000L))
        // Too soon (< 1_000ms) -> ignored
        assertFalse(detector.evaluate(brightFrame, 1_500L))
        // Large change after interval -> triggers
        assertTrue(detector.evaluate(brightFrame, 2_500L))
        // During 8_000ms cooldown -> suppressed even on huge delta
        assertFalse(detector.evaluate(darkFrame, 5_000L))
        // After cooldown expires -> triggers again
        assertTrue(detector.evaluate(brightFrame, 11_000L))
    }

    @Test
    fun navigationEngine_calculatesPolylineAndOffRouteCorrectly() {
        val p0 = GeoPoint(6.5244, 3.3792) // Lagos reference
        val p1 = GeoPoint(6.5254, 3.3792) // ~111m north
        val p2 = GeoPoint(6.5254, 3.3802) // ~111m east

        val dist = NavigationEngine.haversineMeters(p0, p1)
        assertTrue("Expected ~111m, got $dist", dist in 105.0..118.0)

        val route = PedestrianRoute(
            destinationName = "City Pharmacy",
            totalDistanceMeters = 222.0,
            estimatedMinutes = 3,
            polyline = listOf(p0, p1, p2),
            steps = listOf(
                RouteStep("Head north", 111.0, p0, "Broad St"),
                RouteStep("Turn right onto Marina", 111.0, p1, "Marina"),
                RouteStep("Arrive at destination", 0.0, p2, "")
            ),
            providerDescription = "Test"
        )

        // User near p1 (approaching step 1)
        val nearTurn = NavigationEngine.evaluateProgress(
            currentPoint = GeoPoint(6.5253, 3.3792),
            route = route,
            currentStepIndex = 1,
            announcedStepIndex = 0
        )
        assertFalse(nearTurn.isOffRoute)
        assertNotNull(nearTurn.spokenAnnouncement)

        // User 60m east of p0->p1 segment -> off route
        val offRoute = NavigationEngine.evaluateProgress(
            currentPoint = GeoPoint(6.5249, 3.3798),
            route = route,
            currentStepIndex = 0,
            announcedStepIndex = 0
        )
        assertTrue(offRoute.isOffRoute)
        assertTrue(offRoute.offRouteMeters > NavigationEngine.OFF_ROUTE_THRESHOLD_METERS)
    }

    @Test
    fun navigationEngine_cardinalsBearingsAndOsrmFormatting() {
        assertEquals("North", NavigationEngine.cardinalDirection(0f))
        assertEquals("East", NavigationEngine.cardinalDirection(90f))
        assertEquals("South", NavigationEngine.cardinalDirection(180f))
        assertEquals("West", NavigationEngine.cardinalDirection(270f))

        val p0 = GeoPoint(6.5244, 3.3792)
        val eastPoint = GeoPoint(6.5244, 3.3892)
        val brng = NavigationEngine.bearingDegrees(p0, eastPoint)
        assertTrue("Expected ~90 deg east, got $brng", brng in 85f..95f)
        assertEquals("to your right", NavigationEngine.relativeTurnCue(0f, 90f))
        assertEquals("straight ahead", NavigationEngine.relativeTurnCue(88f, 90f))

        val mapsIntent = VoiceIntentParser.parse("Open in maps to Ikeja City Mall", hasPriorSceneContext = false)
        assertTrue(mapsIntent is ClearPathIntent.OpenInMapsApp)

        assertEquals(
            "Turn left onto Herbert Macaulay Way",
            OsmRoutingClient.formatOsrmStep("turn", "left", "Herbert Macaulay Way")
        )
    }

    @Test
    fun languageCatalog_includesNigerianPriorityLanguagesWithHonestReporting() {
        val codes = LanguageCatalog.languages.filter { it.isNigerianPriority }.map { it.code }
        assertTrue(codes.containsAll(listOf("en", "yo", "ig", "ha", "pcm")))

        val yoruba = LanguageCatalog.findByCodeOrName("Yoruba")!!
        val report = LanguageCapabilityReport(yoruba, TtsSupportStatus.UNSUPPORTED)
        assertTrue(report.honestySummary().contains("Native spoken voice unavailable"))
    }

    @Test
    fun voiceIntentParser_handlesSettingsListingToolRequestsAndOfflineEdits() {
        val listIntent = VoiceIntentParser.parse("What settings are available?", hasPriorSceneContext = false)
        assertEquals(ClearPathIntent.ListSettings, listIntent)

        val toggleIntent = VoiceIntentParser.parse("Turn off vibration cues", hasPriorSceneContext = false)
        assertTrue(toggleIntent is ClearPathIntent.AssistantToolRequest)

        val editHaptics = VoiceIntentParser.parseOfflineSettingEdit("Turn off vibration cues")
        assertNotNull(editHaptics)
        assertEquals("haptics", editHaptics?.settingKey)
        assertEquals(false, editHaptics?.booleanValue)

        val editWatch = VoiceIntentParser.parseOfflineSettingEdit("Toggle scene watch")
        assertNotNull(editWatch)
        assertEquals("scene_watch", editWatch?.settingKey)
        assertTrue(editWatch?.toggleBoolean == true)

        val editSpeed = VoiceIntentParser.parseOfflineSettingEdit("Set speech speed to 1.4")
        assertNotNull(editSpeed)
        assertEquals("speech_rate", editSpeed?.settingKey)
        assertEquals(1.4f, editSpeed?.floatValue)

        val editWake = VoiceIntentParser.parseOfflineSettingEdit("Turn on wake phrase mode")
        assertNotNull(editWake)
        assertEquals("wake_phrase_mode", editWake?.settingKey)
        assertEquals(true, editWake?.booleanValue)
    }

    @Test
    fun wakePhraseDetector_matchesAndExtractsTrailingCommand() {
        val exact = WakePhraseDetector.match("ClearPath activate")
        assertNotNull(exact)
        assertNull(exact?.trailingCommand)

        val spaced = WakePhraseDetector.match("Hey Clear Path, what is in front of me?")
        assertNotNull(spaced)
        assertEquals("what is in front of me", spaced?.trailingCommand)

        val nonWake = WakePhraseDetector.match("Take me to the nearest pharmacy")
        assertNull(nonWake)
    }

    @Test
    fun onboardingVoiceParser_handlesConsentVoicesAndPreferences() {
        assertEquals(
            com.example.onboarding.OnboardingVoiceReply.Affirmative,
            com.example.onboarding.OnboardingVoiceParser.parse(
                "Yes, I consent",
                com.example.onboarding.VoiceSetupStep.WELCOME_AND_CONSENT
            )
        )
        assertEquals(
            com.example.onboarding.OnboardingVoiceReply.NextOrAnother,
            com.example.onboarding.OnboardingVoiceParser.parse(
                "Give me another voice",
                com.example.onboarding.VoiceSetupStep.CHOOSE_VOICE
            )
        )
        assertEquals(
            com.example.onboarding.OnboardingVoiceReply.ListAndPlayVoices,
            com.example.onboarding.OnboardingVoiceParser.parse(
                "List available voices and play each",
                com.example.onboarding.VoiceSetupStep.CHOOSE_VOICE
            )
        )
        assertEquals(
            com.example.onboarding.OnboardingVoiceReply.SelectVoiceNumber(3),
            com.example.onboarding.OnboardingVoiceParser.parse(
                "Voice 3",
                com.example.onboarding.VoiceSetupStep.CHOOSE_VOICE
            )
        )
        assertEquals(
            com.example.onboarding.OnboardingVoiceReply.DisableHaptics,
            com.example.onboarding.OnboardingVoiceParser.parse(
                "Turn off vibration",
                com.example.onboarding.VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS
            )
        )
        assertEquals(
            com.example.onboarding.OnboardingVoiceReply.SwitchToManual,
            com.example.onboarding.OnboardingVoiceParser.parse(
                "Manual setup",
                com.example.onboarding.VoiceSetupStep.CHOOSE_VOICE
            )
        )
    }

    @Test
    fun voiceIntentParser_routesLiveCameraShareCommandsToAssistantTools() {
        val startShare = VoiceIntentParser.parse("Share my camera", hasPriorSceneContext = false)
        assertTrue(startShare is ClearPathIntent.AssistantToolRequest)

        val stopShare = VoiceIntentParser.parse("Stop sharing camera", hasPriorSceneContext = true)
        assertTrue(stopShare is ClearPathIntent.AssistantToolRequest)

        val flipCam = VoiceIntentParser.parse("Flip camera to front", hasPriorSceneContext = true)
        assertTrue(flipCam is ClearPathIntent.AssistantToolRequest)
    }

    @Test
    fun voiceIntentParser_routesInterpreterAndSceneryCommandsToAssistantTools() {
        val listenTranslate = VoiceIntentParser.parse("ClearPath listen and translate", hasPriorSceneContext = false)
        assertTrue(listenTranslate is ClearPathIntent.AssistantToolRequest)

        val stopTranslate = VoiceIntentParser.parse("Stop translating", hasPriorSceneContext = true)
        assertTrue(stopTranslate is ClearPathIntent.AssistantToolRequest)

        val scenery = VoiceIntentParser.parse("Give me a summary about my scenery", hasPriorSceneContext = true)
        assertTrue(scenery is ClearPathIntent.AssistantToolRequest)
    }
}
