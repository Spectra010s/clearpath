package com.example.ai

import com.example.translation.LanguageCatalog
import com.example.translation.SupportedLanguage

sealed interface ClearPathIntent {
    data object RepeatLast : ClearPathIntent
    data object StopSpeaking : ClearPathIntent
    data object EndSession : ClearPathIntent
    data class AdjustSpeechRate(val delta: Float) : ClearPathIntent
    data object ListSettings : ClearPathIntent
    data class AssistantToolRequest(val utterance: String) : ClearPathIntent
    data class ReadCameraText(val targetLanguage: SupportedLanguage?) : ClearPathIntent
    data class TranslateSpeech(val textToTranslate: String?, val targetLanguage: SupportedLanguage) : ClearPathIntent
    data class DescribeScene(val question: String) : ClearPathIntent
    data class FollowUpQuestion(val question: String) : ClearPathIntent
}

data class OfflineSettingEdit(
    val settingKey: String,
    val booleanValue: Boolean? = null,
    val toggleBoolean: Boolean = false,
    val floatValue: Float? = null,
    val stringValue: String? = null
)

object VoiceIntentParser {

    fun parse(rawTranscript: String, hasPriorSceneContext: Boolean): ClearPathIntent {
        val text = rawTranscript.trim()
        val lower = text.lowercase()

        if (lower in setOf("repeat", "repeat that", "say that again", "say again", "pardon")) {
            return ClearPathIntent.RepeatLast
        }
        if (lower.startsWith("repeat ") && (lower.contains("answer") || lower.contains("last"))) {
            return ClearPathIntent.RepeatLast
        }

        if (lower in setOf("stop talking", "stop speaking", "be quiet", "quiet", "silence", "hush")) {
            return ClearPathIntent.StopSpeaking
        }

        if (lower in setOf("end session", "stop listening", "goodbye", "close session", "turn off mic")) {
            return ClearPathIntent.EndSession
        }

        if (lower.contains("speak faster") || lower.contains("talk faster") || lower.contains("faster please")) {
            return ClearPathIntent.AdjustSpeechRate(0.15f)
        }
        if (lower.contains("speak slower") || lower.contains("talk slower") || lower.contains("slower please")) {
            return ClearPathIntent.AdjustSpeechRate(-0.15f)
        }

        if (isListSettingsQuery(lower)) {
            return ClearPathIntent.ListSettings
        }

        if (isAssistantToolQuery(lower)) {
            return ClearPathIntent.AssistantToolRequest(text)
        }

        if (lower.contains("translate")) {
            val targetLang = detectTargetLanguageInPhrase(lower) ?: LanguageCatalog.languages.first { it.code == "en" }
            val hasCameraCue = lower.contains("this") || lower.contains("sign") || lower.contains("paper") ||
                lower.contains("camera") || lower.contains("written") || lower.contains("label")
            if (hasCameraCue) {
                return ClearPathIntent.ReadCameraText(targetLang)
            }
            val quoteSplit = text.substringAfter("translate ", "").trim()
            val cleaned = quoteSplit
                .replace(Regex("(?i)\\b(into|to)\\s+(english|yoruba|yorùbá|igbo|hausa|pidgin|nigerian pidgin|french|arabic|spanish)\\b"), "")
                .trim()
            return ClearPathIntent.TranslateSpeech(
                textToTranslate = cleaned.takeIf { it.isNotBlank() },
                targetLanguage = targetLang
            )
        }

        if (lower.contains("read what is written") || lower.contains("read this") ||
            lower.contains("read the text") || lower.contains("what does this say") ||
            lower.contains("what is written") || lower.startsWith("read ")
        ) {
            val targetLang = detectTargetLanguageInPhrase(lower)
            return ClearPathIntent.ReadCameraText(targetLang)
        }

        val visualKeywords = listOf(
            "in front of me", "front of me", "what is this", "describe", "around me",
            "room", "door", "table", "chair", "person", "someone", "obstacle",
            "walk through", "path", "look", "see", "color", "object", "holding",
            "sign", "bottle", "money", "currency", "stairs", "steps", "camera"
        )
        if (visualKeywords.any { lower.contains(it) } || !hasPriorSceneContext) {
            return ClearPathIntent.DescribeScene(text)
        }

        return ClearPathIntent.FollowUpQuestion(text)
    }

    private fun isListSettingsQuery(lower: String): Boolean {
        val hasSettingsWord = lower.contains("setting") || lower.contains("preference") || lower.contains("option")
        val hasListCue = lower.contains("what") || lower.contains("list") || lower.contains("available") ||
            lower.contains("show") || lower.contains("tell me") || lower.contains("read") || lower.contains("check")
        return hasSettingsWord && hasListCue &&
            !lower.contains("turn on") && !lower.contains("turn off") &&
            !lower.contains("enable") && !lower.contains("disable") &&
            !lower.contains("toggle") && !lower.contains("change") && !lower.contains("set ")
    }

