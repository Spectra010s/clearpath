package com.example.navigation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteProgressEvaluation(
    val nextStepIndex: Int,
    val distanceToNextTurnMeters: Double,
    val offRouteMeters: Double,
    val isOffRoute: Boolean,
    val arrived: Boolean,
    val spokenAnnouncement: String?
)

object NavigationEngine {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val STEP_ADVANCE_THRESHOLD_METERS = 14.0
    const val APPROACH_TURN_THRESHOLD_METERS = 25.0
    const val OFF_ROUTE_THRESHOLD_METERS = 28.0

    const val SAFETY_DISCLAIMER =
        "OpenStreetMap and GPS cannot detect curbs, stairs, holes, or traffic. Always rely on your cane, guide dog, and own safety checks before crossing any road."

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    fun cardinalDirection(degrees: Float): String {
        val normalized = ((degrees % 360f) + 360f) % 360f
        val dirs = listOf(
            "North", "Northeast", "East", "Southeast",
            "South", "Southwest", "West", "Northwest"
        )
        val idx = (((normalized + 22.5f) / 45f).toInt()) % 8
        return dirs[idx]
    }

    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360.0) % 360.0).toFloat()
    }

    fun relativeTurnCue(userHeadingDeg: Float, targetBearingDeg: Float): String {
        val diff = ((targetBearingDeg - userHeadingDeg + 540f) % 360f) - 180f
        return when {
            diff in -22f..22f -> "straight ahead"
            diff in 22f..67f -> "slight right"
            diff in 67f..120f -> "to your right"
            diff in -67f..-22f -> "slight left"
            diff in -120f..-67f -> "to your left"
            else -> "behind you"
        }
    }

    fun distanceToPolylineMeters(point: GeoPoint, polyline: List<GeoPoint>): Double {
        if (polyline.isEmpty()) return 0.0
        if (polyline.size == 1) return haversineMeters(point, polyline[0])

        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val d = pointToSegmentMeters(point, polyline[i], polyline[i + 1])
            if (d < minDistance) minDistance = d
        }
        return minDistance
    }

    private fun pointToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val latScale = cos(Math.toRadians(p.latitude)) * 111_320.0
        val lonScale = 111_320.0

        val ax = a.longitude * latScale
        val ay = a.latitude * lonScale
        val bx = b.longitude * latScale
        val by = b.latitude * lonScale
        val px = p.longitude * latScale
        val py = p.latitude * lonScale

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6) return haversineMeters(p, a)

        val t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        val projLon = (ax + t * dx) / latScale
        val projLat = (ay + t * dy) / lonScale
        return haversineMeters(p, GeoPoint(projLat, projLon))
    }

    fun evaluateProgress(
        currentPoint: GeoPoint,
        route: PedestrianRoute,
        currentStepIndex: Int,
        announcedStepIndex: Int
    ): RouteProgressEvaluation {
        if (route.steps.isEmpty()) {
            return RouteProgressEvaluation(
                nextStepIndex = 0,
                distanceToNextTurnMeters = 0.0,
                offRouteMeters = 0.0,
                isOffRoute = false,
                arrived = true,
                spokenAnnouncement = "You appear to be at ${route.destinationName}."
            )
        }

        val lastPoint = route.polyline.lastOrNull() ?: route.steps.last().maneuverLocation
        val distanceToDest = haversineMeters(currentPoint, lastPoint)
        if (distanceToDest <= STEP_ADVANCE_THRESHOLD_METERS && currentStepIndex >= route.steps.size - 2) {
            return RouteProgressEvaluation(
                nextStepIndex = route.steps.lastIndex,
                distanceToNextTurnMeters = distanceToDest,
                offRouteMeters = 0.0,
                isOffRoute = false,
                arrived = true,
                spokenAnnouncement = "You are arriving near ${route.destinationName}."
            )
        }

        var stepIdx = currentStepIndex.coerceIn(0, route.steps.lastIndex)
        var targetStep = route.steps[stepIdx]
        var distToManeuver = haversineMeters(currentPoint, targetStep.maneuverLocation)

        if (distToManeuver <= STEP_ADVANCE_THRESHOLD_METERS && stepIdx < route.steps.lastIndex) {
            stepIdx += 1
            targetStep = route.steps[stepIdx]
            distToManeuver = haversineMeters(currentPoint, targetStep.maneuverLocation)
        }

        val offRouteDist = distanceToPolylineMeters(currentPoint, route.polyline)
        val isOffRoute = offRouteDist > OFF_ROUTE_THRESHOLD_METERS

        val announcement = when {
            stepIdx != announcedStepIndex && distToManeuver <= APPROACH_TURN_THRESHOLD_METERS -> {
                "In ${distToManeuver.toInt()} meters, ${targetStep.instruction}."
            }
            stepIdx != announcedStepIndex -> {
                "Next: ${targetStep.instruction}, about ${targetStep.distanceMeters.toInt()} meters."
            }
            else -> null
        }

        return RouteProgressEvaluation(
            nextStepIndex = stepIdx,
            distanceToNextTurnMeters = distToManeuver,
            offRouteMeters = offRouteDist,
            isOffRoute = isOffRoute,
            arrived = false,
            spokenAnnouncement = announcement
        )
    }
}
