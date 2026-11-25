package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.haptics.AssistiveHaptics
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "GeminiLiveManager"
private const val LIVE_MODEL_NAME = "gemini-2.5-flash-native-audio-preview-12-2025"
private const val INPUT_SAMPLE_RATE = 16000
private const val OUTPUT_SAMPLE_RATE = 24000
const val DECIBEL_BAND_COUNT = 28

data class LiveVoiceOption(
    val name: String,
    val style: String,
    val gender: String
) {
    val spokenLabel: String get() = "$name ($style, $gender)"
}

object LiveVoiceCatalog {
    val voices: List<LiveVoiceOption> = listOf(
        LiveVoiceOption("Aoede", "Breezy & clear", "Female"),
        LiveVoiceOption("Sulafat", "Warm & reassuring", "Female"),
        LiveVoiceOption("Puck", "Upbeat & friendly", "Male"),
        LiveVoiceOption("Charon", "Informative & steady", "Male"),
        LiveVoiceOption("Kore", "Firm & crisp", "Female"),
        LiveVoiceOption("Fenrir", "Expressive & lively", "Male"),
        LiveVoiceOption("Zephyr", "Bright & smooth", "Female"),
        LiveVoiceOption("Orus", "Calm & grounded", "Male"),
        LiveVoiceOption("Leda", "Youthful & light", "Female"),
        LiveVoiceOption("Umbriel", "Easy-going & relaxed", "Male")
    )

    fun findByName(query: String): LiveVoiceOption? {
        val q = query.trim().lowercase()
        return voices.firstOrNull {
            it.name.lowercase() == q ||
                it.name.lowercase().contains(q) ||
                it.style.lowercase().contains(q)
        }
    }
}

data class GeminiLiveState(
    val isConnecting: Boolean = false,
    val isActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val isUserSpeaking: Boolean = false,
    val isCameraStreaming: Boolean = false,
    val framesSentCount: Int = 0,
    val isInterpreterMode: Boolean = false,
    val interpreterTargetLanguage: String = "English",
    val currentVoiceName: String = "Aoede",
    val inputTranscript: String = "",
    val outputTranscript: String = "",
    val errorMessage: String? = null
)

