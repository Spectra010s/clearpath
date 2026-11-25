package com.example.navigation

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class LocationSnapshot(
    val point: GeoPoint,
    val accuracyMeters: Float,
    val bearingDegrees: Float? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val maneuverLocation: GeoPoint,
    val streetName: String = ""
)

data class PedestrianRoute(
    val destinationName: String,
    val totalDistanceMeters: Double,
    val estimatedMinutes: Int,
    val polyline: List<GeoPoint>,
    val steps: List<RouteStep>,
    val providerDescription: String
)

data class PlaceCandidate(
    val name: String,
    val subtitle: String,
    val location: GeoPoint,
    val distanceMeters: Double? = null
)

enum class RoutingProviderMode(val label: String, val policyNote: String) {
    OPEN_ROUTE_SERVICE(
        label = "OpenRouteService (API Key)",
        policyNote = "Uses OpenRouteService foot-walking API (free developer tier available)."
    ),
    CUSTOM_OSRM(
        label = "Self-Hosted OSRM Server",
        policyNote = "Uses your own self-hosted OSRM instance over HTTPS."
    ),
    PUBLIC_DEMO_DEV_ONLY(
        label = "Dev-Only OSRM Demo (Rate-Limited)",
        policyNote = "For testing only. Public demo server has strict 1 req/s limits and no SLA."
    )
}

data class NavigationUiState(
    val isNavigating: Boolean = false,
    val isSearching: Boolean = false,
    val destinationQuery: String = "",
    val currentLocation: LocationSnapshot? = null,
    val currentAddressSummary: String = "Location not checked yet.",
    val compassDegrees: Float = 0f,
    val compassCardinal: String = "North",
    val activeRoute: PedestrianRoute? = null,
    val activeBearingTarget: PlaceCandidate? = null,
    val bearingTargetDistanceMeters: Double = 0.0,
    val bearingTargetDegrees: Float = 0f,
    val currentStepIndex: Int = 0,
    val distanceToNextTurnMeters: Double = 0.0,
    val offRouteMeters: Double = 0.0,
    val isOffRoute: Boolean = false,
    val statusAnnouncement: String = "Navigation idle. Ask 'Where am I?' or enter a destination.",
    val searchResults: List<PlaceCandidate> = emptyList(),
    val providerMode: RoutingProviderMode = RoutingProviderMode.OPEN_ROUTE_SERVICE,
    val errorMessage: String? = null
)
