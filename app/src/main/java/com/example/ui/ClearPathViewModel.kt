package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.ai.ClearPathAiRepository
import com.example.ai.ClearPathIntent
import com.example.ai.InferenceOrigin
import com.example.ai.OfflineSettingEdit
import com.example.ai.VoiceIntentParser
import com.example.camera.CameraController
import com.example.haptics.AssistiveHaptics
import com.example.navigation.CompassSensor
import com.example.navigation.LocationTracker
import com.example.navigation.NavigationEngine
import com.example.navigation.NavigationUiState
import com.example.navigation.OsmRoutingClient
import com.example.navigation.PlaceCandidate
import com.example.navigation.RoutingProviderMode
import com.example.onboarding.ManualSetupStep
import com.example.onboarding.OnboardingUiState
import com.example.onboarding.OnboardingVoiceParser
import com.example.onboarding.OnboardingVoiceReply
import com.example.onboarding.SetupMode
import com.example.onboarding.VoiceSetupStep
import com.example.permissions.ClearPathPermission
import com.example.permissions.PermissionChecker
import com.example.permissions.PermissionSnapshot
import com.example.settings.ClearPathPreferencesRepository
import com.example.settings.ClearPathSettings
import com.example.translation.LanguageCatalog
import com.example.translation.LanguageCapabilityReport
import com.example.translation.SupportedLanguage
import com.example.translation.TranslationManager
import com.example.translation.TranslationOutput
import com.example.voice.DECIBEL_BAND_COUNT
import com.example.voice.GeminiLiveManager
import com.example.voice.GeminiLiveState
import com.example.voice.LiveVoiceCatalog
import com.example.voice.LiveVoiceOption
import com.example.voice.VoiceCoordinator
import com.example.voice.VoiceEvent
import com.example.voice.VoiceOption
import com.example.voice.VoicePhase
import com.example.voice.VoiceUiState
import com.example.voice.WakePhraseBridge
import com.example.voice.WakePhraseDetector
import com.example.voice.WakePhraseService
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class ClearPathTab(val route: String, val label: String) {
    ASSIST("assist", "Assist"),
    NAVIGATE("navigate", "Navigate"),
    SETTINGS("settings", "Settings")
}

data class TranslationUiState(
    val sourceLanguage: SupportedLanguage = LanguageCatalog.autoDetect,
    val targetLanguage: SupportedLanguage = LanguageCatalog.languages.first { it.code == "en" },
    val inputText: String = "",
    val lastOutput: TranslationOutput? = null,
    val isTranslating: Boolean = false,
    val statusNote: String = "Speak, type, or point the camera at text to translate.",
    val languageReports: List<LanguageCapabilityReport> = emptyList(),
    val errorMessage: String? = null
)

data class AssistUiState(
    val latestAnswer: String = "Tap Listen below and ask what is in front of you, ask to read text, translate, or navigate.",
    val lastOrigin: InferenceOrigin? = null,
    val isProcessingAi: Boolean = false,
    val hasSceneContext: Boolean = false,
    val preferOnDeviceSingleTurn: Boolean = false,
    val errorMessage: String? = null,
    val pendingPermissionPrompt: ClearPathPermission? = null,
    val isWaitingForPermissionResult: Boolean = false
)

class ClearPathViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val preferences = ClearPathPreferencesRepository(appContext)
    val haptics = AssistiveHaptics(appContext)
    val voiceCoordinator = VoiceCoordinator(appContext, haptics)
    val geminiLive = GeminiLiveManager(appContext, haptics)
    val cameraController = CameraController(appContext)
    private val aiRepository = ClearPathAiRepository()
    private val translationManager = TranslationManager(aiRepository)
    private val locationTracker = LocationTracker(appContext)
    private val compassSensor = CompassSensor(appContext)
    private val routingClient = OsmRoutingClient()

    private val _settings = MutableStateFlow(ClearPathSettings())
    val settings: StateFlow<ClearPathSettings> = _settings.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionChecker.snapshot(appContext))
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _assistState = MutableStateFlow(AssistUiState())
    val assistState: StateFlow<AssistUiState> = _assistState.asStateFlow()

    private val _translationState = MutableStateFlow(TranslationUiState())
    val translationState: StateFlow<TranslationUiState> = _translationState.asStateFlow()

    private val _navigationState = MutableStateFlow(NavigationUiState())
    val navigationState: StateFlow<NavigationUiState> = _navigationState.asStateFlow()

    private val _activeTab = MutableStateFlow(ClearPathTab.ASSIST)
    val activeTab: StateFlow<ClearPathTab> = _activeTab.asStateFlow()

    private val _onboardingState = MutableStateFlow(OnboardingUiState())
    val onboardingState: StateFlow<OnboardingUiState> = _onboardingState.asStateFlow()

    private val _mapsLaunchEvents = MutableSharedFlow<Pair<com.example.navigation.GeoPoint?, String>>(extraBufferCapacity = 4)
    val mapsLaunchEvents: SharedFlow<Pair<com.example.navigation.GeoPoint?, String>> = _mapsLaunchEvents.asSharedFlow()

    val voiceState: StateFlow<VoiceUiState> = voiceCoordinator.state
    val liveState: StateFlow<GeminiLiveState> = geminiLive.state
    val audioLevel: StateFlow<Float> = combine(
        geminiLive.state,
        geminiLive.audioLevel,
        voiceCoordinator.audioLevel
    ) { live, liveLvl, ttsLvl ->
        if (live.isActive || live.isConnecting) liveLvl else ttsLvl
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val decibelBands: StateFlow<List<Float>> = combine(
        geminiLive.state,
        geminiLive.decibelBands,
        voiceCoordinator.decibelBands
    ) { live, liveBands, ttsBands ->
        if (live.isActive || live.isConnecting) liveBands else ttsBands
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        List(DECIBEL_BAND_COUNT) { 0.06f }
    )

    val isCameraReady: StateFlow<Boolean> = cameraController.isCameraReady
    val useBackCamera: StateFlow<Boolean> = cameraController.useBackCamera

    private val _isLiveCameraShareActive = MutableStateFlow(false)
    val isLiveCameraShareActive: StateFlow<Boolean> = _isLiveCameraShareActive.asStateFlow()

    private val _isFullScreenCameraView = MutableStateFlow(false)
    val isFullScreenCameraView: StateFlow<Boolean> = _isFullScreenCameraView.asStateFlow()

    private var activeAiJob: Job? = null
    private var locationUpdatesJob: Job? = null
    private var compassJob: Job? = null
    private var pendingVoiceAfterMicPermission = false
    private var pendingLiveCameraAfterPermission = false
    private var pendingLiveCameraFullScreen = false
    private var pendingWakePhraseAfterMicPermission = false
    private var pendingExternalActivation = false
    private var pendingSceneQuestionAfterCamera: String? = null
    private var pendingReadTextTargetAfterCamera: SupportedLanguage? = null
    private var pendingLocationAction: (() -> Unit)? = null
    private val pendingOnboardingPermissionQueue = ArrayDeque<ClearPathPermission>()
    private var waitingPermissionRequest: ClearPathPermission? = null
    private var offRouteConsecutiveCount = 0
    private var lastAnnouncedStepIndex = -1
    private var isRerouting = false
    private var welcomeTriggered = false
    private var lastBoundLifecycleOwner: LifecycleOwner? = null
    private var lastBoundPreviewView: PreviewView? = null

    init {
        cameraController.setLiveFrameConsumer { bitmap ->
            geminiLive.sendCameraFrame(bitmap, recycleSource = true)
        }
        observePreferences()
        observeVoiceEvents()
        observeGeminiLiveState()
        observeWakePhraseEvents()
        observeSceneChanges()
        observeTtsReadinessForLanguages()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferences.settings.collectLatest { prefs ->
                _settings.value = prefs
                haptics.enabled = prefs.hapticsEnabled
                cameraController.setSceneWatchEnabled(prefs.sceneWatchEnabled)
                // TTS voice lookup is a binder call and can block for seconds.
                // Keep it off the main thread so foreground services can start in time.
                withContext(Dispatchers.Default) {
                    voiceCoordinator.updatePreferences(
                        speechRate = prefs.speechRate,
                        preferredVoiceName = prefs.preferredVoiceName,
                        spokenLocaleTag = prefs.spokenLanguageTag,
                        continuousConversation = prefs.continuousConversation
                    )
                }
                val src = LanguageCatalog.findByCodeOrName(prefs.translationSourceCode)
                    ?: LanguageCatalog.autoDetect
                val tgt = LanguageCatalog.findByCodeOrName(prefs.translationTargetCode)
                    ?: LanguageCatalog.languages.first { it.code == "en" }
                val parsedMode = runCatching {
                    RoutingProviderMode.valueOf(prefs.routingProviderMode)
                }.getOrDefault(RoutingProviderMode.OPEN_ROUTE_SERVICE)
                _assistState.update { it.copy(preferOnDeviceSingleTurn = prefs.preferOnDeviceAi) }
                _navigationState.update { it.copy(providerMode = parsedMode) }
                _translationState.update { it.copy(sourceLanguage = src, targetLanguage = tgt) }
                _onboardingState.update {
                    it.copy(
                        isPreferencesLoaded = true,
                        hasCompletedOnboarding = prefs.hasCompletedOnboarding,
                        voiceCandidates = voiceCoordinator.state.value.availableVoices.take(12)
                    )
                }

                if (!welcomeTriggered && voiceCoordinator.state.value.isTtsReady) {
                    welcomeTriggered = true
                    handleStartupActivationOrWelcome(prefs)
                }

                if (prefs.hasCompletedOnboarding &&
                    prefs.wakePhraseModeEnabled &&
                    PermissionChecker.snapshot(appContext).hasMicrophone &&
                    !geminiLive.state.value.isActive &&
                    !geminiLive.state.value.isConnecting &&
                    !WakePhraseBridge.isServiceRunning.value
                ) {
                    runCatching { WakePhraseService.start(appContext) }
                }
            }
        }
    }

    private fun observeGeminiLiveState() {
        viewModelScope.launch {
            geminiLive.state.collectLatest { live ->
                val vState = voiceCoordinator.state.value
                WakePhraseBridge.setVoiceSessionBusy(
                    live.isActive || live.isConnecting ||
                        (vState.phase != VoicePhase.IDLE && vState.phase != VoicePhase.ERROR)
                )
                if (live.isActive && _isLiveCameraShareActive.value && !live.isCameraStreaming) {
                    cameraController.setLiveStreamEnabled(true)
                    geminiLive.setCameraStreamingActive(true)
                } else if (!live.isActive && !live.isConnecting && _isLiveCameraShareActive.value) {
                    cameraController.setLiveStreamEnabled(false)
                    _isLiveCameraShareActive.value = false
                    _isFullScreenCameraView.value = false
                }
                val ob = _onboardingState.value
                if (ob.isPreferencesLoaded && !ob.hasCompletedOnboarding && ob.mode == SetupMode.VOICE_FIRST) {
                    if (live.isActive || live.isConnecting) {
                        _onboardingState.update { current ->
                            current.copy(
                                currentPromptText = live.outputTranscript.ifBlank { current.currentPromptText },
                                statusHint = when {
                                    live.isConnecting -> "Connecting real-time ClearPath voice..."
                                    live.inputTranscript.isNotBlank() -> "You: \"${live.inputTranscript}\""
                                    live.isUserSpeaking -> "Listening to you..."
                                    live.isAiSpeaking -> "Speaking · Speak anytime to interrupt"
                                    else -> "Speak naturally anytime"
                                }
                            )
                        }
                    }
                } else if (live.isActive && live.outputTranscript.isNotBlank()) {
                    _assistState.update {
                        it.copy(
                            latestAnswer = live.outputTranscript,
                            lastOrigin = InferenceOrigin.CLOUD
                        )
                    }
                }
            }
        }
    }

    private fun observeTtsReadinessForLanguages() {
        viewModelScope.launch {
            voiceCoordinator.state.collectLatest { vState ->
                val live = geminiLive.state.value
                WakePhraseBridge.setVoiceSessionBusy(
                    live.isActive || live.isConnecting ||
                        (vState.phase != VoicePhase.IDLE && vState.phase != VoicePhase.ERROR)
                )
                if (vState.isTtsReady) {
                    val reports = LanguageCatalog.languages.map { lang ->
                        LanguageCapabilityReport(
                            language = lang,
                            ttsStatus = voiceCoordinator.probeLanguageTtsSupport(lang.ttsLocaleTag)
                        )
                    }
                    _translationState.update { it.copy(languageReports = reports) }
                    _onboardingState.update {
                        it.copy(voiceCandidates = vState.availableVoices.take(12))
                    }

                    if (!welcomeTriggered && _onboardingState.value.isPreferencesLoaded) {
                        welcomeTriggered = true
                        handleStartupActivationOrWelcome(_settings.value)
                    }
                }
            }
        }
    }

    private fun handleStartupActivationOrWelcome(prefs: ClearPathSettings) {
        refreshPermissions()
        if (!prefs.hasCompletedOnboarding) {
            beginVoiceOnboarding()
            return
        }
        val hasMic = _permissions.value.hasMicrophone
        if ((pendingExternalActivation || prefs.autoListenOnOpen) && hasMic) {
            pendingExternalActivation = false
            startLiveAssistSession()
            return
        }
        pendingExternalActivation = false
        val welcome = if (!prefs.hasSpokenWelcome) {
            viewModelScope.launch { preferences.markWelcomeSpoken() }
            "Welcome to ClearPath, your voice-first assistive companion. Tap Listen below to start a live hands-free conversation anytime."
        } else {
            "ClearPath ready."
        }
        voiceCoordinator.speak(welcome, autoListenAfter = false)
    }

    private fun observeVoiceEvents() {
        viewModelScope.launch {
            voiceCoordinator.events.collectLatest { event ->
                when (event) {
                    is VoiceEvent.FinalTranscript -> {
                        val ob = _onboardingState.value
                        if (ob.isPreferencesLoaded && !ob.hasCompletedOnboarding && ob.mode == SetupMode.VOICE_FIRST) {
                            handleOnboardingVoiceTranscript(event.text)
                            return@collectLatest
                        }
                        val wakeMatch = WakePhraseDetector.match(event.text)
                        if (wakeMatch != null) {
                            if (wakeMatch.trailingCommand != null) {
                                handleVoiceTranscript(wakeMatch.trailingCommand)
                            } else {
                                voiceCoordinator.startSession("ClearPath active. Listening.")
                            }
                        } else {
                            handleVoiceTranscript(event.text)
                        }
                    }
                    is VoiceEvent.RecognitionIssue -> {
                        val ob = _onboardingState.value
                        if (ob.isPreferencesLoaded && !ob.hasCompletedOnboarding && ob.mode == SetupMode.VOICE_FIRST) {
                            _onboardingState.update {
                                it.copy(
                                    statusHint = if (event.recoverable) {
                                        "Offline fallback active — speak clearly or tap Manual Setup below."
                                    } else {
                                        event.message
                                    }
                                )
                            }
                        } else if (!event.recoverable) {
                            _assistState.update { it.copy(errorMessage = event.message) }
                        }
                    }
                }
            }
        }
    }

    private fun observeWakePhraseEvents() {
        viewModelScope.launch {
            WakePhraseBridge.activationEvents.collectLatest { trailingCommand ->
                haptics.onListeningStart()
                startLiveAssistSession(initialUserRequest = trailingCommand)
            }
        }
        viewModelScope.launch {
            WakePhraseBridge.stoppedByNotification.collectLatest {
                preferences.setWakePhraseModeEnabled(false)
            }
        }
    }

    fun onExternalAssistantTrigger() {
        refreshPermissions()
        if (!voiceCoordinator.state.value.isTtsReady) {
            pendingExternalActivation = true
            return
        }
        if (_permissions.value.hasMicrophone) {
            startLiveAssistSession()
        } else {
            pendingVoiceAfterMicPermission = true
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.MICROPHONE) }
            voiceCoordinator.speak(ClearPathPermission.MICROPHONE.rationale, autoListenAfter = false)
        }
    }

    private fun observeSceneChanges() {
        viewModelScope.launch {
            cameraController.sceneChanges.collectLatest {
                val vPhase = voiceCoordinator.state.value.phase
                val live = geminiLive.state.value
                if (_settings.value.sceneWatchEnabled &&
                    !live.isAiSpeaking &&
                    vPhase == VoicePhase.IDLE &&
                    !_assistState.value.isProcessingAi
                ) {
                    describeCurrentScene(
                        "Briefly note any major change or potential obstacle directly ahead.",
                        autoListen = false
                    )
                }
            }
        }
    }

    fun selectTab(tab: ClearPathTab) {
        _activeTab.value = tab
    }

    fun refreshPermissions() {
        _permissions.value = PermissionChecker.snapshot(appContext)
    }

    fun onPermissionResult(permission: ClearPathPermission, granted: Boolean) {
        waitingPermissionRequest = null
        refreshPermissions()
        _assistState.update {
            it.copy(
                pendingPermissionPrompt = null,
                isWaitingForPermissionResult = false
            )
        }

        if (_onboardingState.value.awaitingPermissionBatch) {
            if (!granted) {
                val msg = "${permission.title} was denied. Remaining setup permissions will still be offered."
                _assistState.update { it.copy(errorMessage = msg) }
                voiceCoordinator.speak(msg, autoListenAfter = false)
                pendingVoiceAfterMicPermission = false
                pendingLiveCameraAfterPermission = false
                pendingLiveCameraFullScreen = false
                pendingSceneQuestionAfterCamera = null
                pendingReadTextTargetAfterCamera = null
                pendingLocationAction = null
            }
            val next = pendingOnboardingPermissionQueue.removeFirstOrNull()
            if (next != null) {
                _assistState.update { it.copy(pendingPermissionPrompt = next) }
                return
            } else {
                _onboardingState.update { it.copy(awaitingPermissionBatch = false) }
                onOnboardingPermissionBatchFinished()
                return
            }
        }

        if (!granted) {
            val msg = "${permission.title} was denied. You can grant it anytime when using this feature."
            _assistState.update { it.copy(errorMessage = msg) }
            voiceCoordinator.speak(msg, autoListenAfter = false)
            pendingVoiceAfterMicPermission = false
            pendingLiveCameraAfterPermission = false
            pendingLiveCameraFullScreen = false
            pendingSceneQuestionAfterCamera = null
            pendingReadTextTargetAfterCamera = null
            pendingLocationAction = null
            return
        }

        when (permission) {
            ClearPathPermission.MICROPHONE -> {
                if (!_settings.value.hasCompletedOnboarding &&
                    _onboardingState.value.mode == SetupMode.VOICE_FIRST &&
                    pendingVoiceAfterMicPermission
                ) {
                    pendingVoiceAfterMicPermission = false
                    startLiveOnboardingSession()
                    return
                }
                if (pendingLiveCameraAfterPermission) {
                    val full = pendingLiveCameraFullScreen
                    pendingLiveCameraAfterPermission = false
                    pendingLiveCameraFullScreen = false
                    setLiveCameraShare(enabled = true, openFullScreen = full)
                    return
                }
                if (pendingVoiceAfterMicPermission) {
                    pendingVoiceAfterMicPermission = false
                    startLiveAssistSession()
                }
                if (pendingWakePhraseAfterMicPermission) {
                    pendingWakePhraseAfterMicPermission = false
                    setWakePhraseModeEnabled(true)
                }
            }
            ClearPathPermission.CAMERA -> {
                if (pendingLiveCameraAfterPermission) {
                    val full = pendingLiveCameraFullScreen
                    pendingLiveCameraAfterPermission = false
                    pendingLiveCameraFullScreen = false
                    setLiveCameraShare(enabled = true, openFullScreen = full)
                    return
                }
                val sceneQ = pendingSceneQuestionAfterCamera
                val readLang = pendingReadTextTargetAfterCamera
                pendingSceneQuestionAfterCamera = null
                pendingReadTextTargetAfterCamera = null
                if (sceneQ != null) {
                    describeCurrentScene(sceneQ, autoListen = true)
                } else if (readLang != null) {
                    readTextFromCamera(readLang)
                }
            }
            ClearPathPermission.LOCATION -> {
                val action = pendingLocationAction
                pendingLocationAction = null
                action?.invoke()
            }
            ClearPathPermission.NOTIFICATIONS -> {
                if (_settings.value.wakePhraseModeEnabled &&
                    _permissions.value.hasMicrophone &&
                    !geminiLive.state.value.isActive
                ) {
                    runCatching { WakePhraseService.start(appContext) }
                }
            }
        }
    }

    fun dismissPermissionPrompt() {
        waitingPermissionRequest = null
        _assistState.update {
            it.copy(
                pendingPermissionPrompt = null,
                isWaitingForPermissionResult = false
            )
        }
        if (_onboardingState.value.awaitingPermissionBatch) {
            pendingOnboardingPermissionQueue.clear()
            _onboardingState.update { it.copy(awaitingPermissionBatch = false) }
        }
    }

    fun beginPermissionRequest(permission: ClearPathPermission): Boolean {
        if (_assistState.value.isWaitingForPermissionResult ||
            waitingPermissionRequest != null ||
            _assistState.value.pendingPermissionPrompt != permission
        ) {
            return false
        }
        waitingPermissionRequest = permission
        _assistState.update { it.copy(isWaitingForPermissionResult = true) }
        voiceCoordinator.speak(
            "Waiting for Android to confirm ${permission.title}.",
            autoListenAfter = false
        )
        return true
    }

    fun bindCameraPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        useBackCamera: Boolean = cameraController.useBackCamera.value
    ) {
        lastBoundLifecycleOwner = lifecycleOwner
        lastBoundPreviewView = previewView
        if (PermissionChecker.snapshot(appContext).hasCamera) {
            cameraController.bindCamera(lifecycleOwner, previewView, useBackCamera)
        }
    }

    fun flipCameraLens(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        val nextBack = !cameraController.useBackCamera.value
        haptics.onListeningStart()
        bindCameraPreview(lifecycleOwner, previewView, useBackCamera = nextBack)
    }

    fun toggleLiveCameraShare(openFullScreenIfStarting: Boolean = false) {
        val currentlySharing = _isLiveCameraShareActive.value
        setLiveCameraShare(
            enabled = !currentlySharing,
            openFullScreen = if (!currentlySharing) openFullScreenIfStarting else false
        )
    }

    fun setFullScreenCameraView(open: Boolean) {
        if (open) {
            if (!_isLiveCameraShareActive.value) {
                setLiveCameraShare(enabled = true, openFullScreen = true)
            } else {
                _isFullScreenCameraView.value = true
            }
        } else {
            _isFullScreenCameraView.value = false
        }
    }

    fun setLiveCameraShare(enabled: Boolean, openFullScreen: Boolean = false) {
        refreshPermissions()
        if (!enabled) {
            _isLiveCameraShareActive.value = false
            _isFullScreenCameraView.value = false
            cameraController.setLiveStreamEnabled(false)
            geminiLive.setCameraStreamingActive(false)
            haptics.onListeningStop()
            return
        }

        if (!_permissions.value.hasCamera) {
            pendingLiveCameraAfterPermission = true
            pendingLiveCameraFullScreen = openFullScreen
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.CAMERA) }
            voiceCoordinator.speak(ClearPathPermission.CAMERA.rationale, autoListenAfter = false)
            return
        }
        if (!_permissions.value.hasMicrophone) {
            pendingLiveCameraAfterPermission = true
            pendingLiveCameraFullScreen = openFullScreen
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.MICROPHONE) }
            voiceCoordinator.speak(ClearPathPermission.MICROPHONE.rationale, autoListenAfter = false)
            return
        }

        _isLiveCameraShareActive.value = true
        _isFullScreenCameraView.value = openFullScreen
        cameraController.setLiveStreamEnabled(true)
        haptics.onListeningStart()

        val live = geminiLive.state.value
        if (live.isActive) {
            geminiLive.setCameraStreamingActive(true)
            geminiLive.sendTextTurn(
                "Live camera sharing is now active and streaming continuous frames. Acknowledge briefly in one short sentence."
            )
        } else if (!live.isConnecting) {
            startLiveAssistSession(
                initialUserRequest = "Live camera sharing just started. Greet briefly in one short sentence and mention what you currently see if clear."
            )
        }
    }

    fun toggleLiveInterpreterMode() {
        val currentlyInterpreting = geminiLive.state.value.isInterpreterMode
        setLiveInterpreterMode(enabled = !currentlyInterpreting)
    }

    fun setLiveInterpreterMode(
        enabled: Boolean,
        targetLanguage: String = "English",
        notifyLiveModel: Boolean = true
    ) {
        val resolvedTarget = targetLanguage.ifBlank { "English" }
        val live = geminiLive.state.value
        if (enabled && !live.isActive && !live.isConnecting) {
            startLiveAssistSession(
                initialUserRequest = "Switch immediately to Live Interpreter Mode (translating into $resolvedTarget). Call set_live_interpreter_mode(enabled=true), say briefly 'Interpreter mode ready, listening now', and translate whatever the next speaker says."
            )
            return
        }
        geminiLive.setInterpreterMode(enabled, resolvedTarget)
        haptics.onListeningStart()
        if (notifyLiveModel && live.isActive) {
            val prompt = if (enabled) {
                "LIVE INTERPRETER MODE IS NOW ON. Pause other tasks, stay quiet while another person speaks in any language (Chinese, Yoruba, French, Spanish, Arabic, etc.), and interpret their meaning clearly into $resolvedTarget until the user says stop translating."
            } else {
                "LIVE INTERPRETER MODE IS NOW OFF. Briefly confirm in one short sentence that you are back in normal ClearPath guide mode."
            }
            geminiLive.sendTextTurn(prompt)
        }
    }

    fun requestScenerySummary() {
        val live = geminiLive.state.value
        if (live.isActive) {
            geminiLive.sendTextTurn(
                "Give me a complete summary about my scenery right now using get_scenery_summary, combining GPS address, compass direction, nearby places, and camera vision. Coach me briefly if I need to tilt or adjust the camera."
            )
        } else {
            runAssistantWithTools("Give me a summary about my scenery using get_scenery_summary.")
        }
    }

    fun onPrimaryVoiceButtonPressed() {
        refreshPermissions()
        val live = geminiLive.state.value
        if (live.isActive || live.isConnecting) {
            stopLiveAssistSession()
            return
        }
        val vPhase = voiceCoordinator.state.value.phase
        if (vPhase == VoicePhase.SPEAKING || vPhase == VoicePhase.LISTENING) {
            voiceCoordinator.togglePrimaryVoiceAction()
            return
        }
        if (!_permissions.value.hasMicrophone) {
            pendingVoiceAfterMicPermission = true
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.MICROPHONE) }
            voiceCoordinator.speak(ClearPathPermission.MICROPHONE.rationale, autoListenAfter = false)
            return
        }
        startLiveAssistSession()
    }

    fun stopSpeakingOrSession() {
        activeAiJob?.cancel()
        stopLiveAssistSession()
        voiceCoordinator.endSession("Session paused.")
    }

    fun repeatLastSpoken() {
        val liveText = geminiLive.state.value.outputTranscript
        if (geminiLive.state.value.isActive && liveText.isNotBlank()) {
            geminiLive.sendTextTurn("Please repeat your last response briefly.")
        } else {
            voiceCoordinator.repeatLastResponse()
        }
    }

    private fun handleVoiceTranscript(transcript: String) {
        val intent = VoiceIntentParser.parse(
            rawTranscript = transcript,
            hasPriorSceneContext = _assistState.value.hasSceneContext
        )

        when (intent) {
            is ClearPathIntent.RepeatLast -> voiceCoordinator.repeatLastResponse()
            is ClearPathIntent.StopSpeaking -> voiceCoordinator.stopSpeaking()
            is ClearPathIntent.EndSession -> voiceCoordinator.endSession()
            is ClearPathIntent.AdjustSpeechRate -> {
                val next = (_settings.value.speechRate + intent.delta).coerceIn(0.6f, 1.8f)
                viewModelScope.launch {
                    preferences.setSpeechRate(next)
                    voiceCoordinator.speak(
                        "Speech speed set to ${"%.1f".format(next)} times.",
                        autoListenAfter = true
                    )
                }
            }
            is ClearPathIntent.ListSettings -> {
                val summary = buildSpokenSettingsSummary()
                _assistState.update { it.copy(latestAnswer = summary) }
                voiceCoordinator.speak(summary, autoListenAfter = true)
            }
            is ClearPathIntent.AssistantToolRequest -> {
                runAssistantWithTools(intent.utterance)
            }
            is ClearPathIntent.WhereAmI -> {
                checkCurrentLocationAndAnnounce()
            }
            is ClearPathIntent.NavigationStatus -> {
                announceNavigationStatus()
            }
            is ClearPathIntent.StopNavigation -> {
                stopNavigation()
            }
            is ClearPathIntent.OpenInMapsApp -> {
                openInPhoneMaps(intent.query)
            }
            is ClearPathIntent.NavigateTo -> {
                searchAndStartNavigation(intent.query)
            }
            is ClearPathIntent.ReadCameraText -> {
                readTextFromCamera(intent.targetLanguage)
            }
            is ClearPathIntent.TranslateSpeech -> {
                if (intent.textToTranslate != null) {
                    translateText(intent.textToTranslate, _translationState.value.sourceLanguage, intent.targetLanguage)
                } else {
                    viewModelScope.launch {
                        preferences.setTranslationLanguages(_translationState.value.sourceLanguage.code, intent.targetLanguage.code)
                    }
                    voiceCoordinator.speak(
                        "Translation target set to ${intent.targetLanguage.displayName}. Speak the sentence you want translated.",
                        autoListenAfter = true
                    )
                }
            }
            is ClearPathIntent.DescribeScene -> {
                describeCurrentScene(intent.question, autoListen = true)
            }
            is ClearPathIntent.FollowUpQuestion -> {
                runAssistantWithTools(intent.question)
            }
        }
    }

    private fun runAssistantWithTools(utterance: String) {
        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            voiceCoordinator.markProcessing("Processing your request...")
            _assistState.update { it.copy(isProcessingAi = true, errorMessage = null) }
            try {
                val reply = aiRepository.askAssistantWithTools(
                    userMessage = utterance,
                    toolHandler = { name, args -> executeAiTool(name, args) }
                )
                haptics.onAnswerReady()
                _assistState.update {
                    it.copy(
                        isProcessingAi = false,
                        latestAnswer = reply,
                        lastOrigin = InferenceOrigin.CLOUD
                    )
                }
                voiceCoordinator.speak(reply, autoListenAfter = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline fallback for settings edits or listing if network is unavailable
                val offlineEdit = VoiceIntentParser.parseOfflineSettingEdit(utterance)
                if (offlineEdit != null) {
                    val resultMsg = applyOfflineSettingEdit(offlineEdit)
                    haptics.onAnswerReady()
                    _assistState.update { it.copy(isProcessingAi = false, latestAnswer = resultMsg) }
                    voiceCoordinator.speak(resultMsg, autoListenAfter = true)
                    return@launch
                }
                val err = e.localizedMessage ?: "Assistant request failed."
                haptics.onErrorAlert()
                _assistState.update { it.copy(isProcessingAi = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            }
        }
    }

    private suspend fun executeAiTool(name: String, args: Map<String, JsonElement>): JsonObject {
        return when (name) {
            "list_available_settings" -> buildSettingsJson()
            "update_setting" -> applyToolSettingUpdate(args)
            "list_available_voices" -> buildVoicesJson()
            "set_live_interpreter_mode" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val targetLang = args["target_language"]?.jsonPrimitive?.contentOrNull ?: "English"
                withContext(Dispatchers.Main) {
                    setLiveInterpreterMode(
                        enabled = enabled,
                        targetLanguage = targetLang,
                        notifyLiveModel = false
                    )
                }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "interpreter_mode" to JsonPrimitive(enabled),
                        "target_language" to JsonPrimitive(targetLang),
                        "instruction" to JsonPrimitive(
                            if (enabled) {
                                "Stay quiet while another person speaks, then interpret their words clearly into $targetLang."
                            } else {
                                "Interpreter mode ended; resume normal ClearPath assistant mode."
                            }
                        )
                    )
                )
            }
            "get_scenery_summary" -> {
                val focus = args["focus_question"]?.jsonPrimitive?.contentOrNull
                    ?: "Give a full assistive summary of the user's current scenery, location, nearby landmarks, and camera view."
                executeScenerySummaryTool(focus)
            }
            "set_live_camera_share" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val fullScreen = args["full_screen"]?.jsonPrimitive?.booleanOrNull ?: false
                withContext(Dispatchers.Main) {
                    setLiveCameraShare(enabled = enabled, openFullScreen = fullScreen)
                }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "live_camera_share" to JsonPrimitive(enabled),
                        "message" to JsonPrimitive(
                            if (enabled) "Continuous 1 FPS live camera stream enabled." else "Live camera stream paused."
                        )
                    )
                )
            }
            "flip_camera_lens" -> {
                val nextBack = !cameraController.useBackCamera.value
                withContext(Dispatchers.Main) {
                    bindCameraPreviewIfPossible(useBackCamera = nextBack)
                }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "camera_lens" to JsonPrimitive(if (nextBack) "back" else "front")
                    )
                )
            }
            "describe_camera_scene" -> {
                val q = args["question"]?.jsonPrimitive?.contentOrNull
                    ?: "Describe what is directly ahead safely and concisely."
                executeCameraSceneTool(q)
            }
            "read_camera_text" -> {
                val langArg = args["target_language"]?.jsonPrimitive?.contentOrNull
                executeCameraReadTextTool(langArg)
            }
            "get_current_location" -> executeCurrentLocationTool()
            "start_walking_guidance" -> {
                val dest = args["destination"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (dest.isBlank()) {
                    JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("No destination provided.")))
                } else {
                    searchAndStartNavigation(dest)
                    JsonObject(mapOf("status" to JsonPrimitive("started"), "message" to JsonPrimitive("Searching walking route to $dest now.")))
                }
            }
            "open_phone_maps" -> {
                val dest = args["destination"]?.jsonPrimitive?.contentOrNull
                openInPhoneMaps(dest)
                JsonObject(mapOf("status" to JsonPrimitive("opened"), "message" to JsonPrimitive("Launching walking directions in the phone's Maps app.")))
            }
            "get_navigation_status" -> buildNavigationStatusJson()
            "stop_navigation" -> {
                stopNavigation()
                JsonObject(mapOf("status" to JsonPrimitive("stopped"), "message" to JsonPrimitive("Walking guidance stopped.")))
            }
            "translate_text" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val targetArg = args["target_language"]?.jsonPrimitive?.contentOrNull ?: _translationState.value.targetLanguage.code
                val target = LanguageCatalog.findByCodeOrName(targetArg) ?: _translationState.value.targetLanguage
                if (text.isBlank()) {
                    JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("No text provided to translate.")))
                } else {
                    val out = translationManager.translateSpokenOrTypedText(text, _translationState.value.sourceLanguage, target).getOrThrow()
                    _translationState.update { it.copy(inputText = text, lastOutput = out) }
                    JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("ok"),
                            "detected_language" to JsonPrimitive(out.detectedLanguage),
                            "target_language" to JsonPrimitive(target.displayName),
                            "translated_text" to JsonPrimitive(out.translatedText)
                        )
                    )
                }
            }
            else -> JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Unknown tool: $name")))
        }
    }

    private fun buildSpokenSettingsSummary(): String {
        val s = _settings.value
        val srcName = LanguageCatalog.findByCodeOrName(s.translationSourceCode)?.displayName ?: "Auto Detect"
        val tgtName = LanguageCatalog.findByCodeOrName(s.translationTargetCode)?.displayName ?: "English"
        val mode = _navigationState.value.providerMode.label
        return "Available ClearPath settings: " +
            "Speech speed is ${"%.1f".format(s.speechRate)} times, from 0.6 to 1.8. " +
            "Continuous listening is ${if (s.continuousConversation) "on" else "off"}. " +
            "Scene watch alerts are ${if (s.sceneWatchEnabled) "on" else "off"}. " +
            "Haptic vibration cues are ${if (s.hapticsEnabled) "on" else "off"}. " +
            "Prefer on-device AI is ${if (s.preferOnDeviceAi) "on" else "off"}. " +
            "Camera visual preview is ${if (s.showCameraPreview) "shown" else "hidden"}. " +
            "Wake-phrase mode for ClearPath activate is ${if (s.wakePhraseModeEnabled) "on" else "off"}. " +
            "Auto-listen on app open is ${if (s.autoListenOnOpen) "on" else "off"}. " +
            "Translation languages are $srcName to $tgtName. " +
            "Walking route mode is $mode. " +
            "You can tell me to change or toggle any of these."
    }

    private fun buildSettingsJson(): JsonObject {
        val s = _settings.value
        val tgtName = LanguageCatalog.findByCodeOrName(s.translationTargetCode)?.displayName ?: s.translationTargetCode
        val srcName = LanguageCatalog.findByCodeOrName(s.translationSourceCode)?.displayName ?: s.translationSourceCode
        return JsonObject(
            mapOf(
                "speech_rate" to JsonPrimitive("${"%.1f".format(s.speechRate)} (range 0.6 to 1.8)"),
                "continuous_conversation" to JsonPrimitive(s.continuousConversation),
                "scene_watch" to JsonPrimitive(s.sceneWatchEnabled),
                "haptics" to JsonPrimitive(s.hapticsEnabled),
                "prefer_on_device_ai" to JsonPrimitive(s.preferOnDeviceAi),
                "show_camera_preview" to JsonPrimitive(s.showCameraPreview),
                "wake_phrase_mode" to JsonPrimitive(s.wakePhraseModeEnabled),
                "auto_listen_on_open" to JsonPrimitive(s.autoListenOnOpen),
                "translation_source_language" to JsonPrimitive("$srcName (${s.translationSourceCode})"),
                "translation_target_language" to JsonPrimitive("$tgtName (${s.translationTargetCode})"),
                "routing_mode" to JsonPrimitive(_navigationState.value.providerMode.label),
                "preferred_live_voice" to JsonPrimitive(s.preferredLiveVoice),
                "preferred_device_voice" to JsonPrimitive(s.preferredVoiceName.ifBlank { "System default" }),
                "ors_api_key_configured" to JsonPrimitive(s.orsApiKey.isNotBlank()),
                "custom_osrm_configured" to JsonPrimitive(s.customRoutingBaseUrl.isNotBlank())
            )
        )
    }

    private fun buildVoicesJson(): JsonObject {
        val liveVoices = LiveVoiceCatalog.voices.map { lv ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(lv.name),
                    "style" to JsonPrimitive(lv.style),
                    "gender" to JsonPrimitive(lv.gender),
                    "type" to JsonPrimitive("HD Live Voice")
                )
            )
        }
        val deviceVoices = voiceCoordinator.state.value.availableVoices.take(8).map { v ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(v.name),
                    "locale" to JsonPrimitive(v.localeTag),
                    "requires_network" to JsonPrimitive(v.requiresNetwork),
                    "type" to JsonPrimitive("Offline Device TTS")
                )
            )
        }
        return JsonObject(
            mapOf(
                "active_live_voice" to JsonPrimitive(_settings.value.preferredLiveVoice),
                "active_device_voice" to JsonPrimitive(_settings.value.preferredVoiceName.ifBlank { "System default" }),
                "hd_live_voices" to JsonArray(liveVoices),
                "device_tts_voices" to JsonArray(deviceVoices)
            )
        )
    }

    private suspend fun applyToolSettingUpdate(args: Map<String, JsonElement>): JsonObject {
        val settingName = args["setting_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val boolVal = args["boolean_value"]?.jsonPrimitive?.booleanOrNull
        val numVal = args["number_value"]?.jsonPrimitive?.doubleOrNull?.toFloat()
        val strVal = args["string_value"]?.jsonPrimitive?.contentOrNull
        val wantsToggle = strVal.equals("toggle", ignoreCase = true)
        val current = _settings.value

        return when (settingName) {
            "speech_rate" -> {
                val parsed = numVal ?: strVal?.toFloatOrNull()
                if (parsed == null) {
                    JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Provide a speed between 0.6 and 1.8.")))
                } else {
                    val clamped = parsed.coerceIn(0.6f, 1.8f)
                    preferences.setSpeechRate(clamped)
                    JsonObject(mapOf("status" to JsonPrimitive("updated"), "speech_rate" to JsonPrimitive("%.1f".format(clamped))))
                }
            }
            "continuous_conversation" -> {
                val next = boolVal ?: if (wantsToggle) !current.continuousConversation else parseBoolWord(strVal) ?: !current.continuousConversation
                preferences.setContinuousConversation(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "continuous_conversation" to JsonPrimitive(next)))
            }
            "scene_watch" -> {
                val next = boolVal ?: if (wantsToggle) !current.sceneWatchEnabled else parseBoolWord(strVal) ?: !current.sceneWatchEnabled
                preferences.setSceneWatchEnabled(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "scene_watch" to JsonPrimitive(next)))
            }
            "haptics" -> {
                val next = boolVal ?: if (wantsToggle) !current.hapticsEnabled else parseBoolWord(strVal) ?: !current.hapticsEnabled
                preferences.setHapticsEnabled(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "haptics" to JsonPrimitive(next)))
            }
            "prefer_on_device_ai" -> {
                val next = boolVal ?: if (wantsToggle) !current.preferOnDeviceAi else parseBoolWord(strVal) ?: !current.preferOnDeviceAi
                setPreferOnDevice(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "prefer_on_device_ai" to JsonPrimitive(next)))
            }
            "show_camera_preview" -> {
                val next = boolVal ?: if (wantsToggle) !current.showCameraPreview else parseBoolWord(strVal) ?: !current.showCameraPreview
                preferences.setShowCameraPreview(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "show_camera_preview" to JsonPrimitive(next)))
            }
            "wake_phrase_mode" -> {
                val next = boolVal ?: if (wantsToggle) !current.wakePhraseModeEnabled else parseBoolWord(strVal) ?: !current.wakePhraseModeEnabled
                setWakePhraseModeEnabled(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "wake_phrase_mode" to JsonPrimitive(next)))
            }
            "auto_listen_on_open" -> {
                val next = boolVal ?: if (wantsToggle) !current.autoListenOnOpen else parseBoolWord(strVal) ?: !current.autoListenOnOpen
                setAutoListenOnOpen(next)
                JsonObject(mapOf("status" to JsonPrimitive("updated"), "auto_listen_on_open" to JsonPrimitive(next)))
            }
            "translation_source_language" -> {
                val lang = strVal?.let { LanguageCatalog.findByCodeOrName(it) }
                if (lang == null) {
                    JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Supported languages: auto, English, Yoruba, Igbo, Hausa, Pidgin, French, Arabic, Spanish.")))
                } else {
                    setTranslationSource(lang)
                    JsonObject(mapOf("status" to JsonPrimitive("updated"), "translation_source_language" to JsonPrimitive(lang.displayName)))
                }
            }
            "translation_target_language" -> {
                val lang = strVal?.let { LanguageCatalog.findByCodeOrName(it) }
                if (lang == null || lang.code == "auto") {
                    JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Supported target languages: English, Yoruba, Igbo, Hausa, Pidgin, French, Arabic, Spanish.")))
                } else {
                    setTranslationTarget(lang)
                    JsonObject(mapOf("status" to JsonPrimitive("updated"), "translation_target_language" to JsonPrimitive(lang.displayName)))
                }
            }
            "routing_mode" -> {
                val match = RoutingProviderMode.entries.firstOrNull { mode ->
                    val sLower = strVal?.lowercase().orEmpty()
                    mode.name.equals(strVal, ignoreCase = true) ||
                        mode.label.lowercase().contains(sLower) ||
                        (sLower.contains("openroute") && mode == RoutingProviderMode.OPEN_ROUTE_SERVICE) ||
                        (sLower.contains("custom") && mode == RoutingProviderMode.CUSTOM_OSRM) ||
                        (sLower.contains("demo") && mode == RoutingProviderMode.PUBLIC_DEMO_DEV_ONLY)
                }
                if (match == null) {
                    JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Available routing modes: OpenRouteService, Custom Self-Hosted OSRM, or Public OSRM Demo.")))
                } else {
                    setRoutingProviderMode(match)
                    JsonObject(mapOf("status" to JsonPrimitive("updated"), "routing_mode" to JsonPrimitive(match.label)))
                }
            }
            "preferred_voice" -> {
                val query = strVal?.trim().orEmpty()
                val liveMatch = LiveVoiceCatalog.findByName(query)
                if (liveMatch != null) {
                    setPreferredLiveVoice(liveMatch.name)
                    if (geminiLive.state.value.isActive) {
                        viewModelScope.launch {
                            delay(180)
                            geminiLive.switchVoice(
                                liveMatch.name,
                                "Say in one brief sentence: Voice switched to ${liveMatch.name}. How else can I assist you?"
                            )
                        }
                    }
                    JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("updated"),
                            "preferred_live_voice" to JsonPrimitive(liveMatch.spokenLabel)
                        )
                    )
                } else {
                    val match = voiceCoordinator.state.value.availableVoices.firstOrNull {
                        it.name.contains(query, ignoreCase = true) || it.localeTag.contains(query, ignoreCase = true)
                    }
                    if (query.equals("default", ignoreCase = true)) {
                        setPreferredVoiceName("")
                        setPreferredLiveVoice("Aoede")
                        JsonObject(mapOf("status" to JsonPrimitive("updated"), "preferred_voice" to JsonPrimitive("Aoede / System default")))
                    } else if (match != null) {
                        setPreferredVoiceName(match.name)
                        JsonObject(mapOf("status" to JsonPrimitive("updated"), "preferred_voice" to JsonPrimitive("${match.localeTag} (${match.name})")))
                    } else {
                        JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("No live or device voice matched '$query'.")))
                    }
                }
            }
            else -> JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Unknown setting '$settingName'.")))
        }
    }

    private fun parseBoolWord(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "on", "yes", "enable", "enabled", "show" -> true
        "false", "off", "no", "disable", "disabled", "hide" -> false
        else -> null
    }

    private suspend fun applyOfflineSettingEdit(edit: OfflineSettingEdit): String {
        val current = _settings.value
        return when (edit.settingKey) {
            "scene_watch" -> {
                val next = edit.booleanValue ?: !current.sceneWatchEnabled
                preferences.setSceneWatchEnabled(next)
                "Scene watch is now ${if (next) "on" else "off"}."
            }
            "haptics" -> {
                val next = edit.booleanValue ?: !current.hapticsEnabled
                preferences.setHapticsEnabled(next)
                "Haptic cues are now ${if (next) "on" else "off"}."
            }
            "continuous_conversation" -> {
                val next = edit.booleanValue ?: !current.continuousConversation
                preferences.setContinuousConversation(next)
                "Continuous listening is now ${if (next) "on" else "off"}."
            }
            "show_camera_preview" -> {
                val next = edit.booleanValue ?: !current.showCameraPreview
                preferences.setShowCameraPreview(next)
                "Camera visual preview is now ${if (next) "shown" else "hidden"}."
            }
            "prefer_on_device_ai" -> {
                val next = edit.booleanValue ?: !current.preferOnDeviceAi
                setPreferOnDevice(next)
                "Prefer on-device AI is now ${if (next) "on" else "off"}."
            }
            "wake_phrase_mode" -> {
                val next = edit.booleanValue ?: !current.wakePhraseModeEnabled
                setWakePhraseModeEnabled(next)
                "Wake-phrase mode is now ${if (next) "on. Say ClearPath activate while the notification is active" else "off"}."
            }
            "auto_listen_on_open" -> {
                val next = edit.booleanValue ?: !current.autoListenOnOpen
                setAutoListenOnOpen(next)
                "Auto-listen on app open is now ${if (next) "on" else "off"}."
            }
            "speech_rate" -> {
                val rate = edit.floatValue ?: current.speechRate
                preferences.setSpeechRate(rate)
                "Speech speed set to ${"%.1f".format(rate)} times."
            }
            "translation_target_language" -> {
                val lang = edit.stringValue?.let { LanguageCatalog.findByCodeOrName(it) }
                if (lang != null) {
                    setTranslationTarget(lang)
                    "Translation target language set to ${lang.displayName}."
                } else {
                    "Could not match that language."
                }
            }
            else -> "Setting updated."
        }
    }

    private suspend fun executeScenerySummaryTool(focusQuestion: String): JsonObject {
        refreshPermissions()
        val snap = _permissions.value
        var addressSummary = "Location permission not granted yet"
        var compassFacing = _navigationState.value.compassCardinal
        var nearbyLabels = emptyList<String>()
        var accuracyMeters = -1

        if (snap.hasLocation) {
            withContext(Dispatchers.Main) { startCompassObservation() }
            runCatching {
                val fix = locationTracker.getCurrentFix().getOrThrow()
                accuracyMeters = fix.accuracyMeters.toInt()
                val address = locationTracker.reverseGeocodeAddress(fix.point)
                    ?: "Latitude ${"%.4f".format(fix.point.latitude)}, Longitude ${"%.4f".format(fix.point.longitude)}"
                addressSummary = address
                compassFacing = _navigationState.value.compassCardinal
                _navigationState.update { it.copy(currentLocation = fix, currentAddressSummary = address) }

                val nearby = routingClient.fetchNearbyPlacesSummary(fix.point).getOrNull().orEmpty()
                nearbyLabels = nearby.take(5).map { place ->
                    val dist = place.distanceMeters?.toInt()?.let { " (~${it}m)" } ?: ""
                    "${place.name} [${place.subtitle}]$dist"
                }
            }
        }

        var cameraSummary = when {
            !snap.hasCamera -> "Camera permission not granted yet."
            geminiLive.state.value.isCameraStreaming -> "Live camera stream is active right now; inspect the live video feed directly."
            else -> ""
        }

        if (snap.hasCamera && !geminiLive.state.value.isCameraStreaming) {
            var bitmap: Bitmap? = null
            try {
                bitmap = cameraController.captureFrame().getOrThrow()
                if (geminiLive.state.value.isActive) {
                    geminiLive.sendCameraFrame(bitmap)
                }
                var latestText = ""
                aiRepository.analyzeSceneStream(
                    bitmap = bitmap,
                    userQuestion = "$focusQuestion Also note if the user should tilt up, down, left, right, or step back for a clearer view.",
                    preferOnDeviceSingleTurn = _assistState.value.preferOnDeviceSingleTurn
                ).collect { chunk ->
                    latestText = chunk.partialText
                }
                cameraSummary = latestText
                _assistState.update { it.copy(hasSceneContext = true, latestAnswer = latestText) }
            } catch (e: Exception) {
                cameraSummary = "Camera frame check failed: ${e.localizedMessage ?: "unavailable"}."
            } finally {
                bitmap?.recycle()
            }
        }

        return JsonObject(
            mapOf(
                "status" to JsonPrimitive("ok"),
                "gps_address" to JsonPrimitive(addressSummary),
                "compass_facing" to JsonPrimitive(compassFacing),
                "gps_accuracy_meters" to JsonPrimitive(accuracyMeters),
                "nearby_map_places" to JsonArray(nearbyLabels.map { JsonPrimitive(it) }),
                "camera_view" to JsonPrimitive(cameraSummary),
                "coaching_hint" to JsonPrimitive(
                    "Combine GPS, compass direction, nearby places, and camera view into a calm overview. If the camera view looks aimed too low/high/close, politely ask the user to tilt up, shift left/right, or step back."
                )
            )
        )
    }

    private suspend fun executeCameraSceneTool(question: String): JsonObject {
        refreshPermissions()
        if (!_permissions.value.hasCamera) {
            pendingSceneQuestionAfterCamera = question
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.CAMERA) }
            return JsonObject(mapOf("status" to JsonPrimitive("permission_needed"), "message" to JsonPrimitive(ClearPathPermission.CAMERA.rationale)))
        }
        var bitmap: Bitmap? = null
        return try {
            bitmap = cameraController.captureFrame().getOrThrow()
            if (geminiLive.state.value.isActive) {
                geminiLive.sendCameraFrame(bitmap)
            }
            var latestText = ""
            aiRepository.analyzeSceneStream(
                bitmap = bitmap,
                userQuestion = "Use the attached camera frame directly to answer: $question",
                preferOnDeviceSingleTurn = _assistState.value.preferOnDeviceSingleTurn
            ).collect { chunk ->
                latestText = chunk.partialText
            }
            _assistState.update { it.copy(hasSceneContext = true, latestAnswer = latestText) }
            JsonObject(mapOf("status" to JsonPrimitive("ok"), "description" to JsonPrimitive(latestText)))
        } catch (e: Exception) {
            JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive(e.localizedMessage ?: "Camera capture failed.")))
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun executeCameraReadTextTool(targetLangArg: String?): JsonObject {
        refreshPermissions()
        val resolvedTarget = targetLangArg?.let { LanguageCatalog.findByCodeOrName(it) } ?: _translationState.value.targetLanguage
        if (!_permissions.value.hasCamera) {
            pendingReadTextTargetAfterCamera = resolvedTarget
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.CAMERA) }
            return JsonObject(mapOf("status" to JsonPrimitive("permission_needed"), "message" to JsonPrimitive(ClearPathPermission.CAMERA.rationale)))
        }
        var bitmap: Bitmap? = null
        return try {
            bitmap = cameraController.captureFrame().getOrThrow()
            if (resolvedTarget.code == "en") {
                val offline = translationManager.recognizeTextOffline(bitmap)
                if (offline.isSuccess) {
                    val ocr = offline.getOrThrow()
                    return JsonObject(mapOf("status" to JsonPrimitive("ok"), "text" to JsonPrimitive(ocr.extractedText), "origin" to JsonPrimitive("Offline ML Kit OCR")))
                }
            }
            val output = translationManager.readAndTranslateFromCamera(bitmap, resolvedTarget).getOrThrow()
            JsonObject(
                mapOf(
                    "status" to JsonPrimitive("ok"),
                    "detected_language" to JsonPrimitive(output.detectedLanguage),
                    "original_text" to JsonPrimitive(output.originalText),
                    "translated_text" to JsonPrimitive(output.translatedText)
                )
            )
        } catch (e: Exception) {
            JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive(e.localizedMessage ?: "Could not read text from camera.")))
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun executeCurrentLocationTool(): JsonObject {
        refreshPermissions()
        if (!_permissions.value.hasLocation) {
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.LOCATION) }
            return JsonObject(mapOf("status" to JsonPrimitive("permission_needed"), "message" to JsonPrimitive(ClearPathPermission.LOCATION.rationale)))
        }
        startCompassObservation()
        return try {
            val fix = locationTracker.getCurrentFix().getOrThrow()
            val address = locationTracker.reverseGeocodeAddress(fix.point)
                ?: "Latitude ${"%.4f".format(fix.point.latitude)}, Longitude ${"%.4f".format(fix.point.longitude)}"
            val heading = _navigationState.value.compassCardinal
            _navigationState.update { it.copy(currentLocation = fix, currentAddressSummary = address) }
            JsonObject(
                mapOf(
                    "status" to JsonPrimitive("ok"),
                    "address" to JsonPrimitive(address),
                    "facing" to JsonPrimitive(heading),
                    "accuracy_meters" to JsonPrimitive(fix.accuracyMeters.toInt())
                )
            )
        } catch (e: Exception) {
            JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive(e.localizedMessage ?: "Location unavailable.")))
        }
    }

    private fun buildNavigationStatusJson(): JsonObject {
        val nav = _navigationState.value
        val bearing = nav.activeBearingTarget
        val route = nav.activeRoute
        return when {
            nav.isNavigating && bearing != null -> JsonObject(
                mapOf(
                    "active" to JsonPrimitive(true),
                    "mode" to JsonPrimitive("compass_bearing"),
                    "destination" to JsonPrimitive(bearing.name),
                    "distance_meters" to JsonPrimitive(nav.bearingTargetDistanceMeters.toInt()),
                    "cardinal" to JsonPrimitive(NavigationEngine.cardinalDirection(nav.bearingTargetDegrees)),
                    "relative_turn" to JsonPrimitive(NavigationEngine.relativeTurnCue(nav.compassDegrees, nav.bearingTargetDegrees))
                )
            )
            nav.isNavigating && route != null -> {
                val step = route.steps.getOrNull(nav.currentStepIndex)
                JsonObject(
                    mapOf(
                        "active" to JsonPrimitive(true),
                        "mode" to JsonPrimitive("turn_by_turn"),
                        "destination" to JsonPrimitive(route.destinationName),
                        "next_instruction" to JsonPrimitive(step?.instruction ?: ""),
                        "distance_to_turn_meters" to JsonPrimitive(nav.distanceToNextTurnMeters.toInt()),
                        "is_off_route" to JsonPrimitive(nav.isOffRoute)
                    )
                )
            }
            else -> JsonObject(mapOf("active" to JsonPrimitive(false), "message" to JsonPrimitive("No walking route is active.")))
        }
    }

    fun describeCurrentScene(question: String, autoListen: Boolean = false) {
        refreshPermissions()
        if (!_permissions.value.hasCamera) {
            pendingSceneQuestionAfterCamera = question
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.CAMERA) }
            voiceCoordinator.speak(ClearPathPermission.CAMERA.rationale, autoListenAfter = false)
            return
        }

        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            voiceCoordinator.markProcessing("Capturing and analyzing what is ahead...")
            _assistState.update { it.copy(isProcessingAi = true, errorMessage = null) }
            var bitmap: Bitmap? = null
            try {
                bitmap = cameraController.captureFrame().getOrThrow()
                if (geminiLive.state.value.isActive) {
                    geminiLive.sendCameraFrame(bitmap)
                }
                var latestText = ""
                var latestOrigin = InferenceOrigin.CLOUD
                aiRepository.analyzeSceneStream(
                    bitmap = bitmap,
                    userQuestion = question,
                    preferOnDeviceSingleTurn = _assistState.value.preferOnDeviceSingleTurn
                ).collect { chunk ->
                    latestText = chunk.partialText
                    latestOrigin = chunk.origin
                    _assistState.update {
                        it.copy(
                            latestAnswer = chunk.partialText,
                            lastOrigin = chunk.origin,
                            hasSceneContext = true
                        )
                    }
                }
                haptics.onAnswerReady()
                _assistState.update {
                    it.copy(
                        isProcessingAi = false,
                        latestAnswer = latestText,
                        lastOrigin = latestOrigin,
                        hasSceneContext = true
                    )
                }
                voiceCoordinator.speak(latestText, autoListenAfter = autoListen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Could not analyze the camera view."
                haptics.onErrorAlert()
                _assistState.update { it.copy(isProcessingAi = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            } finally {
                bitmap?.recycle()
            }
        }
    }

    fun askFollowUp(question: String) {
        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            voiceCoordinator.markProcessing("Thinking about your follow-up...")
            _assistState.update { it.copy(isProcessingAi = true, errorMessage = null) }
            try {
                var latestText = ""
                aiRepository.askFollowUpStream(question).collect { chunk ->
                    latestText = chunk.partialText
                    _assistState.update {
                        it.copy(latestAnswer = chunk.partialText, lastOrigin = chunk.origin)
                    }
                }
                haptics.onAnswerReady()
                _assistState.update { it.copy(isProcessingAi = false, latestAnswer = latestText) }
                voiceCoordinator.speak(latestText, autoListenAfter = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Follow-up request failed."
                haptics.onErrorAlert()
                _assistState.update { it.copy(isProcessingAi = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            }
        }
    }

    fun readTextFromCamera(targetLanguage: SupportedLanguage? = null) {
        refreshPermissions()
        if (!_permissions.value.hasCamera) {
            pendingReadTextTargetAfterCamera = targetLanguage
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.CAMERA) }
            voiceCoordinator.speak(ClearPathPermission.CAMERA.rationale, autoListenAfter = false)
            return
        }

        val resolvedTarget = targetLanguage ?: _translationState.value.targetLanguage
        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            voiceCoordinator.markProcessing("Reading visible text...")
            _assistState.update { it.copy(isProcessingAi = true, errorMessage = null) }
            _translationState.update { it.copy(isTranslating = true, errorMessage = null) }
            var bitmap: Bitmap? = null
            try {
                bitmap = cameraController.captureFrame().getOrThrow()
                if (resolvedTarget.code == "en") {
                    // Try fast offline ML Kit OCR first
                    val offlineOcr = translationManager.recognizeTextOffline(bitmap)
                    if (offlineOcr.isSuccess) {
                        val ocr = offlineOcr.getOrThrow()
                        haptics.onAnswerReady()
                        _assistState.update {
                            it.copy(
                                isProcessingAi = false,
                                latestAnswer = ocr.extractedText,
                                lastOrigin = ocr.origin
                            )
                        }
                        _translationState.update {
                            it.copy(
                                isTranslating = false,
                                inputText = ocr.extractedText,
                                lastOutput = TranslationOutput(
                                    originalText = ocr.extractedText,
                                    detectedLanguage = "Latin script",
                                    translatedText = ocr.extractedText,
                                    targetLanguage = resolvedTarget,
                                    origin = InferenceOrigin.OFFLINE_ML_KIT
                                ),
                                statusNote = "Read offline with ML Kit OCR."
                            )
                        }
                        voiceCoordinator.speak("Text says: ${ocr.extractedText}", autoListenAfter = true)
                        return@launch
                    }
                }

                // Fallback or non-English target: use Gemini 3.8 Flash vision + translation
                val output = translationManager.readAndTranslateFromCamera(bitmap, resolvedTarget).getOrThrow()
                haptics.onAnswerReady()
                _assistState.update {
                    it.copy(
                        isProcessingAi = false,
                        latestAnswer = output.translatedText,
                        lastOrigin = output.origin
                    )
                }
                _translationState.update {
                    it.copy(
                        isTranslating = false,
                        inputText = output.originalText,
                        lastOutput = output,
                        statusNote = "Detected ${output.detectedLanguage}, translated to ${resolvedTarget.displayName}."
                    )
                }
                speakTranslationWithHonesty(output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Could not read text from camera."
                haptics.onErrorAlert()
                _assistState.update { it.copy(isProcessingAi = false, errorMessage = err) }
                _translationState.update { it.copy(isTranslating = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            } finally {
                bitmap?.recycle()
            }
        }
    }

    fun translateText(
        text: String,
        source: SupportedLanguage = _translationState.value.sourceLanguage,
        target: SupportedLanguage = _translationState.value.targetLanguage
    ) {
        viewModelScope.launch {
            voiceCoordinator.markProcessing("Translating to ${target.displayName}...")
            _translationState.update { it.copy(isTranslating = true, inputText = text, errorMessage = null) }
            try {
                val output = translationManager.translateSpokenOrTypedText(text, source, target).getOrThrow()
                haptics.onAnswerReady()
                _translationState.update {
                    it.copy(
                        isTranslating = false,
                        lastOutput = output,
                        statusNote = "Detected ${output.detectedLanguage} -> ${target.displayName}."
                    )
                }
                speakTranslationWithHonesty(output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Translation failed."
                haptics.onErrorAlert()
                _translationState.update { it.copy(isTranslating = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            }
        }
    }

    private fun speakTranslationWithHonesty(output: TranslationOutput) {
        val ttsSupport = voiceCoordinator.probeLanguageTtsSupport(output.targetLanguage.ttsLocaleTag)
        if (ttsSupport == com.example.voice.TtsSupportStatus.UNSUPPORTED) {
            voiceCoordinator.speak(
                "Translated to ${output.targetLanguage.displayName}. Native ${output.targetLanguage.displayName} voice output is not installed on this device, so the translation is shown on screen: ${output.translatedText}",
                languageTag = "en-US",
                autoListenAfter = false
            )
        } else {
            voiceCoordinator.speak(
                output.translatedText,
                languageTag = output.targetLanguage.ttsLocaleTag,
                autoListenAfter = false
            )
        }
    }

    fun repeatTranslationOriginal() {
        val last = _translationState.value.lastOutput
        if (last == null) {
            voiceCoordinator.speak("No translation recorded yet.")
        } else {
            voiceCoordinator.speak("Original text: ${last.originalText}", languageTag = "en-US")
        }
    }

    fun repeatTranslationResult() {
        val last = _translationState.value.lastOutput
        if (last == null) {
            voiceCoordinator.speak("No translation recorded yet.")
        } else {
            speakTranslationWithHonesty(last)
        }
    }

    fun setTranslationSource(lang: SupportedLanguage) {
        _translationState.update { it.copy(sourceLanguage = lang) }
        viewModelScope.launch {
            preferences.setTranslationLanguages(lang.code, _translationState.value.targetLanguage.code)
        }
    }

    fun setTranslationTarget(lang: SupportedLanguage) {
        _translationState.update { it.copy(targetLanguage = lang) }
        viewModelScope.launch {
            preferences.setTranslationLanguages(_translationState.value.sourceLanguage.code, lang.code)
        }
    }

    fun updateTranslationInput(text: String) {
        _translationState.update { it.copy(inputText = text) }
    }

    // --- Navigation & Location ---

    fun startCompassObservation() {
        if (compassJob?.isActive == true) return
        compassJob = viewModelScope.launch {
            compassSensor.headingDegreesFlow().collect { deg ->
                _navigationState.update {
                    it.copy(
                        compassDegrees = deg,
                        compassCardinal = NavigationEngine.cardinalDirection(deg)
                    )
                }
            }
        }
    }

    fun checkCurrentLocationAndAnnounce() {
        refreshPermissions()
        if (!_permissions.value.hasLocation) {
            pendingLocationAction = { checkCurrentLocationAndAnnounce() }
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.LOCATION) }
            voiceCoordinator.speak(ClearPathPermission.LOCATION.rationale, autoListenAfter = false)
            return
        }

        startCompassObservation()
        viewModelScope.launch {
            _navigationState.update { it.copy(isSearching = true, errorMessage = null) }
            try {
                val fix = locationTracker.getCurrentFix().getOrThrow()
                val address = locationTracker.reverseGeocodeAddress(fix.point)
                    ?: "Latitude ${"%.4f".format(fix.point.latitude)}, Longitude ${"%.4f".format(fix.point.longitude)}"
                val heading = _navigationState.value.compassCardinal
                val summary = "You are near $address, facing $heading. GPS accuracy is about ${fix.accuracyMeters.toInt()} meters."
                _navigationState.update {
                    it.copy(
                        isSearching = false,
                        currentLocation = fix,
                        currentAddressSummary = address,
                        statusAnnouncement = summary
                    )
                }
                voiceCoordinator.speak(summary, autoListenAfter = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Unable to determine your GPS location."
                _navigationState.update { it.copy(isSearching = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            }
        }
    }

    fun updateDestinationQuery(query: String) {
        _navigationState.update { it.copy(destinationQuery = query) }
    }

    fun setRoutingProviderMode(mode: RoutingProviderMode) {
        _navigationState.update { it.copy(providerMode = mode, errorMessage = null) }
        viewModelScope.launch { preferences.setRoutingProviderMode(mode.name) }
    }

    fun searchAndStartNavigation(query: String) {
        refreshPermissions()
        if (!_permissions.value.hasLocation) {
            pendingLocationAction = { searchAndStartNavigation(query) }
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.LOCATION) }
            voiceCoordinator.speak(ClearPathPermission.LOCATION.rationale, autoListenAfter = false)
            return
        }

        startCompassObservation()
        viewModelScope.launch {
            _navigationState.update {
                it.copy(isSearching = true, destinationQuery = query, errorMessage = null)
            }
            voiceCoordinator.markProcessing("Finding walking route to $query...")
            try {
                val fix = locationTracker.getCurrentFix().getOrThrow()
                val candidates = routingClient.searchNearbyOrAddress(
                    query = query,
                    near = fix.point,
                    orsApiKey = _settings.value.orsApiKey
                ).getOrThrow()

                if (candidates.isEmpty()) {
                    throw IllegalStateException("No matching places found on OpenStreetMap for $query.")
                }

                _navigationState.update { it.copy(currentLocation = fix, searchResults = candidates) }
                startRouteToCandidate(candidates.first(), fix.point)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Could not find a walking route."
                haptics.onErrorAlert()
                _navigationState.update { it.copy(isSearching = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            }
        }
    }

    fun startRouteToCandidate(candidate: PlaceCandidate, explicitOrigin: com.example.navigation.GeoPoint? = null) {
        viewModelScope.launch {
            _navigationState.update { it.copy(isSearching = true, errorMessage = null) }
            val mode = _navigationState.value.providerMode
            val orsKey = _settings.value.orsApiKey
            val customUrl = _settings.value.customRoutingBaseUrl
            val needsProviderSetup = (mode == RoutingProviderMode.OPEN_ROUTE_SERVICE && orsKey.isBlank()) ||
                (mode == RoutingProviderMode.CUSTOM_OSRM && customUrl.isBlank())

            try {
                val origin = explicitOrigin ?: locationTracker.getCurrentFix().getOrThrow().point
                if (needsProviderSetup) {
                    startBearingGuidanceFallback(origin, candidate)
                    return@launch
                }

                val route = routingClient.fetchPedestrianRoute(
                    origin = origin,
                    destination = candidate.location,
                    destinationName = candidate.name,
                    mode = mode,
                    orsApiKey = orsKey,
                    customBaseUrl = customUrl
                ).getOrThrow()

                offRouteConsecutiveCount = 0
                lastAnnouncedStepIndex = 0
                val firstStep = route.steps.firstOrNull()?.instruction ?: "Follow the route"
                val intro = "Walking guidance to ${route.destinationName}, about ${route.totalDistanceMeters.toInt()} meters, ${route.estimatedMinutes} minutes. First: $firstStep. ${NavigationEngine.SAFETY_DISCLAIMER}"

                _navigationState.update {
                    it.copy(
                        isSearching = false,
                        isNavigating = true,
                        activeRoute = route,
                        activeBearingTarget = null,
                        currentStepIndex = 0,
                        distanceToNextTurnMeters = route.steps.firstOrNull()?.distanceMeters ?: 0.0,
                        isOffRoute = false,
                        statusAnnouncement = intro
                    )
                }
                haptics.onNavigationTurn()
                voiceCoordinator.speak(intro, autoListenAfter = false)
                beginLiveRouteTracking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Failed to build pedestrian route."
                haptics.onErrorAlert()
                _navigationState.update { it.copy(isSearching = false, errorMessage = err) }
                voiceCoordinator.speak(err, autoListenAfter = false)
            }
        }
    }

    private fun startBearingGuidanceFallback(
        origin: com.example.navigation.GeoPoint,
        candidate: PlaceCandidate
    ) {
        val dist = NavigationEngine.haversineMeters(origin, candidate.location)
        val brng = NavigationEngine.bearingDegrees(origin, candidate.location)
        val cardinal = NavigationEngine.cardinalDirection(brng)
        val relCue = NavigationEngine.relativeTurnCue(_navigationState.value.compassDegrees, brng)
        val msg = "No turn-by-turn routing key is configured, so free compass guidance is active. ${candidate.name} is ${dist.toInt()} meters $cardinal, $relCue. Tap Open in Phone Maps App for full street walking directions."

        _navigationState.update {
            it.copy(
                isSearching = false,
                isNavigating = true,
                activeRoute = null,
                activeBearingTarget = candidate,
                bearingTargetDistanceMeters = dist,
                bearingTargetDegrees = brng,
                statusAnnouncement = msg
            )
        }
        haptics.onNavigationTurn()
        voiceCoordinator.speak(msg, autoListenAfter = false)
        beginLiveBearingTracking(candidate)
    }

    private fun beginLiveBearingTracking(target: PlaceCandidate) {
        locationUpdatesJob?.cancel()
        locationUpdatesJob = viewModelScope.launch {
            locationTracker.locationUpdatesFlow().collect { snapshot ->
                val dist = NavigationEngine.haversineMeters(snapshot.point, target.location)
                val brng = NavigationEngine.bearingDegrees(snapshot.point, target.location)
                if (dist <= 18.0) {
                    haptics.onAnswerReady()
                    _navigationState.update {
                        it.copy(
                            currentLocation = snapshot,
                            isNavigating = false,
                            bearingTargetDistanceMeters = dist,
                            statusAnnouncement = "You are arriving near ${target.name}."
                        )
                    }
                    voiceCoordinator.speak("You are arriving near ${target.name}.")
                    locationUpdatesJob?.cancel()
                } else {
                    _navigationState.update {
                        it.copy(
                            currentLocation = snapshot,
                            bearingTargetDistanceMeters = dist,
                            bearingTargetDegrees = brng
                        )
                    }
                }
            }
        }
    }

    fun openInPhoneMaps(explicitQuery: String? = null) {
        val nav = _navigationState.value
        val targetPoint = nav.activeBearingTarget?.location
            ?: nav.activeRoute?.polyline?.lastOrNull()
            ?: nav.searchResults.firstOrNull()?.location
        val label = explicitQuery?.takeIf { it.isNotBlank() }
            ?: nav.activeBearingTarget?.name
            ?: nav.activeRoute?.destinationName
            ?: nav.destinationQuery.takeIf { it.isNotBlank() }
            ?: "Nearby place"

        voiceCoordinator.speak("Opening walking directions for $label in your phone's Maps app.")
        _mapsLaunchEvents.tryEmit(targetPoint to label)
    }

    fun checkPathAheadFromNavigation() {
        describeCurrentScene(
            question = "I am walking. Tell me briefly what is directly in front of me, any steps, doors, or obstacles, without guessing distances.",
            autoListen = false
        )
    }

    private fun beginLiveRouteTracking() {
        locationUpdatesJob?.cancel()
        locationUpdatesJob = viewModelScope.launch {
            locationTracker.locationUpdatesFlow().collect { snapshot ->
                val state = _navigationState.value
                val route = state.activeRoute ?: return@collect
                if (!state.isNavigating) return@collect

                val eval = NavigationEngine.evaluateProgress(
                    currentPoint = snapshot.point,
                    route = route,
                    currentStepIndex = state.currentStepIndex,
                    announcedStepIndex = lastAnnouncedStepIndex
                )

                if (eval.arrived) {
                    haptics.onAnswerReady()
                    _navigationState.update {
                        it.copy(
                            currentLocation = snapshot,
                            isNavigating = false,
                            statusAnnouncement = eval.spokenAnnouncement ?: "Arrived."
                        )
                    }
                    voiceCoordinator.speak(eval.spokenAnnouncement ?: "You are arriving at your destination.")
                    locationUpdatesJob?.cancel()
                    return@collect
                }

                if (eval.isOffRoute) {
                    offRouteConsecutiveCount++
                    if (offRouteConsecutiveCount >= 2 && !isRerouting) {
                        isRerouting = true
                        offRouteConsecutiveCount = 0
                        voiceCoordinator.speak("You appear off route. Calculating a new walking path.")
                        rerouteFrom(snapshot.point, route)
                    }
                } else {
                    offRouteConsecutiveCount = 0
                }

                if (eval.spokenAnnouncement != null && !eval.isOffRoute) {
                    lastAnnouncedStepIndex = eval.nextStepIndex
                    haptics.onNavigationTurn()
                    voiceCoordinator.speak(eval.spokenAnnouncement)
                }

                _navigationState.update {
                    it.copy(
                        currentLocation = snapshot,
                        currentStepIndex = eval.nextStepIndex,
                        distanceToNextTurnMeters = eval.distanceToNextTurnMeters,
                        offRouteMeters = eval.offRouteMeters,
                        isOffRoute = eval.isOffRoute,
                        statusAnnouncement = eval.spokenAnnouncement ?: state.statusAnnouncement
                    )
                }
            }
        }
    }

    private fun rerouteFrom(origin: com.example.navigation.GeoPoint, oldRoute: com.example.navigation.PedestrianRoute) {
        viewModelScope.launch {
            try {
                val dest = oldRoute.polyline.lastOrNull() ?: return@launch
                val newRoute = routingClient.fetchPedestrianRoute(
                    origin = origin,
                    destination = dest,
                    destinationName = oldRoute.destinationName,
                    mode = _navigationState.value.providerMode,
                    orsApiKey = _settings.value.orsApiKey,
                    customBaseUrl = _settings.value.customRoutingBaseUrl
                ).getOrThrow()
                lastAnnouncedStepIndex = 0
                val first = newRoute.steps.firstOrNull()?.instruction ?: "Continue straight"
                _navigationState.update {
                    it.copy(
                        activeRoute = newRoute,
                        currentStepIndex = 0,
                        isOffRoute = false,
                        statusAnnouncement = "Rerouted. $first"
                    )
                }
                voiceCoordinator.speak("Rerouted. $first")
            } catch (e: Exception) {
                voiceCoordinator.speak("Unable to reroute right now: ${e.localizedMessage}")
            } finally {
                isRerouting = false
            }
        }
    }

    fun announceNavigationStatus() {
        val state = _navigationState.value
        val route = state.activeRoute
        val bearingTarget = state.activeBearingTarget
        if (state.isNavigating && bearingTarget != null) {
            val cardinal = NavigationEngine.cardinalDirection(state.bearingTargetDegrees)
            val rel = NavigationEngine.relativeTurnCue(state.compassDegrees, state.bearingTargetDegrees)
            voiceCoordinator.speak(
                "${bearingTarget.name} is about ${state.bearingTargetDistanceMeters.toInt()} meters $cardinal, $rel. You are facing ${state.compassCardinal}.",
                autoListenAfter = true
            )
            return
        }
        if (!state.isNavigating || route == null) {
            voiceCoordinator.speak(
                "No walking route is active. Ask Take me to a place, or tap Search Destination.",
                autoListenAfter = true
            )
            return
        }
        val step = route.steps.getOrNull(state.currentStepIndex)
        val onRouteMsg = if (state.isOffRoute) {
            "You appear about ${state.offRouteMeters.toInt()} meters off the planned route."
        } else {
            "You are on the route, facing ${state.compassCardinal}."
        }
        val turnMsg = if (step != null) {
            "Next instruction in about ${state.distanceToNextTurnMeters.toInt()} meters: ${step.instruction}."
        } else ""
        voiceCoordinator.speak("$onRouteMsg $turnMsg".trim(), autoListenAfter = true)
    }

    fun stopNavigation() {
        locationUpdatesJob?.cancel()
        _navigationState.update {
            it.copy(
                isNavigating = false,
                activeRoute = null,
                activeBearingTarget = null,
                isOffRoute = false,
                statusAnnouncement = "Walking guidance stopped."
            )
        }
        voiceCoordinator.speak("Navigation stopped.", autoListenAfter = false)
    }

    // --- Settings updates ---

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch { preferences.setSpeechRate(rate) }
    }

    fun setContinuousConversation(enabled: Boolean) {
        viewModelScope.launch { preferences.setContinuousConversation(enabled) }
    }

    fun setSceneWatchEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSceneWatchEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setHapticsEnabled(enabled) }
    }

    fun setShowCameraPreview(show: Boolean) {
        viewModelScope.launch { preferences.setShowCameraPreview(show) }
    }

    fun setPreferOnDevice(enabled: Boolean) {
        _assistState.update { it.copy(preferOnDeviceSingleTurn = enabled) }
        viewModelScope.launch { preferences.setPreferOnDeviceAi(enabled) }
    }

    fun setWakePhraseModeEnabled(enabled: Boolean) {
        refreshPermissions()
        if (enabled && !_permissions.value.hasMicrophone) {
            pendingWakePhraseAfterMicPermission = true
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.MICROPHONE) }
            voiceCoordinator.speak(ClearPathPermission.MICROPHONE.rationale, autoListenAfter = false)
            return
        }
        viewModelScope.launch {
            preferences.setWakePhraseModeEnabled(enabled)
            if (enabled) {
                if (!_permissions.value.hasNotifications) {
                    _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.NOTIFICATIONS) }
                }
                runCatching { WakePhraseService.start(appContext) }
            } else {
                WakePhraseService.stop(appContext)
            }
        }
    }

    fun setAutoListenOnOpen(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoListenOnOpen(enabled) }
    }

    fun setPreferredVoiceName(name: String) {
        viewModelScope.launch { preferences.setPreferredVoice(name) }
    }

    fun setPreferredLiveVoice(name: String) {
        viewModelScope.launch { preferences.setPreferredLiveVoice(name) }
    }

    fun saveRoutingConfiguration(orsKey: String, customUrl: String) {
        viewModelScope.launch { preferences.setRoutingConfig(orsKey, customUrl) }
    }

    // --- Live Duplex Assist Sessions (Gemini Live API) ---

    fun startLiveAssistSession(initialUserRequest: String? = null) {
        refreshPermissions()
        if (!_permissions.value.hasMicrophone) {
            pendingVoiceAfterMicPermission = true
            _assistState.update { it.copy(pendingPermissionPrompt = ClearPathPermission.MICROPHONE) }
            voiceCoordinator.speak(ClearPathPermission.MICROPHONE.rationale, autoListenAfter = false)
            return
        }

        WakePhraseService.stop(appContext)
        voiceCoordinator.stopSpeaking()
        voiceCoordinator.stopListening()
        _assistState.update { it.copy(errorMessage = null) }

        val greetingPrompt = if (!initialUserRequest.isNullOrBlank()) {
            "The user just triggered ClearPath with this request: \"$initialUserRequest\". Fulfill it immediately using your tools if needed and reply concisely."
        } else {
            "ClearPath Live is now active. Greet the user in one short calm sentence letting them know you are listening and they can interrupt anytime."
        }

        geminiLive.startSession(
            voiceName = _settings.value.preferredLiveVoice,
            systemInstruction = aiRepository.liveAssistSystemInstruction,
            tools = aiRepository.assistToolsList,
            initialPrompt = greetingPrompt,
            reuseSessionHandle = true,
            onToolCall = { call -> executeLiveAssistToolCall(call) },
            onConnectionFailed = { err ->
                viewModelScope.launch(Dispatchers.Main) {
                    _assistState.update {
                        it.copy(errorMessage = "Live audio unavailable ($err). Using offline turn-based voice.")
                    }
                    if (!initialUserRequest.isNullOrBlank()) {
                        handleVoiceTranscript(initialUserRequest)
                    } else {
                        voiceCoordinator.startSession("ClearPath offline voice ready.")
                    }
                }
            }
        )
    }

    fun stopLiveAssistSession() {
        cameraController.setLiveStreamEnabled(false)
        _isLiveCameraShareActive.value = false
        _isFullScreenCameraView.value = false
        geminiLive.stopSession()
        if (_settings.value.hasCompletedOnboarding &&
            _settings.value.wakePhraseModeEnabled &&
            _permissions.value.hasMicrophone
        ) {
            runCatching { WakePhraseService.start(appContext) }
        }
    }

    private fun bindCameraPreviewIfPossible(useBackCamera: Boolean) {
        lastBoundLifecycleOwner?.let { owner ->
            bindCameraPreview(owner, lastBoundPreviewView, useBackCamera)
        }
    }

    private suspend fun executeLiveAssistToolCall(call: FunctionCallPart): FunctionResponsePart {
        val result = executeAiTool(call.name, call.args)
        return FunctionResponsePart(call.name, result)
    }

    // --- First-Time Setup (Live Duplex Voice-First & Manual) ---

    fun beginVoiceOnboarding() {
        refreshPermissions()
        val snap = _permissions.value
        val voices = voiceCoordinator.state.value.availableVoices.take(12)
        _onboardingState.update {
            it.copy(
                mode = SetupMode.VOICE_FIRST,
                voiceStep = VoiceSetupStep.WELCOME_AND_CONSENT,
                voiceCandidates = voices,
                currentVoiceIndex = 0,
                isPlayingShowcase = false
            )
        }

        if (snap.hasMicrophone) {
            startLiveOnboardingSession()
            return
        }

        geminiLive.stopSession()
        val prompt = "Welcome to ClearPath, your voice-first assistive companion. " +
            "Grant Microphone access below to begin hands-free live voice setup, or choose Manual Setup or Skip at the bottom."
        _onboardingState.update {
            it.copy(
                currentPromptText = prompt,
                statusHint = "Tap Grant Access below to enable Microphone & live conversation."
            )
        }
        voiceCoordinator.speak(prompt, autoListenAfter = false)
    }

    private fun startLiveOnboardingSession() {
        refreshPermissions()
        val snap = _permissions.value
        WakePhraseService.stop(appContext)
        voiceCoordinator.stopSpeaking()
        voiceCoordinator.stopListening()

        val currentLiveVoice = _settings.value.preferredLiveVoice
        val initialPrompt = buildString {
            append("Start first-time voice setup now. ")
            append("Current voice: $currentLiveVoice. ")
            append("Permissions status: Microphone=granted, Camera=${snap.hasCamera}, Location=${snap.hasLocation}. ")
            if (snap.hasCamera && snap.hasLocation) {
                append("Camera and Location are already granted. Begin right at Step 2: greet them warmly in 1 sentence, and ask if they like this $currentLiveVoice voice or want to try another or list available voices.")
            } else {
                append("Begin at Step 1: greet them warmly in 1 sentence and ask if they consent to Camera access for describing surroundings and Location access for walking guidance.")
            }
        }

        _onboardingState.update {
            it.copy(
                mode = SetupMode.VOICE_FIRST,
                currentPromptText = "Starting live ClearPath voice setup...",
                statusHint = "Connecting real-time voice · Speak anytime to interrupt"
            )
        }

        geminiLive.startSession(
            voiceName = currentLiveVoice,
            systemInstruction = aiRepository.liveOnboardingSystemInstruction,
            tools = aiRepository.onboardingToolsList,
            initialPrompt = initialPrompt,
            reuseSessionHandle = false,
            onToolCall = { call -> handleLiveOnboardingToolCall(call) },
            onConnectionFailed = { err ->
                viewModelScope.launch(Dispatchers.Main) {
                    fallbackOfflineVoiceOnboarding(err)
                }
            }
        )
    }

    private suspend fun handleLiveOnboardingToolCall(call: FunctionCallPart): FunctionResponsePart {
        val result: JsonObject = when (call.name) {
            "request_consent_permissions" -> {
                withContext(Dispatchers.Main) {
                    _onboardingState.update { it.copy(voiceStep = VoiceSetupStep.WELCOME_AND_CONSENT) }
                    requestAllOnboardingConsentPermissions()
                }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("prompted"),
                        "message" to JsonPrimitive("Permission dialogs shown for Camera, Location, and Notifications. Ask the user to tap Allow on screen.")
                    )
                )
            }

            "list_live_voices" -> {
                withContext(Dispatchers.Main) {
                    _onboardingState.update { it.copy(voiceStep = VoiceSetupStep.CHOOSE_VOICE) }
                }
                val active = _settings.value.preferredLiveVoice
                val items = LiveVoiceCatalog.voices.map { v ->
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(v.name),
                            "style" to JsonPrimitive(v.style),
                            "gender" to JsonPrimitive(v.gender)
                        )
                    )
                }
                JsonObject(
                    mapOf(
                        "active_voice" to JsonPrimitive(active),
                        "voices" to JsonArray(items)
                    )
                )
            }

            "switch_live_voice" -> {
                val requested = call.args["voice_name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val currentIdx = LiveVoiceCatalog.voices.indexOfFirst {
                    it.name.equals(_settings.value.preferredLiveVoice, ignoreCase = true)
                }.coerceAtLeast(0)
                val targetVoice = if (requested.isBlank() ||
                    requested.equals("next", ignoreCase = true) ||
                    requested.equals("another", ignoreCase = true)
                ) {
                    LiveVoiceCatalog.voices[(currentIdx + 1) % LiveVoiceCatalog.voices.size]
                } else {
                    LiveVoiceCatalog.findByName(requested)
                        ?: LiveVoiceCatalog.voices[(currentIdx + 1) % LiveVoiceCatalog.voices.size]
                }

                preferences.setPreferredLiveVoice(targetVoice.name)
                withContext(Dispatchers.Main) {
                    _onboardingState.update {
                        it.copy(
                            voiceStep = VoiceSetupStep.CHOOSE_VOICE,
                            currentPromptText = "Switching live voice to ${targetVoice.spokenLabel}...",
                            statusHint = "Voice: ${targetVoice.name} · Speak anytime to interrupt"
                        )
                    }
                }
                viewModelScope.launch {
                    delay(180)
                    geminiLive.switchVoice(
                        newVoiceName = targetVoice.name,
                        announcementPrompt = "You are now speaking in the ${targetVoice.name} voice (${targetVoice.style}). Say hello in one brief warm sentence and ask if they want to keep ${targetVoice.name}, try another voice, or hear what voices are available."
                    )
                }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("switching"),
                        "new_voice" to JsonPrimitive(targetVoice.spokenLabel)
                    )
                )
            }

            "play_voice_samples" -> {
                val active = _settings.value.preferredLiveVoice
                val summary = LiveVoiceCatalog.voices.joinToString("; ") { "${it.name} (${it.style}, ${it.gender})" }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "active_voice" to JsonPrimitive(active),
                        "available_voices_summary" to JsonPrimitive(summary),
                        "instruction" to JsonPrimitive("Read a few voice options concisely and offer to call switch_live_voice immediately whenever the user names one or says next.")
                    )
                )
            }

            "update_setting" -> {
                withContext(Dispatchers.Main) {
                    _onboardingState.update {
                        val nextStep = if (call.args["setting_name"]?.jsonPrimitive?.contentOrNull == "wake_phrase_mode") {
                            VoiceSetupStep.WAKE_PHRASE_CHOICE
                        } else {
                            VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS
                        }
                        it.copy(voiceStep = nextStep)
                    }
                }
                applyToolSettingUpdate(call.args)
            }

            "complete_onboarding" -> {
                withContext(Dispatchers.Main) {
                    completeOnboarding(keepLiveActive = true)
                }
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("completed"),
                        "message" to JsonPrimitive("Onboarding finished. ClearPath is ready.")
                    )
                )
            }

            "switch_to_manual_setup" -> {
                withContext(Dispatchers.Main) {
                    switchToManualSetup()
                }
                JsonObject(mapOf("status" to JsonPrimitive("switched_to_manual")))
            }

            else -> JsonObject(mapOf("status" to JsonPrimitive("error"), "message" to JsonPrimitive("Unknown onboarding tool: ${call.name}")))
        }
        return FunctionResponsePart(call.name, result)
    }

    private fun fallbackOfflineVoiceOnboarding(reason: String) {
        refreshPermissions()
        val snap = _permissions.value
        val ob = _onboardingState.value
        if (ob.hasCompletedOnboarding || ob.mode != SetupMode.VOICE_FIRST) return
        _onboardingState.update {
            it.copy(
                statusHint = "Offline voice fallback (${reason.take(40)})."
            )
        }
        if (snap.hasMicrophone && snap.hasCamera && snap.hasLocation) {
            advanceVoiceOnboardingTo(VoiceSetupStep.CHOOSE_VOICE)
        } else {
            val prompt = "Welcome to ClearPath, your voice-first assistive companion. " +
                "Do you consent to granting access to your Camera to describe surroundings and read text, and Location for walking directions?"
            _onboardingState.update {
                it.copy(
                    currentPromptText = prompt,
                    statusHint = "Say \"Yes, I consent\" or \"Not now\"."
                )
            }
            voiceCoordinator.speak(prompt, autoListenAfter = snap.hasMicrophone)
        }
    }

    fun requestAllOnboardingConsentPermissions() {
        refreshPermissions()
        val snap = _permissions.value
        pendingOnboardingPermissionQueue.clear()
        if (!snap.hasMicrophone) pendingOnboardingPermissionQueue.addLast(ClearPathPermission.MICROPHONE)
        if (!snap.hasCamera) pendingOnboardingPermissionQueue.addLast(ClearPathPermission.CAMERA)
        if (!snap.hasLocation) pendingOnboardingPermissionQueue.addLast(ClearPathPermission.LOCATION)
        if (!snap.hasNotifications) pendingOnboardingPermissionQueue.addLast(ClearPathPermission.NOTIFICATIONS)

        val first = pendingOnboardingPermissionQueue.removeFirstOrNull()
        if (first == null) {
            onOnboardingPermissionBatchFinished()
            return
        }
        _onboardingState.update { it.copy(awaitingPermissionBatch = true) }
        _assistState.update { it.copy(pendingPermissionPrompt = first) }
    }

    fun requestSingleOnboardingPermission(permission: ClearPathPermission) {
        pendingOnboardingPermissionQueue.clear()
        _onboardingState.update { it.copy(awaitingPermissionBatch = false) }
        _assistState.update { it.copy(pendingPermissionPrompt = permission) }
    }

    private fun onOnboardingPermissionBatchFinished() {
        refreshPermissions()
        val snap = _permissions.value
        val ob = _onboardingState.value
        if (ob.mode == SetupMode.VOICE_FIRST && ob.voiceStep == VoiceSetupStep.WELCOME_AND_CONSENT) {
            if (geminiLive.state.value.isActive) {
                geminiLive.sendTextTurn(
                    "Permission dialogs completed: Camera=${snap.hasCamera}, Location=${snap.hasLocation}, Notifications=${snap.hasNotifications}. Acknowledge briefly and move to Step 2 voice selection."
                )
                _onboardingState.update { it.copy(voiceStep = VoiceSetupStep.CHOOSE_VOICE) }
                return
            }
            if (snap.hasMicrophone) {
                startLiveOnboardingSession()
                return
            }
            val grantedList = buildList {
                if (snap.hasMicrophone) add("Microphone")
                if (snap.hasCamera) add("Camera")
                if (snap.hasLocation) add("Location")
            }
            val ack = if (grantedList.isEmpty()) {
                "No permissions were granted yet. ClearPath will ask again when you use each feature. Now let's choose a voice."
            } else {
                "Thanks, ${grantedList.joinToString(", ")} access is ready. Now let's choose a voice."
            }
            voiceCoordinator.speak(ack, autoListenAfter = false) {
                advanceVoiceOnboardingTo(VoiceSetupStep.CHOOSE_VOICE)
            }
        }
    }

    private fun advanceVoiceOnboardingTo(step: VoiceSetupStep) {
        refreshPermissions()
        val voices = voiceCoordinator.state.value.availableVoices.take(12)
        _onboardingState.update {
            it.copy(
                voiceStep = step,
                voiceCandidates = voices,
                isPlayingShowcase = false
            )
        }
        when (step) {
            VoiceSetupStep.WELCOME_AND_CONSENT -> beginVoiceOnboarding()
            VoiceSetupStep.CHOOSE_VOICE -> {
                if (voices.isEmpty()) {
                    val prompt = "Your device is using the default voice. Let's check your speed and feedback preferences next."
                    _onboardingState.update { it.copy(currentPromptText = prompt) }
                    voiceCoordinator.speak(prompt, autoListenAfter = false) {
                        advanceVoiceOnboardingTo(VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS)
                    }
                } else {
                    playVoiceCandidateSample(index = 0)
                }
            }
            VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS -> {
                val speed = "%.1f".format(_settings.value.speechRate)
                val prompt = "Your speaking speed is $speed times, with continuous listening and vibration cues turned on. Say keep defaults, speak faster, speak slower, or turn off vibration."
                _onboardingState.update {
                    it.copy(
                        currentPromptText = prompt,
                        statusHint = "Say \"Keep defaults\", \"Faster\", \"Slower\", or \"Turn off vibration\"."
                    )
                }
                voiceCoordinator.speak(prompt, autoListenAfter = _permissions.value.hasMicrophone)
            }
            VoiceSetupStep.WAKE_PHRASE_CHOICE -> {
                val prompt = "Would you like to enable hands-free Wake-Phrase Mode so you can say ClearPath activate while the notification is active?"
                _onboardingState.update {
                    it.copy(
                        currentPromptText = prompt,
                        statusHint = "Say \"Yes\" to enable Wake-Phrase Mode, or \"No\" to skip."
                    )
                }
                voiceCoordinator.speak(prompt, autoListenAfter = _permissions.value.hasMicrophone)
            }
            VoiceSetupStep.COMPLETED -> completeOnboarding()
        }
    }

    private fun playVoiceCandidateSample(index: Int) {
        val candidates = _onboardingState.value.voiceCandidates
            .ifEmpty { voiceCoordinator.state.value.availableVoices.take(12) }
        if (candidates.isEmpty()) return
        val safeIdx = ((index % candidates.size) + candidates.size) % candidates.size
        val candidate = candidates[safeIdx]
        val prompt = if (candidates.size == 1) {
            "Your device currently has one voice installed: ${candidate.displayLabel}. Say keep to continue, or faster or slower to adjust speed."
        } else {
            "Voice ${safeIdx + 1} of ${candidates.size}: ${candidate.displayLabel}. Hello, this is how ClearPath will guide you. Do you want to keep this voice, try another voice, or ask me to list all available voices?"
        }
        _onboardingState.update {
            it.copy(
                voiceCandidates = candidates,
                currentVoiceIndex = safeIdx,
                currentPromptText = prompt,
                isPlayingShowcase = false,
                statusHint = "Say \"Keep\", \"Another\", or \"List available voices\"."
            )
        }
        voiceCoordinator.speakWithSpecificVoice(
            voiceName = candidate.name,
            text = prompt,
            autoListenAfter = _permissions.value.hasMicrophone
        )
    }

    private fun playAllVoicesShowcase() {
        val candidates = _onboardingState.value.voiceCandidates
            .ifEmpty { voiceCoordinator.state.value.availableVoices.take(12) }
            .take(6)
        if (candidates.isEmpty()) return
        val samples = candidates.mapIndexed { idx, voice ->
            voice.name to "Voice ${idx + 1}, ${voice.displayLabel}: Hello, I am ready to guide you with ClearPath."
        }
        val intro = "Playing ${candidates.size} available voices on your device."
        val outro = "Which voice number would you like to keep, from 1 to ${candidates.size}? Or say try another."
        _onboardingState.update {
            it.copy(
                isPlayingShowcase = true,
                currentPromptText = "$intro Listening to samples 1 through ${candidates.size}...",
                statusHint = "Tap the center circle anytime to interrupt and pick a voice number."
            )
        }
        voiceCoordinator.speakVoiceShowcase(
            introText = intro,
            samples = samples,
            outroText = outro,
            autoListenAfter = _permissions.value.hasMicrophone,
            onFinished = {
                _onboardingState.update {
                    it.copy(
                        isPlayingShowcase = false,
                        currentPromptText = outro,
                        statusHint = "Say \"Voice 1\" through \"Voice ${candidates.size}\", or \"Keep\"."
                    )
                }
            }
        )
    }

    private fun handleOnboardingVoiceTranscript(transcript: String) {
        val ob = _onboardingState.value
        val reply = OnboardingVoiceParser.parse(transcript, ob.voiceStep)

        when (reply) {
            is OnboardingVoiceReply.SwitchToManual -> {
                switchToManualSetup()
                return
            }
            is OnboardingVoiceReply.SkipSetup -> {
                skipOnboarding()
                return
            }
            is OnboardingVoiceReply.RepeatPrompt -> {
                if (ob.voiceStep == VoiceSetupStep.CHOOSE_VOICE) {
                    playVoiceCandidateSample(ob.currentVoiceIndex)
                } else {
                    advanceVoiceOnboardingTo(ob.voiceStep)
                }
                return
            }
            is OnboardingVoiceReply.FasterSpeech -> {
                val nextRate = (_settings.value.speechRate + 0.2f).coerceAtMost(1.8f)
                viewModelScope.launch { preferences.setSpeechRate(nextRate) }
                if (ob.voiceStep == VoiceSetupStep.CHOOSE_VOICE) {
                    playVoiceCandidateSample(ob.currentVoiceIndex)
                } else {
                    voiceCoordinator.speak(
                        "Speed set to ${"%.1f".format(nextRate)} times. Would you like to keep this or change anything else?",
                        autoListenAfter = true
                    )
                }
                return
            }
            is OnboardingVoiceReply.SlowerSpeech -> {
                val nextRate = (_settings.value.speechRate - 0.2f).coerceAtLeast(0.6f)
                viewModelScope.launch { preferences.setSpeechRate(nextRate) }
                if (ob.voiceStep == VoiceSetupStep.CHOOSE_VOICE) {
                    playVoiceCandidateSample(ob.currentVoiceIndex)
                } else {
                    voiceCoordinator.speak(
                        "Speed set to ${"%.1f".format(nextRate)} times. Would you like to keep this or change anything else?",
                        autoListenAfter = true
                    )
                }
                return
            }
            else -> Unit
        }

        when (ob.voiceStep) {
            VoiceSetupStep.WELCOME_AND_CONSENT -> when (reply) {
                is OnboardingVoiceReply.Affirmative -> requestAllOnboardingConsentPermissions()
                is OnboardingVoiceReply.Negative -> {
                    voiceCoordinator.speak(
                        "Okay, ClearPath will ask for permissions later when you use those features. Next, let's pick a voice.",
                        autoListenAfter = false
                    ) {
                        advanceVoiceOnboardingTo(VoiceSetupStep.CHOOSE_VOICE)
                    }
                }
                else -> {
                    val prompt = "Please say yes to grant Camera and Location access, or say no to decide later."
                    _onboardingState.update { it.copy(currentPromptText = prompt) }
                    voiceCoordinator.speak(prompt, autoListenAfter = true)
                }
            }

            VoiceSetupStep.CHOOSE_VOICE -> {
                val candidates = ob.voiceCandidates
                when (reply) {
                    is OnboardingVoiceReply.ListAndPlayVoices -> playAllVoicesShowcase()
                    is OnboardingVoiceReply.NextOrAnother -> playVoiceCandidateSample(ob.currentVoiceIndex + 1)
                    is OnboardingVoiceReply.Previous -> playVoiceCandidateSample(ob.currentVoiceIndex - 1)
                    is OnboardingVoiceReply.SelectVoiceNumber -> {
                        val idx = reply.oneBasedIndex - 1
                        if (idx in candidates.indices) {
                            val chosen = candidates[idx]
                            viewModelScope.launch { preferences.setPreferredVoice(chosen.name) }
                            _onboardingState.update { it.copy(currentVoiceIndex = idx) }
                            voiceCoordinator.speakWithSpecificVoice(
                                voiceName = chosen.name,
                                text = "Selected voice ${reply.oneBasedIndex}, ${chosen.displayLabel}.",
                                autoListenAfter = false
                            ) {
                                advanceVoiceOnboardingTo(VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS)
                            }
                        } else {
                            voiceCoordinator.speak(
                                "Please pick a voice number between 1 and ${candidates.size}, or say another.",
                                autoListenAfter = true
                            )
                        }
                    }
                    is OnboardingVoiceReply.SelectVoiceByQuery -> {
                        val matchIdx = candidates.indexOfFirst {
                            it.displayLabel.contains(reply.query, ignoreCase = true) ||
                                it.localeTag.contains(reply.query, ignoreCase = true)
                        }
                        if (matchIdx >= 0) {
                            playVoiceCandidateSample(matchIdx)
                        } else {
                            voiceCoordinator.speak(
                                "I could not match that voice name. Say another, or list available voices.",
                                autoListenAfter = true
                            )
                        }
                    }
                    is OnboardingVoiceReply.Affirmative -> {
                        val chosen = candidates.getOrNull(ob.currentVoiceIndex)
                        if (chosen != null) {
                            viewModelScope.launch { preferences.setPreferredVoice(chosen.name) }
                            voiceCoordinator.speakWithSpecificVoice(
                                voiceName = chosen.name,
                                text = "Great, keeping this voice.",
                                autoListenAfter = false
                            ) {
                                advanceVoiceOnboardingTo(VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS)
                            }
                        } else {
                            advanceVoiceOnboardingTo(VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS)
                        }
                    }
                    else -> {
                        val prompt = "Say keep to use this voice, another to hear the next one, or list available voices to hear all of them."
                        _onboardingState.update { it.copy(currentPromptText = prompt) }
                        voiceCoordinator.speak(prompt, autoListenAfter = true)
                    }
                }
            }

            VoiceSetupStep.PREFERENCES_SPEED_AND_HAPTICS -> when (reply) {
                is OnboardingVoiceReply.DisableHaptics -> {
                    setHapticsEnabled(false)
                    voiceCoordinator.speak("Vibration cues turned off.", autoListenAfter = false) {
                        advanceVoiceOnboardingTo(VoiceSetupStep.WAKE_PHRASE_CHOICE)
                    }
                }
                is OnboardingVoiceReply.EnableHaptics -> {
                    setHapticsEnabled(true)
                    voiceCoordinator.speak("Vibration cues kept on.", autoListenAfter = false) {
                        advanceVoiceOnboardingTo(VoiceSetupStep.WAKE_PHRASE_CHOICE)
                    }
                }
                is OnboardingVoiceReply.DisableContinuousListening -> {
                    setContinuousConversation(false)
                    voiceCoordinator.speak("Continuous listening turned off.", autoListenAfter = false) {
                        advanceVoiceOnboardingTo(VoiceSetupStep.WAKE_PHRASE_CHOICE)
                    }
                }
                is OnboardingVoiceReply.Affirmative,
                is OnboardingVoiceReply.Negative,
                is OnboardingVoiceReply.NextOrAnother -> {
                    advanceVoiceOnboardingTo(VoiceSetupStep.WAKE_PHRASE_CHOICE)
                }
                else -> {
                    voiceCoordinator.speak(
                        "Say keep defaults, faster, slower, or turn off vibration.",
                        autoListenAfter = true
                    )
                }
            }

            VoiceSetupStep.WAKE_PHRASE_CHOICE -> when (reply) {
                is OnboardingVoiceReply.Affirmative -> {
                    setWakePhraseModeEnabled(true)
                    completeOnboarding()
                }
                is OnboardingVoiceReply.Negative,
                is OnboardingVoiceReply.NextOrAnother -> {
                    setWakePhraseModeEnabled(false)
                    completeOnboarding()
                }
                else -> {
                    voiceCoordinator.speak(
                        "Say yes to turn on ClearPath activate wake-phrase mode, or no to skip it.",
                        autoListenAfter = true
                    )
                }
            }

            VoiceSetupStep.COMPLETED -> completeOnboarding()
        }
    }

    fun switchToManualSetup() {
        geminiLive.stopSession()
        voiceCoordinator.stopListening()
        voiceCoordinator.stopSpeaking()
        _onboardingState.update {
            it.copy(
                mode = SetupMode.MANUAL,
                manualStep = ManualSetupStep.CONSENT_PERMISSIONS,
                isPlayingShowcase = false,
                voiceCandidates = voiceCoordinator.state.value.availableVoices.take(15)
            )
        }
    }

    fun switchToVoiceSetup() {
        _onboardingState.update {
            it.copy(
                mode = SetupMode.VOICE_FIRST,
                isPlayingShowcase = false
            )
        }
        beginVoiceOnboarding()
    }

    fun setManualSetupStep(step: ManualSetupStep) {
        _onboardingState.update { it.copy(manualStep = step) }
    }

    fun previewAndSelectLiveVoiceInManualSetup(liveVoice: LiveVoiceOption) {
        viewModelScope.launch { preferences.setPreferredLiveVoice(liveVoice.name) }
        voiceCoordinator.speak(
            "Selected ${liveVoice.name}, a ${liveVoice.style} live voice for ClearPath.",
            autoListenAfter = false
        )
    }

    fun previewAndSelectVoiceInManualSetup(voice: VoiceOption) {
        viewModelScope.launch { preferences.setPreferredVoice(voice.name) }
        voiceCoordinator.speakWithSpecificVoice(
            voiceName = voice.name,
            text = "Hello, this is ClearPath speaking with ${voice.displayLabel}.",
            autoListenAfter = false
        )
    }

    fun skipOnboarding() {
        geminiLive.stopSession()
        voiceCoordinator.stopListening()
        viewModelScope.launch {
            preferences.markWelcomeSpoken()
            preferences.setHasCompletedOnboarding(true)
        }
        _onboardingState.update {
            it.copy(
                hasCompletedOnboarding = true,
                voiceStep = VoiceSetupStep.COMPLETED,
                isPlayingShowcase = false
            )
        }
        voiceCoordinator.speak(
            "Skipped setup. Using default settings. Tap Listen anytime.",
            autoListenAfter = false
        )
    }

    fun completeOnboarding(keepLiveActive: Boolean = false) {
        if (!keepLiveActive) {
            geminiLive.stopSession()
        }
        voiceCoordinator.stopListening()
        viewModelScope.launch {
            preferences.markWelcomeSpoken()
            preferences.setHasCompletedOnboarding(true)
        }
        _onboardingState.update {
            it.copy(
                hasCompletedOnboarding = true,
                voiceStep = VoiceSetupStep.COMPLETED,
                isPlayingShowcase = false
            )
        }
        if (keepLiveActive && _permissions.value.hasMicrophone) {
            startLiveAssistSession(
                initialUserRequest = "Setup is complete. Welcome the user briefly in 1 sentence and ask how you can help them first."
            )
        } else {
            voiceCoordinator.speak(
                "Setup complete. ClearPath is ready. Tap Listen anytime for a live hands-free conversation.",
                autoListenAfter = false
            )
        }
    }

    fun rerunOnboardingFromSettings() {
        geminiLive.stopSession()
        viewModelScope.launch {
            preferences.setHasCompletedOnboarding(false)
        }
        _activeTab.value = ClearPathTab.ASSIST
        _onboardingState.update {
            it.copy(
                hasCompletedOnboarding = false,
                mode = SetupMode.VOICE_FIRST,
                voiceStep = VoiceSetupStep.WELCOME_AND_CONSENT,
                manualStep = ManualSetupStep.CONSENT_PERMISSIONS
            )
        }
        beginVoiceOnboarding()
    }

    override fun onCleared() {
        super.onCleared()
        waitingPermissionRequest = null
        pendingOnboardingPermissionQueue.clear()
        locationUpdatesJob?.cancel()
        compassJob?.cancel()
        cameraController.release()
        geminiLive.release()
        voiceCoordinator.release()
    }
}