@OptIn(PublicPreviewAPI::class)
class GeminiLiveManager(
    context: Context,
    private val haptics: AssistiveHaptics
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(GeminiLiveState())
    val state: StateFlow<GeminiLiveState> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _decibelBands = MutableStateFlow(List(DECIBEL_BAND_COUNT) { 0.06f })
    val decibelBands: StateFlow<List<Float>> = _decibelBands.asStateFlow()

    private var sessionJob: Job? = null
    private var activeSession: LiveSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var resumptionHandle: String? = null
    private var activeTools: List<Tool> = emptyList()
    private var activeSystemInstruction: Content? = null
    private var activeToolExecutor: (suspend (FunctionCallPart) -> FunctionResponsePart)? = null
    private var activeOnConnectionFailed: ((String) -> Unit)? = null

    private val videoFrameChannel = Channel<Pair<Bitmap, Boolean>>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = { (bmp, recycleAfter) ->
            if (recycleAfter && !bmp.isRecycled) {
                runCatching { bmp.recycle() }
            }
        }
    )

    fun startSession(
        voiceName: String,
        systemInstruction: Content,
        tools: List<Tool>,
        initialPrompt: String? = null,
        reuseSessionHandle: Boolean = false,
        onToolCall: suspend (FunctionCallPart) -> FunctionResponsePart,
        onConnectionFailed: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onConnectionFailed("Microphone permission is required for live conversation.")
            return
        }

        val resolvedVoice = LiveVoiceCatalog.findByName(voiceName)?.name ?: "Aoede"
        activeTools = tools
        activeSystemInstruction = systemInstruction
        activeToolExecutor = onToolCall
        activeOnConnectionFailed = onConnectionFailed
        if (!reuseSessionHandle) {
            resumptionHandle = null
        }

        stopSessionInternal(resetVoiceName = false)

        _state.update {
            it.copy(
                isConnecting = true,
                isActive = false,
                isAiSpeaking = false,
                isUserSpeaking = false,
                currentVoiceName = resolvedVoice,
                errorMessage = null
            )
        }

        sessionJob = scope.launch {
            runDuplexSession(
                voiceName = resolvedVoice,
                systemInstruction = systemInstruction,
                tools = tools,
                initialPrompt = initialPrompt,
                handle = if (reuseSessionHandle) resumptionHandle else null,
                onToolCall = onToolCall,
                onConnectionFailed = onConnectionFailed
            )
        }
    }

    fun switchVoice(newVoiceName: String, announcementPrompt: String) {
        val instruction = activeSystemInstruction ?: return
        val executor = activeToolExecutor ?: return
        val onFailed = activeOnConnectionFailed ?: {}
        val resolved = LiveVoiceCatalog.findByName(newVoiceName)?.name ?: newVoiceName
        startSession(
            voiceName = resolved,
            systemInstruction = instruction,
            tools = activeTools,
            initialPrompt = announcementPrompt,
            reuseSessionHandle = false,
            onToolCall = executor,
            onConnectionFailed = onFailed
        )
    }

    fun sendTextTurn(text: String) {
        val session = activeSession ?: return
        scope.launch {
            runCatching { session.send(text, turnComplete = true) }
        }
    }

    fun setInterpreterMode(enabled: Boolean, targetLanguage: String = "English") {
        _state.update {
            it.copy(
                isInterpreterMode = enabled,
                interpreterTargetLanguage = targetLanguage.ifBlank { "English" }
            )
        }
    }

    fun setCameraStreamingActive(active: Boolean) {
        _state.update {
            it.copy(
                isCameraStreaming = active,
                framesSentCount = if (active) it.framesSentCount else 0
            )
        }
        if (!active) {
            while (videoFrameChannel.tryReceive().isSuccess) { /* drained and recycled via onUndeliveredElement */ }
        }
    }

    fun sendCameraFrame(bitmap: Bitmap, recycleSource: Boolean = false) {
        if (activeSession == null || !_state.value.isActive) {
            if (recycleSource && !bitmap.isRecycled) {
                runCatching { bitmap.recycle() }
            }
            return
        }
        val sent = videoFrameChannel.trySend(bitmap to recycleSource)
        if (!sent.isSuccess && recycleSource && !bitmap.isRecycled) {
            runCatching { bitmap.recycle() }
        }
    }

    fun stopSession() {
        stopSessionInternal(resetVoiceName = false)
    }

    private fun stopSessionInternal(resetVoiceName: Boolean) {
        val oldJob = sessionJob
        sessionJob = null
        val oldSession = activeSession
        activeSession = null
        oldJob?.cancel()
        while (videoFrameChannel.tryReceive().isSuccess) { /* drain */ }
        if (oldSession != null) {
            scope.launch { runCatching { oldSession.close() } }
        }
        _audioLevel.value = 0f
        _decibelBands.value = List(DECIBEL_BAND_COUNT) { 0.06f }
        _state.update {
            it.copy(
                isConnecting = false,
                isActive = false,
                isAiSpeaking = false,
                isUserSpeaking = false,
                isCameraStreaming = false,
                framesSentCount = 0,
                isInterpreterMode = false,
                currentVoiceName = if (resetVoiceName) "Aoede" else it.currentVoiceName
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runDuplexSession(
        voiceName: String,
        systemInstruction: Content,
        tools: List<Tool>,
        initialPrompt: String?,
        handle: String?,
        onToolCall: suspend (FunctionCallPart) -> FunctionResponsePart,
        onConnectionFailed: (String) -> Unit
    ) {
        var audioRecord: AudioRecord? = null
        var audioTrack: AudioTrack? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var session: LiveSession? = null
        val playbackQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)

        try {
            val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = LIVE_MODEL_NAME,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(voice = Voice(voiceName))
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                    realtimeInputConfig = RealtimeInputConfig.builder()
                        .setActivityHandling(RealtimeInputConfig.ActivityHandling.INTERRUPT)
                        .build()
                },
                tools = tools,
                systemInstruction = systemInstruction
            )

            session = if (handle != null) {
                liveModel.connect(SessionResumptionConfig(handle = handle))
            } else {
                liveModel.connect(SessionResumptionConfig())
            }
            activeSession = session

            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            requestAudioFocus()

            val minRecBuf = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf * 2
            )

            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }

            val minPlayBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()
            audioRecord.startRecording()

            _state.update {
                it.copy(
                    isConnecting = false,
                    isActive = true,
                    currentVoiceName = voiceName,
                    errorMessage = null
                )
            }
            haptics.onListeningStart()

            if (!initialPrompt.isNullOrBlank()) {
                session.send(initialPrompt, turnComplete = true)
            }

            // 1. Dedicated speaker playback + real PCM decibel spectrum updater
            val playJob = scope.launch(Dispatchers.IO) {
                for (pcmBytes in playbackQueue) {
                    if (!isActive) break
                    updateSpectrumFromPcm(pcmBytes, isAiAudio = true)
                    audioTrack.write(pcmBytes, 0, pcmBytes.size)
                }
            }

            // 2. Continuous duplex mic streamer (never paused during AI speech -> real interruption!)
            val recordJob = scope.launch(Dispatchers.IO) {
                val readBuffer = ByteArray(1280) // ~40ms at 16kHz 16-bit mono
                while (isActive) {
                    val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        val chunk = readBuffer.copyOf(read)
                        val micEnergy = computeRmsNormalized(chunk, read)
                        val speakingNow = micEnergy > 0.14f
                        if (speakingNow != _state.value.isUserSpeaking) {
                            _state.update { it.copy(isUserSpeaking = speakingNow) }
                        }
                        if (!_state.value.isAiSpeaking) {
                            if (speakingNow) {
                                updateSpectrumFromPcm(chunk, isAiAudio = false)
                            } else {
                                _audioLevel.value = (_audioLevel.value * 0.72f).coerceAtLeast(0.02f)
                                _decibelBands.value = _decibelBands.value.map { (it * 0.75f).coerceAtLeast(0.05f) }
                            }
                        }
                        session.sendAudioRealtime(InlineData(chunk, "audio/pcm;rate=16000"))
                    }
                }
            }

            // 2b. Continuous conflated video frame sender (~1fps JPEG frames when camera sharing is on)
            val videoJob = scope.launch(Dispatchers.IO) {
                for ((bitmap, recycleSource) in videoFrameChannel) {
                    if (!isActive) {
                        if (recycleSource && !bitmap.isRecycled) runCatching { bitmap.recycle() }
                        break
                    }
                    try {
                        if (!bitmap.isRecycled) {
                            val scaled = scaleBitmapForLive(bitmap, maxDim = 768)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
                            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                            session.sendVideoRealtime(InlineData(out.toByteArray(), "image/jpeg"))
                            _state.update {
                                it.copy(framesSentCount = it.framesSentCount + 1)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed sending live video frame", e)
                    } finally {
                        if (recycleSource && !bitmap.isRecycled) {
                            runCatching { bitmap.recycle() }
                        }
                    }
                }
            }

            // 3. Server receive loop (audio, interruption signals, transcriptions, tool calls)
            session.receive().collect { message ->
                when (message) {
                    is LiveServerContent -> {
                        if (message.interrupted) {
                            // User spoke over the AI -> immediately flush speaker queue!
                            while (playbackQueue.tryReceive().isSuccess) { /* drain */ }
                            runCatching {
                                audioTrack.pause()
                                audioTrack.flush()
                                audioTrack.play()
                            }
                            _audioLevel.value = 0.08f
                            _state.update { it.copy(isAiSpeaking = false) }
                            haptics.onListeningStart()
                        }

                        val inText = message.inputTranscription?.text?.trim()
                        if (!inText.isNullOrBlank()) {
                            _state.update { it.copy(inputTranscript = inText) }
                        }

                        val outText = message.outputTranscription?.text?.trim()
                        if (!outText.isNullOrBlank()) {
                            _state.update { current ->
                                val combined = if (current.outputTranscript.isBlank() ||
                                    current.outputTranscript.endsWith(".") ||
                                    current.outputTranscript.endsWith("?")
                                ) {
                                    outText
                                } else {
                                    "${current.outputTranscript} $outText".trim()
                                }
                                current.copy(outputTranscript = combined)
                            }
                        }

                        val parts = message.content?.parts.orEmpty()
                        var hasAudioChunk = false
                        for (part in parts) {
                            if (part is InlineDataPart && part.mimeType.startsWith("audio/")) {
                                hasAudioChunk = true
                                playbackQueue.trySend(part.inlineData)
                            }
                        }
                        if (hasAudioChunk && !_state.value.isAiSpeaking) {
                            haptics.onDecibelPulse(0.65f)
                            _state.update { it.copy(isAiSpeaking = true) }
                        }

                        if (message.turnComplete || message.generationComplete) {
                            _state.update { it.copy(isAiSpeaking = false) }
                        }
                    }

                    is LiveServerToolCall -> {
                        val responses = message.functionCalls.map { call ->
                            runCatching { onToolCall(call) }.getOrElse { err ->
                                FunctionResponsePart(
                                    call.name,
                                    kotlinx.serialization.json.JsonObject(
                                        mapOf(
                                            "status" to kotlinx.serialization.json.JsonPrimitive("error"),
                                            "message" to kotlinx.serialization.json.JsonPrimitive(
                                                err.localizedMessage ?: "Tool execution failed."
                                            )
                                        )
                                    )
                                )
                            }
                        }
                        if (responses.isNotEmpty()) {
                            session.sendFunctionResponse(responses)
                        }
                    }

                    is LiveSessionResumptionUpdate -> {
                        if (message.resumable == true && !message.newHandle.isNullOrBlank()) {
                            resumptionHandle = message.newHandle
                        }
                    }

                    is LiveServerGoAway -> {
                        Log.w(TAG, "Gemini Live GoAway received; timeLeft=${message.timeLeft}")
                    }

                    else -> Unit
                }
            }

            recordJob.cancel()
            videoJob.cancel()
            playbackQueue.close()
            playJob.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Live session error", e)
            val errMsg = e.localizedMessage ?: "Could not connect to Gemini Live."
            _state.update {
                it.copy(
                    isConnecting = false,
                    isActive = false,
                    isAiSpeaking = false,
                    isCameraStreaming = false,
                    framesSentCount = 0,
                    errorMessage = errMsg
                )
            }
            onConnectionFailed(errMsg)
        } finally {
            playbackQueue.close()
            while (videoFrameChannel.tryReceive().isSuccess) { /* drained */ }
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { audioTrack?.pause() }
            runCatching { audioTrack?.flush() }
            runCatching { audioTrack?.release() }
            runCatching { session?.close() }
            runCatching { audioManager.mode = previousAudioMode }
            abandonAudioFocus()
            if (activeSession === session) {
                activeSession = null
            }
            _audioLevel.value = 0f
            _decibelBands.value = List(DECIBEL_BAND_COUNT) { 0.06f }
            _state.update {
                it.copy(
                    isConnecting = false,
                    isActive = false,
                    isAiSpeaking = false,
                    isUserSpeaking = false,
                    isCameraStreaming = false,
                    framesSentCount = 0,
                    isInterpreterMode = false
                )
            }
        }
    }

    private fun updateSpectrumFromPcm(pcmBytes: ByteArray, isAiAudio: Boolean) {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount <= 0) return

        val bands = MutableList(DECIBEL_BAND_COUNT) { 0.06f }
        val step = (sampleCount / DECIBEL_BAND_COUNT).coerceAtLeast(1)
        var overallSumSq = 0.0

        for (b in 0 until DECIBEL_BAND_COUNT) {
            val start = b * step
            val end = min(sampleCount, start + step)
            var peak = 0
            var diffSum = 0.0
            var prevSample = 0
            for (s in start until end) {
                val lo = pcmBytes[s * 2].toInt() and 0xFF
                val hi = pcmBytes[s * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val mag = abs(sample)
                if (mag > peak) peak = mag
                diffSum += abs(sample - prevSample)
                prevSample = sample
                val norm = sample / 32768.0
                overallSumSq += norm * norm
            }
            val count = (end - start).coerceAtLeast(1)
            val amplitude = ((peak / 32768f) * 2.6f).coerceIn(0.05f, 1f)
            val texture = (((diffSum / count) / 14000.0).toFloat()).coerceIn(0.05f, 1f)
            val shaped = if (isAiAudio) {
                (amplitude * 0.72f + texture * 0.28f).coerceIn(0.08f, 1f)
            } else {
                (amplitude * 0.85f + texture * 0.15f).coerceIn(0.06f, 1f)
            }
            bands[b] = shaped
        }

        val rms = sqrt(overallSumSq / sampleCount).toFloat()
        val level = (rms * 4.2f).coerceIn(0.06f, 1f)
        _audioLevel.value = level
        _decibelBands.value = bands
    }

    private fun computeRmsNormalized(pcmBytes: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until samples) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo) / 32768.0
            sumSq += sample * sample
        }
        return (sqrt(sumSq / samples).toFloat() * 4.5f).coerceIn(0f, 1f)
    }

    private fun scaleBitmapForLive(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDim && h <= maxDim) return source
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
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
                AudioManager.STREAM_VOICE_CALL,
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
        stopSession()
    }
}
