package com.example.onboarding

import com.example.voice.VoiceOption

enum class SetupMode {
    VOICE_FIRST,
    MANUAL
}

enum class VoiceSetupStep(val stepNumber: Int, val title: String) {
    WELCOME_AND_CONSENT(1, "Consent & Access"),
    CHOOSE_VOICE(2, "Choose Voice"),
    PREFERENCES_SPEED_AND_HAPTICS(3, "Speed & Feedback"),
    WAKE_PHRASE_CHOICE(4, "Hands-Free Wake Phrase"),
    COMPLETED(5, "Ready")
}

enum class ManualSetupStep(val stepNumber: Int, val title: String) {
    CONSENT_PERMISSIONS(1, "Permissions & Consent"),
    CHOOSE_VOICE(2, "Choose a Voice"),
    PREFERENCES(3, "Assistant Preferences")
}

data class OnboardingUiState(
    val isPreferencesLoaded: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val mode: SetupMode = SetupMode.VOICE_FIRST,
    val voiceStep: VoiceSetupStep = VoiceSetupStep.WELCOME_AND_CONSENT,
    val manualStep: ManualSetupStep = ManualSetupStep.CONSENT_PERMISSIONS,
    val currentPromptText: String = "Preparing ClearPath setup...",
    val statusHint: String = "Speak naturally anytime to reply or interrupt.",
    val voiceCandidates: List<VoiceOption> = emptyList(),
    val currentVoiceIndex: Int = 0,
    val isPlayingShowcase: Boolean = false,
    val awaitingPermissionBatch: Boolean = false
)

sealed interface OnboardingVoiceReply {
    data object Affirmative : OnboardingVoiceReply
    data object Negative : OnboardingVoiceReply
    data object NextOrAnother : OnboardingVoiceReply
    data object Previous : OnboardingVoiceReply
    data object ListAndPlayVoices : OnboardingVoiceReply
    data class SelectVoiceNumber(val oneBasedIndex: Int) : OnboardingVoiceReply
    data class SelectVoiceByQuery(val query: String) : OnboardingVoiceReply
    data object FasterSpeech : OnboardingVoiceReply
    data object SlowerSpeech : OnboardingVoiceReply
    data object DisableHaptics : OnboardingVoiceReply
    data object EnableHaptics : OnboardingVoiceReply
    data object DisableContinuousListening : OnboardingVoiceReply
    data object SwitchToManual : OnboardingVoiceReply
    data object SkipSetup : OnboardingVoiceReply
    data object RepeatPrompt : OnboardingVoiceReply
    data class Unrecognized(val rawText: String) : OnboardingVoiceReply
}

object OnboardingVoiceParser {

    private val ordinals = mapOf(
        "first" to 1, "one" to 1, "1" to 1, "1st" to 1,
        "second" to 2, "two" to 2, "2" to 2, "2nd" to 2,
        "third" to 3, "three" to 3, "3" to 3, "3rd" to 3,
        "fourth" to 4, "four" to 4, "4" to 4, "4th" to 4,
        "fifth" to 5, "five" to 5, "5" to 5, "5th" to 5,
        "sixth" to 6, "six" to 6, "6" to 6, "6th" to 6
    )

    fun parse(rawTranscript: String, currentStep: VoiceSetupStep): OnboardingVoiceReply {
        val text = rawTranscript.trim()
        val lower = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (lower.isBlank()) return OnboardingVoiceReply.Unrecognized(text)

        if (lower in setOf("repeat", "say that again", "pardon", "again", "what did you say")) {
            return OnboardingVoiceReply.RepeatPrompt
        }

        if (lower.contains("manual setup") || lower.contains("manual mode") || lower == "manual") {
            return OnboardingVoiceReply.SwitchToManual
        }

        if (lower.contains("skip setup") || lower == "skip") {
            return OnboardingVoiceReply.SkipSetup
        }

        if (lower.contains("faster") || lower.contains("speed up")) {
            return OnboardingVoiceReply.FasterSpeech
        }
        if (lower.contains("slower") || lower.contains("slow down")) {
            return OnboardingVoiceReply.SlowerSpeech
        }

        if (currentStep == VoiceSetupStep.CHOOSE_VOICE) {
            if (lower.contains("list") || lower.contains("available voice") || lower.contains("all voice") ||
                lower.contains("play each") || lower.contains("hear all") || lower.contains("what voice")
            ) {
                return OnboardingVoiceReply.ListAndPlayVoices
            }

            if (lower.contains("previous") || lower.contains("go back") || lower.contains("last voice")) {
                return OnboardingVoiceReply.Previous
            }

            if (lower.contains("another") || lower.contains("next") || lower.contains("different") ||
                lower.contains("try another") || lower.contains("change") || lower.contains("no") ||
                lower.contains("don't like") || lower.contains("dont like") || lower.contains("not this")
            ) {
                return OnboardingVoiceReply.NextOrAnother
            }

            // Check explicit numbered selection ("voice 2", "number 3", "second one", "option 1")
            val numberMatch = Regex("(?:voice|option|number)?\\s*(first|second|third|fourth|fifth|sixth|one|two|three|four|five|six|[1-6](?:st|nd|rd|th)?)")
                .find(lower)
            if (numberMatch != null) {
                val token = numberMatch.groupValues[1]
                val idx = ordinals[token]
                if (idx != null) {
                    return OnboardingVoiceReply.SelectVoiceNumber(idx)
                }
            }

            val localeKeywords = listOf("nigeria", "nigerian", "british", "uk", "american", "us", "indian", "australian", "female", "male")
            if (localeKeywords.any { lower.contains(it) }) {
                return OnboardingVoiceReply.SelectVoiceByQuery(lower)
            }
        }

        if (currentStep == VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS) {
            if (lower.contains("turn off haptic") || lower.contains("no haptic") ||
                lower.contains("disable haptic") || lower.contains("turn off vibration") ||
                lower.contains("no vibration")
            ) {
                return OnboardingVoiceReply.DisableHaptics
            }
            if (lower.contains("turn on haptic") || lower.contains("enable haptic")) {
                return OnboardingVoiceReply.EnableHaptics
            }
            if (lower.contains("turn off continuous") || lower.contains("no continuous") ||
                lower.contains("stop auto listen")
            ) {
                return OnboardingVoiceReply.DisableContinuousListening
            }
        }

        val yesWords = listOf(
            "yes", "yeah", "yep", "sure", "okay", "ok", "i consent", "consent", "allow",
            "agree", "keep", "keep it", "this one", "sounds good", "good", "fine",
            "default", "defaults", "continue", "done", "confirm", "enable", "turn it on"
        )
        if (yesWords.any { lower == it || lower.contains(it) }) {
            return OnboardingVoiceReply.Affirmative
        }

        val noWords = listOf("no", "nope", "nah", "not now", "later", "decline", "don't", "do not", "skip")
        if (noWords.any { lower == it || lower.startsWith("$it ") }) {
            return OnboardingVoiceReply.Negative
        }

        return OnboardingVoiceReply.Unrecognized(text)
    }
}
