package com.example.translation

import com.example.voice.TtsSupportStatus

data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val sttLocaleTag: String,
    val ttsLocaleTag: String,
    val isNigerianPriority: Boolean,
    val sttNote: String,
    val aiTranslationSupported: Boolean = true
)

data class LanguageCapabilityReport(
    val language: SupportedLanguage,
    val ttsStatus: TtsSupportStatus
) {
    fun honestySummary(): String {
        val ttsText = when (ttsStatus) {
            TtsSupportStatus.AVAILABLE -> "Spoken output available on this device"
            TtsSupportStatus.NETWORK_REQUIRED_OR_MISSING_PACK ->
                "Spoken output requires downloading a device voice pack"
            TtsSupportStatus.UNSUPPORTED ->
                "Native spoken voice unavailable on this device; text shown on screen"
        }
        return "${language.displayName}: ${language.sttNote}. $ttsText."
    }
}

object LanguageCatalog {

    val autoDetect = SupportedLanguage(
        code = "auto",
        displayName = "Auto-Detect",
        nativeName = "Auto-Detect",
        sttLocaleTag = "en-US",
        ttsLocaleTag = "en-US",
        isNigerianPriority = false,
        sttNote = "Uses AI language identification for text/input"
    )

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage(
            code = "en",
            displayName = "English",
            nativeName = "English",
            sttLocaleTag = "en-US",
            ttsLocaleTag = "en-US",
            isNigerianPriority = true,
            sttNote = "Full speech input supported"
        ),
        SupportedLanguage(
            code = "yo",
            displayName = "Yoruba",
            nativeName = "Yorùbá",
            sttLocaleTag = "yo-NG",
            ttsLocaleTag = "yo-NG",
            isNigerianPriority = true,
            sttNote = "Speech input via Google Speech (device-dependent)"
        ),
        SupportedLanguage(
            code = "ig",
            displayName = "Igbo",
            nativeName = "Asụsụ Igbo",
            sttLocaleTag = "ig-NG",
            ttsLocaleTag = "ig-NG",
            isNigerianPriority = true,
            sttNote = "Speech input via Google Speech (device-dependent)"
        ),
        SupportedLanguage(
            code = "ha",
            displayName = "Hausa",
            nativeName = "Hausa",
            sttLocaleTag = "ha-NG",
            ttsLocaleTag = "ha-NG",
            isNigerianPriority = true,
            sttNote = "Speech input via Google Speech (device-dependent)"
        ),
        SupportedLanguage(
            code = "pcm",
            displayName = "Nigerian Pidgin",
            nativeName = "Naijá Pidgin",
            sttLocaleTag = "en-NG",
            ttsLocaleTag = "en-NG",
            isNigerianPriority = true,
            sttNote = "Uses Nigerian English speech input with AI Pidgin translation"
        ),
        SupportedLanguage(
            code = "fr",
            displayName = "French",
            nativeName = "Français",
            sttLocaleTag = "fr-FR",
            ttsLocaleTag = "fr-FR",
            isNigerianPriority = false,
            sttNote = "Full speech input supported"
        ),
        SupportedLanguage(
            code = "ar",
            displayName = "Arabic",
            nativeName = "العربية",
            sttLocaleTag = "ar-SA",
            ttsLocaleTag = "ar-SA",
            isNigerianPriority = false,
            sttNote = "Full speech input supported"
        ),
        SupportedLanguage(
            code = "es",
            displayName = "Spanish",
            nativeName = "Español",
            sttLocaleTag = "es-ES",
            ttsLocaleTag = "es-ES",
            isNigerianPriority = false,
            sttNote = "Full speech input supported"
        )
    )

    fun findByCodeOrName(query: String): SupportedLanguage? {
        val q = query.trim().lowercase()
        if (q == "auto" || q == "detect") return autoDetect
        return languages.firstOrNull {
            it.code == q ||
                it.displayName.lowercase() == q ||
                it.nativeName.lowercase() == q ||
                (q == "pidgin" && it.code == "pcm") ||
                (q == "yoruba" && it.code == "yo")
        }
    }
}
