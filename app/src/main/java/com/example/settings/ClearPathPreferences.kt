package com.example.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.clearPathDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clearpath_preferences"
)

data class ClearPathSettings(
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val preferredVoiceName: String = "",
    val spokenLanguageTag: String = "en-NG",
    val translationSourceCode: String = "auto",
    val translationTargetCode: String = "en",
    val continuousConversation: Boolean = true,
    val sceneWatchEnabled: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val showCameraPreview: Boolean = true,
    val preferOnDeviceAi: Boolean = false,
    val wakePhraseModeEnabled: Boolean = false,
    val autoListenOnOpen: Boolean = false,
    val routingProviderMode: String = "OPEN_ROUTE_SERVICE",
    val orsApiKey: String = "",
    val customRoutingBaseUrl: String = "",
    val hasSpokenWelcome: Boolean = false
)

class ClearPathPreferencesRepository(private val context: Context) {

    private object Keys {
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val PREFERRED_VOICE = stringPreferencesKey("preferred_voice")
        val SPOKEN_LANG = stringPreferencesKey("spoken_lang")
        val TRANS_SOURCE = stringPreferencesKey("trans_source")
        val TRANS_TARGET = stringPreferencesKey("trans_target")
        val CONTINUOUS_CONVO = booleanPreferencesKey("continuous_convo")
        val SCENE_WATCH = booleanPreferencesKey("scene_watch")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SHOW_PREVIEW = booleanPreferencesKey("show_preview")
        val PREFER_ON_DEVICE = booleanPreferencesKey("prefer_on_device")
        val WAKE_PHRASE = booleanPreferencesKey("wake_phrase")
        val AUTO_LISTEN_ON_OPEN = booleanPreferencesKey("auto_listen_on_open")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val ORS_API_KEY = stringPreferencesKey("ors_api_key")
        val CUSTOM_ROUTING_URL = stringPreferencesKey("custom_routing_url")
        val HAS_WELCOME = booleanPreferencesKey("has_welcome")
    }

    val settings: Flow<ClearPathSettings> = context.clearPathDataStore.data.map { prefs ->
        ClearPathSettings(
            speechRate = (prefs[Keys.SPEECH_RATE] ?: 1.0f).coerceIn(0.6f, 1.8f),
            speechPitch = (prefs[Keys.SPEECH_PITCH] ?: 1.0f).coerceIn(0.75f, 1.35f),
            preferredVoiceName = prefs[Keys.PREFERRED_VOICE] ?: "",
            spokenLanguageTag = prefs[Keys.SPOKEN_LANG] ?: "en-NG",
            translationSourceCode = prefs[Keys.TRANS_SOURCE] ?: "auto",
            translationTargetCode = prefs[Keys.TRANS_TARGET] ?: "en",
            continuousConversation = prefs[Keys.CONTINUOUS_CONVO] ?: true,
            sceneWatchEnabled = prefs[Keys.SCENE_WATCH] ?: false,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            showCameraPreview = prefs[Keys.SHOW_PREVIEW] ?: true,
            preferOnDeviceAi = prefs[Keys.PREFER_ON_DEVICE] ?: false,
            wakePhraseModeEnabled = prefs[Keys.WAKE_PHRASE] ?: false,
            autoListenOnOpen = prefs[Keys.AUTO_LISTEN_ON_OPEN] ?: false,
            routingProviderMode = prefs[Keys.ROUTING_MODE] ?: "OPEN_ROUTE_SERVICE",
            orsApiKey = prefs[Keys.ORS_API_KEY] ?: "",
            customRoutingBaseUrl = prefs[Keys.CUSTOM_ROUTING_URL] ?: "",
            hasSpokenWelcome = prefs[Keys.HAS_WELCOME] ?: false
        )
    }

    suspend fun setSpeechRate(rate: Float) {
        context.clearPathDataStore.edit { it[Keys.SPEECH_RATE] = rate.coerceIn(0.6f, 1.8f) }
    }

    suspend fun setPreferredVoice(name: String) {
        context.clearPathDataStore.edit { it[Keys.PREFERRED_VOICE] = name }
    }

    suspend fun setSpokenLanguageTag(tag: String) {
        context.clearPathDataStore.edit { it[Keys.SPOKEN_LANG] = tag }
    }

    suspend fun setTranslationLanguages(sourceCode: String, targetCode: String) {
        context.clearPathDataStore.edit {
            it[Keys.TRANS_SOURCE] = sourceCode
            it[Keys.TRANS_TARGET] = targetCode
        }
    }

    suspend fun setContinuousConversation(enabled: Boolean) {
        context.clearPathDataStore.edit { it[Keys.CONTINUOUS_CONVO] = enabled }
    }

    suspend fun setSceneWatchEnabled(enabled: Boolean) {
        context.clearPathDataStore.edit { it[Keys.SCENE_WATCH] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.clearPathDataStore.edit { it[Keys.HAPTICS] = enabled }
    }

    suspend fun setShowCameraPreview(show: Boolean) {
        context.clearPathDataStore.edit { it[Keys.SHOW_PREVIEW] = show }
    }

    suspend fun setPreferOnDeviceAi(enabled: Boolean) {
        context.clearPathDataStore.edit { it[Keys.PREFER_ON_DEVICE] = enabled }
    }

    suspend fun setWakePhraseModeEnabled(enabled: Boolean) {
        context.clearPathDataStore.edit { it[Keys.WAKE_PHRASE] = enabled }
    }

    suspend fun setAutoListenOnOpen(enabled: Boolean) {
        context.clearPathDataStore.edit { it[Keys.AUTO_LISTEN_ON_OPEN] = enabled }
    }

    suspend fun setRoutingProviderMode(modeName: String) {
        context.clearPathDataStore.edit { it[Keys.ROUTING_MODE] = modeName }
    }

    suspend fun setRoutingConfig(orsApiKey: String, customBaseUrl: String) {
        context.clearPathDataStore.edit {
            it[Keys.ORS_API_KEY] = orsApiKey.trim()
            it[Keys.CUSTOM_ROUTING_URL] = customBaseUrl.trim()
        }
    }

    suspend fun markWelcomeSpoken() {
        context.clearPathDataStore.edit { it[Keys.HAS_WELCOME] = true }
    }
}
