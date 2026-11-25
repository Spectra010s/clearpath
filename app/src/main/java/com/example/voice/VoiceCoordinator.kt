package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.haptics.AssistiveHaptics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "VoiceCoordinator"

enum class VoicePhase {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

enum class TtsSupportStatus {
    AVAILABLE,
    NETWORK_REQUIRED_OR_MISSING_PACK,
    UNSUPPORTED
}

data class VoiceOption(
    val name: String,
    val localeTag: String,
    val requiresNetwork: Boolean,
    val displayLabel: String = localeTag
)

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val isSessionActive: Boolean = false,
    val partialTranscript: String = "",
    val lastUserTranscript: String = "",
    val lastSpokenText: String = "",
    val speechRate: Float = 1.0f,
    val selectedVoiceName: String = "",
    val availableVoices: List<VoiceOption> = emptyList(),
    val isTtsReady: Boolean = false,
    val isRecognitionAvailable: Boolean = true,
    val statusDetail: String = "Ready. Activate Listen to speak."
)

sealed interface VoiceEvent {
    data class FinalTranscript(val text: String) : VoiceEvent
    data class RecognitionIssue(val message: String, val recoverable: Boolean) : VoiceEvent
}

class VoiceCoordinator(
    context: Context,
    private val haptics: AssistiveHaptics
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var speechRecognizer: SpeechRecognizer? = null
    private var focusRequest: AudioFocusRequest? = null

    private var currentListeningLocale: String = "en-US"
    private var continuousAutoListen: Boolean = true
    private var resumeListeningAfterUtterance: Boolean = false
    private var activeUtteranceId: String? = null
    private var onActiveUtteranceDone: (() -> Unit)? = null
    private var pendingShowcaseQueue: ArrayDeque<ShowcaseUtterance> = ArrayDeque()
    private var pendingShowcaseFinishAutoListen: Boolean = false

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _decibelBands = MutableStateFlow(List(DECIBEL_BAND_COUNT) { 0.06f })
    val decibelBands: StateFlow<List<Float>> = _decibelBands.asStateFlow()

    private var bandPhaseCounter = 0f

    private fun publishAudioLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _audioLevel.value = clamped
        if (clamped <= 0.02f) {
            _decibelBands.value = List(DECIBEL_BAND_COUNT) { 0.06f }
            return
        }
        bandPhaseCounter += 0.55f
        val center = (DECIBEL_BAND_COUNT - 1) / 2f
        _decibelBands.value = List(DECIBEL_BAND_COUNT) { idx ->
            val distNorm = abs(idx - center) / center
            val envelope = (1f - distNorm * 0.45f)
            val wave = 0.55f + 0.45f * sin(bandPhaseCounter + idx * 0.6f + (PI.toFloat() * 0.25f))
            (0.08f + clamped * envelope * wave * 0.92f).coerceIn(0.06f, 1f)
        }
    }

    private val _state = MutableStateFlow(
        VoiceUiState(
            isRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        )
    )
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private data class ShowcaseUtterance(
        val voiceName: String?,
        val text: String
    )

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val result = engine.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.UK)
            }
            engine.setSpeechRate(_state.value.speechRate)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        publishAudioLevel(0.68f)
                        haptics.onDecibelPulse(0.7f)
                        _state.update {
                            it.copy(
                                phase = VoicePhase.SPEAKING,
                                statusDetail = "Speaking response."
                            )
                        }
                    }
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    mainHandler.post {
                        if (utteranceId == activeUtteranceId) {
                            val wordLen = (end - start).coerceIn(1, 10)
                            val energy = (0.50f + (wordLen / 10f) * 0.48f).coerceIn(0.45f, 1f)
                            publishAudioLevel(energy)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (utteranceId == activeUtteranceId) {
                            if (pendingShowcaseQueue.isNotEmpty()) {
                                playNextShowcaseItem()
                                return@post
                            }
                            publishAudioLevel(0f)
                            abandonAudioFocus()
                            val completionCallback = onActiveUtteranceDone
                            onActiveUtteranceDone = null
                            val shouldAutoListen = resumeListeningAfterUtterance &&
                                _state.value.isSessionActive &&
                                continuousAutoListen
                            resumeListeningAfterUtterance = false
                            _state.update {
                                it.copy(
                                    phase = VoicePhase.IDLE,
                                    statusDetail = if (_state.value.isSessionActive) {
                                        "Session active. Tap Listen or speak."
                                    } else {
                                        "Ready. Tap Listen to begin."
                                    }
                                )
                            }
                            completionCallback?.invoke()
                            if (shouldAutoListen) {
                                mainHandler.postDelayed({
                                    if (_state.value.isSessionActive &&
                                        _state.value.phase == VoicePhase.IDLE
                                    ) {
                                        startListening(currentListeningLocale)
                                    }
                                }, 350L)
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainHandler.post {
                        pendingShowcaseQueue.clear()
                        onActiveUtteranceDone = null
                        publishAudioLevel(0f)
                        abandonAudioFocus()
                        resumeListeningAfterUtterance = false
                        _state.update {
                            it.copy(
                                phase = VoicePhase.IDLE,
                                statusDetail = "Speech playback interrupted."
                            )
                        }
                    }
                }
            })

            val voices = runCatching {
                val rawVoices = engine.voices
                    ?.filter { !it.isNetworkConnectionRequired || it.locale.language in setOf("en", "yo", "ig", "ha", "fr", "ar", "es") }
                    .orEmpty()
                val sorted = rawVoices.sortedWith(
                    compareBy<android.speech.tts.Voice>(
                        { it.isNetworkConnectionRequired },
                        {
                            val tag = it.locale.toLanguageTag()
                            when {
                                tag.equals("en-US", ignoreCase = true) -> 0
                                tag.equals("en-GB", ignoreCase = true) -> 1
                                it.locale.language == "en" -> 2
                                else -> 3
                            }
                        },
                        { it.locale.displayName },
                        { it.name }
                    )
                )
                sorted.mapIndexed { idx, v ->
                    VoiceOption(
                        name = v.name,
                        localeTag = v.locale.toLanguageTag(),
                        requiresNetwork = v.isNetworkConnectionRequired,
                        displayLabel = buildFriendlyVoiceLabel(v.locale, v.name, v.isNetworkConnectionRequired, idx)
                    )
                }
            }.getOrDefault(emptyList())

            _state.update {
                it.copy(
                    isTtsReady = true,
                    availableVoices = voices
                )
            }
            applyVoiceSelection(_state.value.selectedVoiceName)
        } else {
            Log.e(TAG, "TextToSpeech init failed with status=$status")
            _state.update {
                it.copy(
                    isTtsReady = false,
                    statusDetail = "Device speech output unavailable."
                )
            }
        }
    }

    fun updatePreferences(
        speechRate: Float,
        preferredVoiceName: String,
        spokenLocaleTag: String,
        continuousConversation: Boolean
    ) {
        currentListeningLocale = spokenLocaleTag.ifBlank { "en-US" }
        continuousAutoListen = continuousConversation
        tts?.setSpeechRate(speechRate)
        if (preferredVoiceName != _state.value.selectedVoiceName) {
            applyVoiceSelection(preferredVoiceName)
        }
        _state.update {
            it.copy(
                speechRate = speechRate,
                selectedVoiceName = preferredVoiceName
            )
        }
    }

    private fun applyVoiceSelection(voiceName: String) {
        if (voiceName.isBlank()) return
        val match = tts?.voices?.firstOrNull { it.name == voiceName }
        if (match != null) {
            tts?.voice = match
        }
    }

    fun startSession(welcomeText: String? = null) {
        _state.update { it.copy(isSessionActive = true) }
        if (!welcomeText.isNullOrBlank()) {
            speak(welcomeText, autoListenAfter = true)
        } else {
            startListening(currentListeningLocale)
        }
    }

    fun endSession(farewellText: String? = "ClearPath session ended.") {
        mainHandler.post {
            pendingShowcaseQueue.clear()
            onActiveUtteranceDone = null
            resumeListeningAfterUtterance = false
            publishAudioLevel(0f)
            stopListeningInternal()
            _state.update {
                it.copy(
                    isSessionActive = false,
                    partialTranscript = "",
                    phase = VoicePhase.IDLE,
                    statusDetail = "Session ended. Tap Listen to start again."
                )
            }
            if (!farewellText.isNullOrBlank()) {
                speak(farewellText, autoListenAfter = false)
            } else {
                stopSpeaking()
            }
        }
    }

    fun togglePrimaryVoiceAction() {
        when (_state.value.phase) {
            VoicePhase.SPEAKING -> interruptSpeakingAndListen()
            VoicePhase.LISTENING -> stopListening()
            VoicePhase.PROCESSING -> {
                // Allow user to cancel waiting and re-listen
                startListening(currentListeningLocale)
            }
            VoicePhase.IDLE, VoicePhase.ERROR -> {
                _state.update { it.copy(isSessionActive = true) }
                startListening(currentListeningLocale)
            }
        }
    }

    fun interruptSpeakingAndListen() {
        mainHandler.post {
            pendingShowcaseQueue.clear()
            onActiveUtteranceDone = null
            resumeListeningAfterUtterance = false
            publishAudioLevel(0f)
            tts?.stop()
            abandonAudioFocus()
            _state.update { it.copy(isSessionActive = true) }
            startListening(currentListeningLocale)
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            pendingShowcaseQueue.clear()
            onActiveUtteranceDone = null
            resumeListeningAfterUtterance = false
            publishAudioLevel(0f)
            tts?.stop()
            abandonAudioFocus()
            _state.update {
                it.copy(
                    phase = VoicePhase.IDLE,
                    statusDetail = "Stopped speaking."
                )
            }
        }
    }

    fun markProcessing(detail: String = "Thinking...") {
        mainHandler.post {
            resumeListeningAfterUtterance = false
            stopListeningInternal()
            haptics.onProcessingTick()
            _state.update {
                it.copy(
                    phase = VoicePhase.PROCESSING,
                    partialTranscript = "",
                    statusDetail = detail
                )
            }
        }
    }

    fun speak(
        text: String,
        languageTag: String? = null,
        autoListenAfter: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        mainHandler.post {
            pendingShowcaseQueue.clear()
            speakInternal(
                cleanText = cleanText,
                languageTag = languageTag,
                explicitVoiceName = null,
                autoListenAfter = autoListenAfter,
                onFinished = onFinished
            )
        }
    }

    fun speakWithSpecificVoice(
        voiceName: String?,
        text: String,
        autoListenAfter: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        mainHandler.post {
            pendingShowcaseQueue.clear()
            speakInternal(
                cleanText = cleanText,
                languageTag = null,
                explicitVoiceName = voiceName,
                autoListenAfter = autoListenAfter,
                onFinished = onFinished
            )
        }
    }

    fun speakVoiceShowcase(
        introText: String,
        samples: List<Pair<String, String>>,
        outroText: String,
        autoListenAfter: Boolean = true,
        onFinished: (() -> Unit)? = null
    ) {
        mainHandler.post {
            pendingShowcaseQueue.clear()
            pendingShowcaseFinishAutoListen = autoListenAfter
            onActiveUtteranceDone = onFinished

            samples.forEach { (voiceName, line) ->
                pendingShowcaseQueue.addLast(ShowcaseUtterance(voiceName, line))
            }
            pendingShowcaseQueue.addLast(ShowcaseUtterance(_state.value.selectedVoiceName.ifBlank { null }, outroText))

            speakInternal(
                cleanText = introText,
                languageTag = null,
                explicitVoiceName = null,
                autoListenAfter = false,
                onFinished = onFinished
            )
        }
    }

    private fun playNextShowcaseItem() {
        val next = pendingShowcaseQueue.removeFirstOrNull() ?: return
        val isLast = pendingShowcaseQueue.isEmpty()
        speakInternal(
            cleanText = next.text,
            languageTag = null,
            explicitVoiceName = next.voiceName,
            autoListenAfter = isLast && pendingShowcaseFinishAutoListen,
            onFinished = if (isLast) onActiveUtteranceDone else null
        )
    }

    private fun speakInternal(
        cleanText: String,
        languageTag: String?,
        explicitVoiceName: String?,
        autoListenAfter: Boolean,
        onFinished: (() -> Unit)?
    ) {
        if (_state.value.phase == VoicePhase.LISTENING) {
            stopListeningInternal()
        }

        _state.update {
            it.copy(
                lastSpokenText = cleanText,
                phase = VoicePhase.SPEAKING,
                statusDetail = "Speaking response.",
                isSessionActive = it.isSessionActive || autoListenAfter
            )
        }

        val engine = tts
        if (engine == null || !_state.value.isTtsReady) {
            publishAudioLevel(0f)
            _state.update {
                it.copy(
                    phase = VoicePhase.IDLE,
                    statusDetail = "Speech engine not ready; text shown on screen."
                )
            }
            onFinished?.invoke()
            return
        }

        if (!explicitVoiceName.isNullOrBlank()) {
            val match = engine.voices?.firstOrNull { it.name == explicitVoiceName }
            if (match != null) {
                engine.voice = match
            }
        } else if (!languageTag.isNullOrBlank()) {
            val targetLocale = Locale.forLanguageTag(languageTag)
            val availability = engine.isLanguageAvailable(targetLocale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = targetLocale
            } else {
                engine.language = Locale.US
            }
        } else if (_state.value.selectedVoiceName.isBlank()) {
            engine.language = Locale.US
        } else {
            applyVoiceSelection(_state.value.selectedVoiceName)
        }

        requestAudioFocus()
        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        onActiveUtteranceDone = onFinished
        resumeListeningAfterUtterance = autoListenAfter && _state.value.isSessionActive
        engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun repeatLastResponse() {
        val last = _state.value.lastSpokenText
        if (last.isBlank()) {
            speak("Nothing has been spoken yet.", autoListenAfter = _state.value.isSessionActive)
        } else {
            speak(last, autoListenAfter = _state.value.isSessionActive)
        }
    }

    fun startListening(localeTag: String = currentListeningLocale) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                _state.update {
                    it.copy(
                        isRecognitionAvailable = false,
                        phase = VoicePhase.ERROR,
                        statusDetail = "Speech recognition service is unavailable on this device."
                    )
                }
                haptics.onErrorAlert()
                _events.tryEmit(
                    VoiceEvent.RecognitionIssue(
                        "On-device or system speech recognition is unavailable.",
                        recoverable = false
                    )
                )
                return@post
            }

            pendingShowcaseQueue.clear()
            onActiveUtteranceDone = null
            resumeListeningAfterUtterance = false
            publishAudioLevel(0f)
            tts?.stop()
            abandonAudioFocus()
            stopListeningInternal()

            currentListeningLocale = localeTag.ifBlank { "en-US" }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    haptics.onListeningStart()
                    publishAudioLevel(0.15f)
                    _state.update {
                        it.copy(
                            phase = VoicePhase.LISTENING,
                            partialTranscript = "",
                            statusDetail = "Listening... Speak clearly."
                        )
                    }
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.08f, 1f)
                    publishAudioLevel(normalized)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    haptics.onListeningStop()
                    publishAudioLevel(0f)
                    _state.update {
                        it.copy(
                            phase = VoicePhase.PROCESSING,
                            statusDetail = "Processing what you said..."
                        )
                    }
                }

                override fun onError(error: Int) {
                    val isSilenceOrNoMatch = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    val message = mapSpeechError(error)
                    Log.w(TAG, "SpeechRecognizer error=$error ($message)")
                    publishAudioLevel(0f)
                    stopListeningInternal()
                    if (isSilenceOrNoMatch) {
                        _state.update {
                            it.copy(
                                phase = VoicePhase.IDLE,
                                partialTranscript = "",
                                statusDetail = "Did not catch speech. Tap Listen when ready."
                            )
                        }
                        _events.tryEmit(VoiceEvent.RecognitionIssue(message, recoverable = true))
                    } else {
                        haptics.onErrorAlert()
                        _state.update {
                            it.copy(
                                phase = VoicePhase.ERROR,
                                partialTranscript = "",
                                statusDetail = message
                            )
                        }
                        _events.tryEmit(VoiceEvent.RecognitionIssue(message, recoverable = false))
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()?.trim().orEmpty()
                    publishAudioLevel(0f)
                    stopListeningInternal()
                    if (transcript.isNotEmpty()) {
                        _state.update {
                            it.copy(
                                lastUserTranscript = transcript,
                                partialTranscript = "",
                                phase = VoicePhase.PROCESSING,
                                statusDetail = "Heard: $transcript"
                            )
                        }
                        _events.tryEmit(VoiceEvent.FinalTranscript(transcript))
                    } else {
                        _state.update {
                            it.copy(
                                phase = VoicePhase.IDLE,
                                partialTranscript = "",
                                statusDetail = "Did not catch anything. Tap Listen to try again."
                            )
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        _state.update { it.copy(partialTranscript = partial) }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentListeningLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Avoid cutting users off prematurely during pauses
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1600L
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            }

            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SpeechRecognizer", e)
                stopListeningInternal()
                haptics.onErrorAlert()
                _state.update {
                    it.copy(
                        phase = VoicePhase.ERROR,
                        statusDetail = "Could not open microphone recognizer."
                    )
                }
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            stopListeningInternal()
            _state.update {
                it.copy(
                    phase = VoicePhase.IDLE,
                    partialTranscript = "",
                    statusDetail = "Stopped listening."
                )
            }
        }
    }

    fun probeLanguageTtsSupport(localeTag: String): TtsSupportStatus {
        val engine = tts ?: return TtsSupportStatus.UNSUPPORTED
        if (!_state.value.isTtsReady) return TtsSupportStatus.UNSUPPORTED
        return when (engine.isLanguageAvailable(Locale.forLanguageTag(localeTag))) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> TtsSupportStatus.AVAILABLE
            TextToSpeech.LANG_MISSING_DATA -> TtsSupportStatus.NETWORK_REQUIRED_OR_MISSING_PACK
            else -> TtsSupportStatus.UNSUPPORTED
        }
    }

    private fun stopListeningInternal() {
        speechRecognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        speechRecognizer = null
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun release() {
        mainHandler.post {
            stopListeningInternal()
            tts?.stop()
            tts?.shutdown()
            tts = null
            abandonAudioFocus()
        }
    }

    companion object {
        fun buildFriendlyVoiceLabel(
            locale: Locale,
            rawName: String,
            requiresNetwork: Boolean,
            index: Int
        ): String {
            val langCountry = buildString {
                append(locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { locale.language })
                val country = locale.getDisplayCountry(Locale.ENGLISH)
                if (country.isNotBlank()) {
                    append(" (")
                    append(country)
                    append(")")
                }
            }
            val tagSuffix = rawName.substringAfterLast("-x-", "").substringBefore("-").uppercase()
            val mode = if (requiresNetwork) "Online" else "Offline"
            return if (tagSuffix.isNotBlank() && tagSuffix.length <= 6) {
                "$langCountry · Style $tagSuffix · $mode"
            } else {
                "$langCountry · Option ${index + 1} · $mode"
            }
        }

        fun mapSpeechError(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio capture error."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy; please try again."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out waiting for speech."
            else -> "Speech recognition could not complete."
        }
    }
}
