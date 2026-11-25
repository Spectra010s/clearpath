package com.example.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class LocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentFix(): Result<LocationSnapshot> {
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null && continuation.isActive) {
                            continuation.resume(
                                LocationSnapshot(
                                    point = GeoPoint(loc.latitude, loc.longitude),
                                    accuracyMeters = loc.accuracy,
                                    bearingDegrees = if (loc.hasBearing()) loc.bearing else null,
                                    timestampMs = loc.time
                                )
                            )
                        } else if (continuation.isActive) {
                            continuation.cancel(
                                IllegalStateException("GPS fix unavailable right now. Step outdoors or wait a moment.")
                            )
                        }
                    }
                    .addOnFailureListener { err ->
                        if (continuation.isActive) {
                            continuation.cancel(err)
                        }
                    }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun locationUpdatesFlow(intervalMs: Long = 3_000L): Flow<LocationSnapshot> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(1_500L)
            .setMinUpdateDistanceMeters(2f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    LocationSnapshot(
                        point = GeoPoint(loc.latitude, loc.longitude),
                        accuracyMeters = loc.accuracy,
                        bearingDegrees = if (loc.hasBearing()) loc.bearing else null,
                        timestampMs = loc.time
                    )
                )
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    @Suppress("DEPRECATION")
    suspend fun reverseGeocodeAddress(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(point.latitude, point.longitude, 1) { addresses ->
                        val addr = addresses.firstOrNull()
                        val formatted = listOfNotNull(
                            addr?.thoroughfare ?: addr?.featureName,
                            addr?.subLocality ?: addr?.locality,
                            addr?.adminArea
                        ).distinct().joinToString(", ").ifBlank { null }
                        if (continuation.isActive) continuation.resume(formatted)
                    }
                }
            } else {
                val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)
                val addr = addresses?.firstOrNull()
                listOfNotNull(
                    addr?.thoroughfare ?: addr?.featureName,
                    addr?.subLocality ?: addr?.locality,
                    addr?.adminArea
                ).distinct().joinToString(", ").ifBlank { null }
            }
        }.getOrNull()
    }
}

class CompassSensor(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun headingDegreesFlow(): Flow<Float> = callbackFlow {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        var lastEmittedMs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lastEmittedMs < 250L) return
                lastEmittedMs = now

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthDeg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
                trySend(azimuthDeg)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
