package com.example.navigation

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "OsmRoutingClient"

/**
 * Policy-aware OpenStreetMap geocoding, POI lookup, and pedestrian routing client.
 * Never hammers public demo servers: enforces descriptive User-Agent, >= 1.5s rate limiting,
 * and supports OpenRouteService (free key) or self-hosted OSRM/Valhalla servers.
 */
class OsmRoutingClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "ClearPath-Assistive-Android/1.0 (accessibility-companion)")
                .build()
            chain.proceed(request)
        }
        .build()

    @Suppress("unused")
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val rateLimitMutex = Mutex()
    private var lastRequestAtMs = 0L

    private suspend fun throttle() {
        rateLimitMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestAtMs
            if (elapsed < 1_500L) {
                kotlinx.coroutines.delay(1_500L - elapsed)
            }
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    suspend fun searchNearbyOrAddress(
        query: String,
        near: GeoPoint?,
        orsApiKey: String
    ): Result<List<PlaceCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Please say or enter a place name or address.")

            if (orsApiKey.isNotBlank()) {
                return@runCatching searchWithOrsPelias(trimmed, near, orsApiKey)
            }

            // If asking for a category like "nearest pharmacy" and we have GPS, use Overpass API within bounds
            if (near != null && isCategoryPoiQuery(trimmed)) {
                val poiResults = searchPoiWithOverpass(trimmed, near)
                if (poiResults.isNotEmpty()) return@runCatching poiResults
            }

            // Fallback single-shot Nominatim search (strictly throttled >=1.5s with User-Agent)
            searchWithNominatim(trimmed, near)
        }
    }

    suspend fun fetchNearbyPlacesSummary(
        near: GeoPoint,
        radiusMeters: Int = 180
    ): Result<List<PlaceCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            throttle()
            val overpassQl = """
                [out:json][timeout:10];
                (
                  node["name"]["amenity"](around:$radiusMeters,${near.latitude},${near.longitude});
                  node["name"]["shop"](around:$radiusMeters,${near.latitude},${near.longitude});
                  node["name"]["highway"="bus_stop"](around:$radiusMeters,${near.latitude},${near.longitude});
                  way["name"]["building"](around:$radiusMeters,${near.latitude},${near.longitude});
                );
                out center 6;
            """.trimIndent()
            val encoded = URLEncoder.encode(overpassQl, "UTF-8")
            val req = Request.Builder()
                .url("https://overpass-api.de/api/interpreter?data=$encoded")
                .get()
                .build()
            val body = executeForString(req)
            val elements = JSONObject(body).optJSONArray("elements") ?: JSONArray()
            val list = mutableListOf<PlaceCandidate>()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val lat = if (el.has("lat")) el.getDouble("lat") else el.optJSONObject("center")?.optDouble("lat") ?: continue
                val lon = if (el.has("lon")) el.getDouble("lon") else el.optJSONObject("center")?.optDouble("lon") ?: continue
                val tags = el.optJSONObject("tags") ?: continue
                val name = tags.optString("name").trim()
                if (name.isBlank()) continue
                val kind = tags.optString("amenity")
                    .ifBlank { tags.optString("shop") }
                    .ifBlank { tags.optString("highway") }
                    .ifBlank { "landmark" }
                val point = GeoPoint(lat, lon)
                val dist = NavigationEngine.haversineMeters(near, point)
                list.add(PlaceCandidate(name = name, subtitle = kind, location = point, distanceMeters = dist))
            }
            list.distinctBy { it.name.lowercase() }
                .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        }
    }

    suspend fun fetchPedestrianRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        destinationName: String,
        mode: RoutingProviderMode,
        orsApiKey: String,
        customBaseUrl: String
    ): Result<PedestrianRoute> = withContext(Dispatchers.IO) {
        runCatching {
            when (mode) {
                RoutingProviderMode.OPEN_ROUTE_SERVICE -> {
                    if (orsApiKey.isBlank()) {
                        throw IllegalStateException(
                            "OpenRouteService API key is not set. Open Settings to add a free OpenRouteService key, enter a self-hosted OSRM URL, or switch to Dev-Only OSRM Demo mode."
                        )
                    }
                    routeWithOpenRouteService(origin, destination, destinationName, orsApiKey)
                }
                RoutingProviderMode.CUSTOM_OSRM -> {
                    val base = customBaseUrl.trim().trimEnd('/')
                    if (base.isBlank() || !base.startsWith("http")) {
                        throw IllegalStateException(
                            "No self-hosted OSRM server URL is configured in Settings."
                        )
                    }
                    routeWithOsrm(base, origin, destination, destinationName, "Self-Hosted OSRM")
                }
                RoutingProviderMode.PUBLIC_DEMO_DEV_ONLY -> {
                    routeWithOsrm(
                        baseUrl = "https://routing.openstreetmap.de/routed-foot",
                        origin = origin,
                        destination = destination,
                        destinationName = destinationName,
                        providerLabel = "OSM Foot Demo Server (Dev-Only)"
                    )
                }
            }
        }
    }

    private suspend fun searchWithOrsPelias(
        query: String,
        near: GeoPoint?,
        apiKey: String
    ): List<PlaceCandidate> {
        throttle()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val focus = if (near != null) "&focus.point.lat=${near.latitude}&focus.point.lon=${near.longitude}" else ""
        val url = "https://api.openrouteservice.org/geocode/search?api_key=$apiKey&text=$encoded&size=5$focus"
        val req = Request.Builder().url(url).get().build()
        val body = executeForString(req)
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: JSONArray()
        val list = mutableListOf<PlaceCandidate>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val lon = coords.getDouble(0)
            val lat = coords.getDouble(1)
            val props = f.getJSONObject("properties")
            val name = props.optString("name", query)
            val label = props.optString("label", name)
            val point = GeoPoint(lat, lon)
            val dist = near?.let { NavigationEngine.haversineMeters(it, point) }
            list.add(PlaceCandidate(name = name, subtitle = label, location = point, distanceMeters = dist))
        }
        return list
    }

    private suspend fun searchPoiWithOverpass(query: String, near: GeoPoint): List<PlaceCandidate> {
        throttle()
        val amenityTag = mapQueryToOsmAmenity(query) ?: return emptyList()
        val overpassQl = """
            [out:json][timeout:12];
            (
              node["amenity"="$amenityTag"](around:2500,${near.latitude},${near.longitude});
              way["amenity"="$amenityTag"](around:2500,${near.latitude},${near.longitude});
            );
            out center 8;
        """.trimIndent()
        val encoded = URLEncoder.encode(overpassQl, "UTF-8")
        val req = Request.Builder()
            .url("https://overpass-api.de/api/interpreter?data=$encoded")
            .get()
            .build()
        val body = executeForString(req)
        val elements = JSONObject(body).optJSONArray("elements") ?: JSONArray()
        val results = mutableListOf<PlaceCandidate>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val lat = if (el.has("lat")) el.getDouble("lat") else el.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (el.has("lon")) el.getDouble("lon") else el.optJSONObject("center")?.optDouble("lon") ?: continue
            val tags = el.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: amenityTag.replaceFirstChar { it.uppercase() }
            val street = tags?.optString("addr:street").orEmpty()
            val point = GeoPoint(lat, lon)
            val dist = NavigationEngine.haversineMeters(near, point)
            results.add(
                PlaceCandidate(
                    name = name,
                    subtitle = if (street.isNotBlank()) street else "OpenStreetMap $amenityTag",
                    location = point,
                    distanceMeters = dist
                )
            )
        }
        return results.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
    }

    private suspend fun searchWithNominatim(query: String, near: GeoPoint?): List<PlaceCandidate> {
        throttle()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val viewbox = if (near != null) {
            val d = 0.15
            "&viewbox=${near.longitude - d},${near.latitude + d},${near.longitude + d},${near.latitude - d}&bounded=0"
        } else ""
        val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=jsonv2&limit=5$viewbox"
        val req = Request.Builder().url(url).get().build()
        val body = executeForString(req)
        val arr = JSONArray(body)
        val results = mutableListOf<PlaceCandidate>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val lat = item.getDouble("lat")
            val lon = item.getDouble("lon")
            val display = item.optString("display_name", query)
            val shortName = display.substringBefore(",").trim().ifBlank { query }
            val point = GeoPoint(lat, lon)
            val dist = near?.let { NavigationEngine.haversineMeters(it, point) }
            results.add(PlaceCandidate(name = shortName, subtitle = display, location = point, distanceMeters = dist))
        }
        return results
    }

    private suspend fun routeWithOpenRouteService(
        origin: GeoPoint,
        destination: GeoPoint,
        destinationName: String,
        apiKey: String
    ): PedestrianRoute {
        throttle()
        val url = "https://api.openrouteservice.org/v2/directions/foot-walking" +
            "?api_key=$apiKey" +
            "&start=${origin.longitude},${origin.latitude}" +
            "&end=${destination.longitude},${destination.latitude}"
        val req = Request.Builder().url(url).get().build()
        val body = executeForString(req)
        val root = JSONObject(body)
        val feature = root.getJSONArray("features").getJSONObject(0)
        val coordsJson = feature.getJSONObject("geometry").getJSONArray("coordinates")
        val polyline = buildList {
            for (i in 0 until coordsJson.length()) {
                val c = coordsJson.getJSONArray(i)
                add(GeoPoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
            }
        }
        val props = feature.getJSONObject("properties")
        val summary = props.getJSONObject("summary")
        val totalDist = summary.getDouble("distance")
        val durationSec = summary.getDouble("duration")
        val segments = props.getJSONArray("segments")
        val steps = mutableListOf<RouteStep>()
        for (s in 0 until segments.length()) {
            val stepsJson = segments.getJSONObject(s).getJSONArray("steps")
            for (i in 0 until stepsJson.length()) {
                val st = stepsJson.getJSONObject(i)
                val wayPoints = st.getJSONArray("way_points")
                val startIdx = wayPoints.getInt(0).coerceIn(0, (polyline.size - 1).coerceAtLeast(0))
                val maneuverPoint = polyline.getOrElse(startIdx) { destination }
                steps.add(
                    RouteStep(
                        instruction = st.optString("instruction", "Continue"),
                        distanceMeters = st.optDouble("distance", 0.0),
                        maneuverLocation = maneuverPoint,
                        streetName = st.optString("name", "")
                    )
                )
            }
        }
        return PedestrianRoute(
            destinationName = destinationName,
            totalDistanceMeters = totalDist,
            estimatedMinutes = (durationSec / 60.0).toInt().coerceAtLeast(1),
            polyline = polyline,
            steps = steps,
            providerDescription = "OpenStreetMap via OpenRouteService (foot-walking)"
        )
    }

    private suspend fun routeWithOsrm(
        baseUrl: String,
        origin: GeoPoint,
        destination: GeoPoint,
        destinationName: String,
        providerLabel: String
    ): PedestrianRoute {
        throttle()
        val url = "$baseUrl/route/v1/foot/" +
            "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
            "?steps=true&geometries=geojson&overview=full"
        val req = Request.Builder().url(url).get().build()
        val body = executeForString(req)
        val root = JSONObject(body)
        if (root.optString("code") != "Ok") {
            throw IllegalStateException("Routing server could not calculate a walking route.")
        }
        val route = root.getJSONArray("routes").getJSONObject(0)
        val totalDist = route.getDouble("distance")
        val durationSec = route.getDouble("duration")
        val coordsJson = route.getJSONObject("geometry").getJSONArray("coordinates")
        val polyline = buildList {
            for (i in 0 until coordsJson.length()) {
                val c = coordsJson.getJSONArray(i)
                add(GeoPoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
            }
        }
        val legs = route.getJSONArray("legs")
        val steps = mutableListOf<RouteStep>()
        for (l in 0 until legs.length()) {
            val stepsJson = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until stepsJson.length()) {
                val st = stepsJson.getJSONObject(i)
                val maneuver = st.getJSONObject("maneuver")
                val loc = maneuver.getJSONArray("location")
                val type = maneuver.optString("type", "continue")
                val modifier = maneuver.optString("modifier", "")
                val street = st.optString("name", "")
                val humanInstruction = formatOsrmStep(type, modifier, street)
                steps.add(
                    RouteStep(
                        instruction = humanInstruction,
                        distanceMeters = st.optDouble("distance", 0.0),
                        maneuverLocation = GeoPoint(loc.getDouble(1), loc.getDouble(0)),
                        streetName = street
                    )
                )
            }
        }
        return PedestrianRoute(
            destinationName = destinationName,
            totalDistanceMeters = totalDist,
            estimatedMinutes = (durationSec / 60.0).toInt().coerceAtLeast(1),
            polyline = polyline,
            steps = steps,
            providerDescription = providerLabel
        )
    }

    private fun executeForString(request: Request): String {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} for ${request.url.host}: $raw")
                throw IllegalStateException("Routing service returned HTTP ${response.code}.")
            }
            return raw
        }
    }

    companion object {
        fun formatOsrmStep(type: String, modifier: String, street: String): String {
            val onStreet = if (street.isNotBlank()) " onto $street" else ""
            return when (type) {
                "depart" -> "Head ${modifier.ifBlank { "forward" }}${if (street.isNotBlank()) " along $street" else ""}"
                "arrive" -> "Arrive at your destination"
                "turn" -> "Turn ${modifier.ifBlank { "ahead" }}$onStreet"
                "continue" -> "Continue ${modifier.ifBlank { "straight" }}$onStreet"
                "roundabout" -> "Navigate roundabout$onStreet"
                else -> "${type.replaceFirstChar { it.uppercase() }} ${modifier}$onStreet".trim()
            }
        }

        private fun isCategoryPoiQuery(query: String): Boolean {
            val q = query.lowercase()
            return listOf("pharmacy", "chemist", "hospital", "clinic", "bank", "atm", "supermarket", "restaurant", "cafe", "bus stop")
                .any { q.contains(it) }
        }

        private fun mapQueryToOsmAmenity(query: String): String? {
            val q = query.lowercase()
            return when {
                q.contains("pharmacy") || q.contains("chemist") -> "pharmacy"
                q.contains("hospital") -> "hospital"
                q.contains("clinic") -> "clinic"
                q.contains("bank") -> "bank"
                q.contains("atm") -> "atm"
                q.contains("cafe") -> "cafe"
                q.contains("restaurant") -> "restaurant"
                else -> null
            }
        }
    }
}
