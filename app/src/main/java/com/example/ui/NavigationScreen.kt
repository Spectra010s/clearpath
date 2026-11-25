package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.navigation.NavigationEngine
import com.example.navigation.NavigationUiState
import com.example.navigation.PlaceCandidate
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AlertCoralContainer
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnWarmAmber
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.WarmAmber
import com.example.voice.VoiceUiState

@Composable
fun NavigationScreen(
    navState: NavigationUiState,
    voiceState: VoiceUiState,
    onStartCompass: () -> Unit,
    onWhereAmI: () -> Unit,
    onDestinationChange: (String) -> Unit,
    onSearchAndRoute: (String) -> Unit,
    onSelectCandidate: (PlaceCandidate) -> Unit,
    onOpenInMapsApp: () -> Unit,
    onCheckPathAhead: () -> Unit,
    onCheckRouteStatus: () -> Unit,
    onStopNavigation: () -> Unit,
    onPrimaryVoiceTap: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        onStartCompass()
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VoiceStatusBanner(voiceState = voiceState)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Honest safety limitation banner
            Card(
                colors = CardDefaults.cardColors(containerColor = AlertCoralContainer),
                border = BorderStroke(1.5.dp, AlertCoral)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = AlertCoral
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = NavigationEngine.SAFETY_DISCLAIMER,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HighContrastText
                    )
                }
            }

            // Location, Compass & Quick Camera Path Check
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.5.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Explore, contentDescription = null, tint = RestrainedCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Facing ${navState.compassCardinal} (${navState.compassDegrees.toInt()}°)",
                            style = MaterialTheme.typography.titleMedium,
                            color = RestrainedCyan,
                            modifier = Modifier.testTag("compass_heading_text")
                        )
                    }

                    Text(
                        text = navState.currentAddressSummary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HighContrastText,
                        modifier = Modifier.testTag("current_address_text")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onWhereAmI,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("where_am_i_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RestrainedCyan,
                                contentColor = OnWarmAmber
                            )
                        ) {
                            Icon(Icons.Rounded.MyLocation, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Where Am I?")
                        }

                        OutlinedButton(
                            onClick = { onSearchAndRoute("pharmacy") },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("nearest_pharmacy_button"),
                            border = BorderStroke(1.5.dp, WarmAmber)
                        ) {
                            Icon(Icons.Rounded.LocalPharmacy, contentDescription = null, tint = WarmAmber)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pharmacy", color = HighContrastText)
                        }
                    }

                    OutlinedButton(
                        onClick = onCheckPathAhead,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("nav_check_path_camera_button"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = ObsidianSurfaceElevated
                        ),
                        border = BorderStroke(1.5.dp, WarmAmber)
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = WarmAmber)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Check Path Ahead (Camera)",
                            style = MaterialTheme.typography.labelLarge,
                            color = HighContrastText
                        )
                    }
                }
            }

            // Destination search + Phone Maps Handoff
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionHeading("Walking Destination")
                    Text(
                        text = "Mode: ${navState.providerMode.label}. Or launch free turn-by-turn directions in your phone's Maps app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HighContrastSecondaryText
                    )
                    OutlinedTextField(
                        value = navState.destinationQuery,
                        onValueChange = onDestinationChange,
                        label = { Text("Address or place (or say 'Take me to...')") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("destination_input")
                    )
                    Button(
                        onClick = { onSearchAndRoute(navState.destinationQuery) },
                        enabled = !navState.isSearching && navState.destinationQuery.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("start_navigation_button")
                    ) {
                        Icon(Icons.Rounded.DirectionsWalk, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (navState.isSearching) "Searching..." else "Start ClearPath Guidance")
                    }

                    OutlinedButton(
                        onClick = onOpenInMapsApp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("open_phone_maps_button"),
                        border = BorderStroke(1.5.dp, RestrainedCyan)
                    ) {
                        Icon(Icons.Rounded.Map, contentDescription = null, tint = RestrainedCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Walking Directions in Maps App",
                            style = MaterialTheme.typography.labelLarge,
                            color = HighContrastText
                        )
                    }

                    navState.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlertCoral,
                            modifier = Modifier.testTag("navigation_error_text")
                        )
                    }
                }
            }

            // Active Zero-Key Compass Bearing Card
            if (navState.isNavigating && navState.activeBearingTarget != null) {
                val target = navState.activeBearingTarget
                val cardinal = NavigationEngine.cardinalDirection(navState.bearingTargetDegrees)
                val rel = NavigationEngine.relativeTurnCue(navState.compassDegrees, navState.bearingTargetDegrees)
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(2.dp, WarmAmber)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Compass Guidance to ${target.name}",
                            style = MaterialTheme.typography.titleMedium,
                            color = WarmAmber
                        )
                        Text(
                            text = "${navState.bearingTargetDistanceMeters.toInt()} m $cardinal ($rel)",
                            style = MaterialTheme.typography.titleLarge,
                            color = HighContrastText,
                            modifier = Modifier.testTag("active_bearing_cue")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCheckRouteStatus,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .testTag("check_route_button")
                            ) {
                                Text("Speak Cue")
                            }
                            OutlinedButton(
                                onClick = onStopNavigation,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .testTag("stop_navigation_button"),
                                border = BorderStroke(1.5.dp, AlertCoral)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null, tint = AlertCoral)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", color = AlertCoral)
                            }
                        }
                    }
                }
            }

            // Active Turn-by-Turn Street Route Card
            if (navState.isNavigating && navState.activeRoute != null) {
                val route = navState.activeRoute
                val currentStep = route.steps.getOrNull(navState.currentStepIndex)
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(2.dp, if (navState.isOffRoute) AlertCoral else ReadyEmerald)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Navigation,
                                contentDescription = null,
                                tint = if (navState.isOffRoute) AlertCoral else ReadyEmerald
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Guiding to ${route.destinationName}",
                                style = MaterialTheme.typography.titleMedium,
                                color = HighContrastText
                            )
                        }
                        Text(
                            text = currentStep?.instruction ?: navState.statusAnnouncement,
                            style = MaterialTheme.typography.titleLarge,
                            color = WarmAmber,
                            modifier = Modifier.testTag("active_turn_instruction")
                        )
                        Text(
                            text = "Distance to next maneuver: ${navState.distanceToNextTurnMeters.toInt()} m • Source: ${route.providerDescription}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HighContrastSecondaryText
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCheckRouteStatus,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .testTag("check_route_button")
                            ) {
                                Text("Check Route")
                            }
                            OutlinedButton(
                                onClick = onStopNavigation,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .testTag("stop_navigation_button"),
                                border = BorderStroke(1.5.dp, AlertCoral)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null, tint = AlertCoral)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", color = AlertCoral)
                            }
                        }
                    }
                }
            }

            if (navState.searchResults.size > 1 && !navState.isNavigating) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Alternative Matches",
                            style = MaterialTheme.typography.titleMedium,
                            color = HighContrastText
                        )
                        navState.searchResults.take(4).forEachIndexed { idx, item ->
                            OutlinedButton(
                                onClick = { onSelectCandidate(item) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 54.dp)
                                    .testTag("candidate_place_$idx")
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(item.name, color = WarmAmber, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        text = "${item.subtitle}${item.distanceMeters?.let { " (${it.toInt()} m)" } ?: ""}",
                                        color = HighContrastSecondaryText,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        PrimaryVoiceActionButton(
            voiceState = voiceState,
            onClick = onPrimaryVoiceTap,
            onEndSession = onEndSession
        )
    }
}