    private fun isAssistantToolQuery(lower: String): Boolean {
        val isLocationOrNavigation = lower.contains("where am i") ||
            lower.contains("my location") || lower.contains("current address") ||
            lower.contains("navigate") || lower.contains("directions") ||
            lower.contains("take me to") || lower.contains("walk me to") ||
            lower.contains("guide me to") || lower.contains("how do i get to") ||
            lower.contains("nearest") || lower.contains("next turn") ||
            lower.contains("where should i turn") || lower.contains("am i still on") ||
            lower.contains("correct route") || lower.contains("on route") ||
            lower.contains("stop navigation") || lower.contains("cancel navigation") ||
            lower.contains("end navigation") || lower.contains("cancel route") ||
            lower.contains("open in maps") || lower.contains("open maps") ||
            lower.contains("use google maps") || lower.contains("launch maps")
        if (isLocationOrNavigation) {
            return true
        }
        val isLiveInterpreterOrScenery = lower.contains("listen and translate") ||
            lower.contains("listen & translate") ||
            lower.contains("listen and interpret") ||
            lower.contains("stop translating") ||
            lower.contains("exit interpreter") ||
            lower.contains("scenery") ||
            (lower.contains("summary") && (lower.contains("around") || lower.contains("location") || lower.contains("place")))
        if (isLiveInterpreterOrScenery) {
            return true
        }
        val isCameraShareOrFlip = lower.contains("live camera") ||
            lower.contains("camera share") ||
            lower.contains("flip camera") ||
            lower.contains("switch camera") ||
            ((lower.contains("share") || lower.contains("sharing")) && lower.contains("camera")) ||
            ((lower.contains("turn on") || lower.contains("turn off") || lower.contains("start ") || lower.contains("stop ") || lower.contains("pause ")) && lower.contains("camera"))
        if (isCameraShareOrFlip) {
            return true
        }
        val settingSubjects = listOf(
            "setting", "settings", "preference", "preferences",
            "scene watch", "haptic", "vibration", "vibrate",
            "continuous listening", "continuous conversation", "auto listen",
            "speech speed", "speech rate", "voice speed", "talking speed",
            "on-device", "on device", "gemini nano",
            "camera preview", "viewfinder",
            "wake phrase", "wake word", "hands free", "hands-free", "auto listen on open", "listen on open",
            "voice", "voices", "routing mode", "translation language", "target language", "source language"
        )
        val actionWords = listOf(
            "turn on", "turn off", "switch on", "switch off", "enable", "disable",
            "toggle", "change", "set ", "adjust", "hide", "show", "mute", "unmute",
            "what ", "list ", "which ", "edit "
        )
        return settingSubjects.any { lower.contains(it) } && actionWords.any { lower.contains(it) }
    }

    fun parseOfflineSettingEdit(rawTranscript: String): OfflineSettingEdit? {
        val lower = rawTranscript.lowercase()
        val desiredBool = when {
            lower.contains("turn on") || lower.contains("switch on") || lower.contains("enable") || lower.contains("show ") -> true
            lower.contains("turn off") || lower.contains("switch off") || lower.contains("disable") || lower.contains("hide ") -> false
            else -> null
        }
        val wantsToggle = lower.contains("toggle")

        return when {
            lower.contains("scene watch") -> OfflineSettingEdit(
                settingKey = "scene_watch",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("haptic") || lower.contains("vibration") || lower.contains("vibrate") -> OfflineSettingEdit(
                settingKey = "haptics",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("continuous") || lower.contains("auto listen") -> OfflineSettingEdit(
                settingKey = "continuous_conversation",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("camera preview") || lower.contains("viewfinder") -> OfflineSettingEdit(
                settingKey = "show_camera_preview",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("on-device") || lower.contains("on device") || lower.contains("gemini nano") -> OfflineSettingEdit(
                settingKey = "prefer_on_device_ai",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("wake phrase") || lower.contains("wake word") || lower.contains("hands free") || lower.contains("hands-free") -> OfflineSettingEdit(
                settingKey = "wake_phrase_mode",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("listen on open") || lower.contains("auto open") -> OfflineSettingEdit(
                settingKey = "auto_listen_on_open",
                booleanValue = desiredBool,
                toggleBoolean = desiredBool == null && wantsToggle
            )
            lower.contains("speech speed") || lower.contains("speech rate") || lower.contains("voice speed") -> {
                val num = Regex("""(\d+(?:\.\d+)?)""").find(lower)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                num?.let { OfflineSettingEdit(settingKey = "speech_rate", floatValue = it.coerceIn(0.6f, 1.8f)) }
            }
            lower.contains("target language") || lower.contains("translation language") -> {
                val lang = detectTargetLanguageInPhrase(lower)
                    ?: LanguageCatalog.languages.firstOrNull { lower.contains(it.displayName.lowercase()) }
                lang?.let { OfflineSettingEdit(settingKey = "translation_target_language", stringValue = it.code) }
            }
            else -> null
        }
    }

    private fun detectTargetLanguageInPhrase(lower: String): SupportedLanguage? {
        val patterns = listOf(
            "yoruba" to "yo",
            "yorùbá" to "yo",
            "igbo" to "ig",
            "hausa" to "ha",
            "pidgin" to "pcm",
            "french" to "fr",
            "arabic" to "ar",
            "spanish" to "es",
            "english" to "en"
        )
        for ((word, code) in patterns) {
            if (lower.contains("to $word") || lower.contains("into $word") || lower.contains("in $word")) {
                return LanguageCatalog.findByCodeOrName(code)
            }
        }
        return null
    }
}
