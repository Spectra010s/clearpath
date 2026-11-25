package com.example.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.permissions.ClearPathPermission
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.OnWarmAmber
import com.example.ui.theme.WarmAmber
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ClearPathApp(
    isWideScreen: Boolean,
    viewModel: ClearPathViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val assistState by viewModel.assistState.collectAsStateWithLifecycle()
    val translationState by viewModel.translationState.collectAsStateWithLifecycle()
    val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val isCameraReady by viewModel.isCameraReady.collectAsStateWithLifecycle()

    // Keep ImageCapture & SceneWatch bound regardless of which visual tab is currently open.
    DisposableEffect(permissions.hasCamera, activeTab, settings.showCameraPreview, lifecycleOwner) {
        if (permissions.hasCamera && (activeTab != ClearPathTab.ASSIST || !settings.showCameraPreview)) {
            viewModel.bindCameraPreview(lifecycleOwner, null)
        }
        onDispose { }
    }

    LaunchedEffect(Unit) {
        viewModel.mapsLaunchEvents.collectLatest { (point, label) ->
            val encodedLabel = Uri.encode(label)
            val navUri = if (point != null) {
                Uri.parse("google.navigation:q=${point.latitude},${point.longitude}&mode=w")
            } else {
                Uri.parse("google.navigation:q=$encodedLabel&mode=w")
            }
            val geoUri = if (point != null) {
                Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($encodedLabel)")
            } else {
                Uri.parse("geo:0,0?q=$encodedLabel")
            }

            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, navUri))
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                } catch (_: ActivityNotFoundException) {
                    viewModel.voiceCoordinator.speak(
                        "No Maps app was found on this device to open external directions."
                    )
                }
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(ClearPathPermission.MICROPHONE, granted)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(ClearPathPermission.CAMERA, granted)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onPermissionResult(ClearPathPermission.LOCATION, granted)
    }

    if (activeTab != ClearPathTab.ASSIST) {
        BackHandler {
            viewModel.selectTab(ClearPathTab.ASSIST)
        }
    }

    assistState.pendingPermissionPrompt?.let { perm ->
        PermissionRationaleDialog(
            permission = perm,
            onConfirm = {
                viewModel.dismissPermissionPrompt()
                when (perm) {
                    ClearPathPermission.MICROPHONE ->
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    ClearPathPermission.CAMERA ->
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    ClearPathPermission.LOCATION ->
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                }
            },
            onDismiss = { viewModel.dismissPermissionPrompt() }
        )
    }

    Scaffold(
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!isWideScreen) {
                NavigationBar(
                    containerColor = ObsidianSurface,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    ClearPathTab.entries.forEach { tab ->
                        val selected = tab == activeTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { viewModel.selectTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = tabIcon(tab, selected),
                                    contentDescription = null
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = OnWarmAmber,
                                selectedTextColor = WarmAmber,
                                indicatorColor = WarmAmber,
                                unselectedIconColor = HighContrastSecondaryText,
                                unselectedTextColor = HighContrastSecondaryText
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("nav_tab_${tab.route}")
                                .semantics {
                                    contentDescription = "${tab.label} tab${if (selected) ", selected" else ""}"
                                }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isWideScreen) {
                NavigationRail(
                    containerColor = ObsidianSurface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    ClearPathTab.entries.forEach { tab ->
                        val selected = tab == activeTab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.selectTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = tabIcon(tab, selected),
                                    contentDescription = null
                                )
                            },
                            label = { Text(tab.label, color = HighContrastText) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("rail_tab_${tab.route}")
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                val contentModifier = Modifier.widthIn(max = 680.dp)
                when (activeTab) {
                    ClearPathTab.ASSIST -> AssistScreen(
                        voiceState = voiceState,
                        assistState = assistState,
                        settings = settings,
                        permissions = permissions,
                        isCameraReady = isCameraReady,
                        onBindCamera = viewModel::bindCameraPreview,
                        onPrimaryVoiceTap = viewModel::onPrimaryVoiceButtonPressed,
                        onEndSession = viewModel::stopSpeakingOrSession,
                        onRepeatLast = viewModel::repeatLastSpoken,
                        onDescribeScene = {
                            viewModel.describeCurrentScene(
                                "Describe what is directly in front of me safely and concisely.",
                                autoListen = false
                            )
                        },
                        onReadText = { viewModel.readTextFromCamera() },
                        onToggleSceneWatch = viewModel::setSceneWatchEnabled,
                        onTogglePreviewVisible = viewModel::setShowCameraPreview,
                        modifier = contentModifier
                    )

                    ClearPathTab.NAVIGATE -> NavigationScreen(
                        navState = navigationState,
                        voiceState = voiceState,
                        onStartCompass = viewModel::startCompassObservation,
                        onWhereAmI = viewModel::checkCurrentLocationAndAnnounce,
                        onDestinationChange = viewModel::updateDestinationQuery,
                        onSearchAndRoute = viewModel::searchAndStartNavigation,
                        onSelectCandidate = { viewModel.startRouteToCandidate(it) },
                        onOpenInMapsApp = { viewModel.openInPhoneMaps() },
                        onCheckPathAhead = viewModel::checkPathAheadFromNavigation,
                        onCheckRouteStatus = viewModel::announceNavigationStatus,
                        onStopNavigation = viewModel::stopNavigation,
                        onPrimaryVoiceTap = viewModel::onPrimaryVoiceButtonPressed,
                        onEndSession = viewModel::stopSpeakingOrSession,
                        modifier = contentModifier
                    )

                    ClearPathTab.TRANSLATE -> TranslationScreen(
                        translationState = translationState,
                        voiceState = voiceState,
                        onSelectSource = viewModel::setTranslationSource,
                        onSelectTarget = viewModel::setTranslationTarget,
                        onInputChanged = viewModel::updateTranslationInput,
                        onTranslateText = {
                            viewModel.translateText(translationState.inputText)
                        },
                        onReadAndTranslateCamera = {
                            viewModel.readTextFromCamera(translationState.targetLanguage)
                        },
                        onRepeatOriginal = viewModel::repeatTranslationOriginal,
                        onRepeatTranslation = viewModel::repeatTranslationResult,
                        onPrimaryVoiceTap = viewModel::onPrimaryVoiceButtonPressed,
                        onEndSession = viewModel::stopSpeakingOrSession,
                        modifier = contentModifier
                    )

                    ClearPathTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        voiceState = voiceState,
                        preferOnDevice = assistState.preferOnDeviceSingleTurn,
                        routingMode = navigationState.providerMode,
                        onSpeechRateChange = viewModel::setSpeechRate,
                        onTestVoice = {
                            viewModel.voiceCoordinator.speak(
                                "This is ClearPath speaking at ${"%.1f".format(settings.speechRate)} times speed."
                            )
                        },
                        onContinuousConvoChange = viewModel::setContinuousConversation,
                        onHapticsChange = viewModel::setHapticsEnabled,
                        onSceneWatchChange = viewModel::setSceneWatchEnabled,
                        onShowPreviewChange = viewModel::setShowCameraPreview,
                        onPreferOnDeviceChange = viewModel::setPreferOnDevice,
                        onSelectVoice = viewModel::setPreferredVoiceName,
                        onRoutingModeChange = viewModel::setRoutingProviderMode,
                        onSaveRoutingConfig = viewModel::saveRoutingConfiguration,
                        onPrimaryVoiceTap = viewModel::onPrimaryVoiceButtonPressed,
                        onEndSession = viewModel::stopSpeakingOrSession,
                        modifier = contentModifier
                    )
                }
            }
        }
    }
}

private fun tabIcon(tab: ClearPathTab, selected: Boolean) = when (tab) {
    ClearPathTab.ASSIST -> if (selected) Icons.Filled.RecordVoiceOver else Icons.Outlined.RecordVoiceOver
    ClearPathTab.NAVIGATE -> if (selected) Icons.Filled.Navigation else Icons.Outlined.Navigation
    ClearPathTab.TRANSLATE -> if (selected) Icons.Filled.Translate else Icons.Outlined.Translate
    ClearPathTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
