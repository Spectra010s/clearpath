package com.example.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class ClearPathPermission(
    val manifestPermission: String,
    val title: String,
    val rationale: String
) {
    MICROPHONE(
        manifestPermission = Manifest.permission.RECORD_AUDIO,
        title = "Microphone access",
        rationale = "ClearPath uses your microphone only while a listening session is active or while you explicitly enable Wake-Phrase Mode with an ongoing notification."
    ),
    CAMERA(
        manifestPermission = Manifest.permission.CAMERA,
        title = "Camera access",
        rationale = "ClearPath uses your camera only when you ask to describe your surroundings, identify objects, or read text. Images are processed on demand and not saved to disk."
    ),
    LOCATION(
        manifestPermission = Manifest.permission.ACCESS_FINE_LOCATION,
        title = "Location access",
        rationale = "ClearPath uses GPS only when you ask where you are or start walking guidance. Location tracking stops as soon as you end navigation."
    ),
    NOTIFICATIONS(
        manifestPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            Manifest.permission.RECORD_AUDIO
        },
        title = "Notification access",
        rationale = "Android requires a visible ongoing notification whenever ClearPath Wake-Phrase Mode is listening in the foreground."
    )
}

data class PermissionSnapshot(
    val hasMicrophone: Boolean = false,
    val hasCamera: Boolean = false,
    val hasLocation: Boolean = false,
    val hasNotifications: Boolean = true
)

object PermissionChecker {
    fun snapshot(context: Context): PermissionSnapshot {
        val mic = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val cam = hasPermission(context, Manifest.permission.CAMERA)
        val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return PermissionSnapshot(
            hasMicrophone = mic,
            hasCamera = cam,
            hasLocation = fine || coarse,
            hasNotifications = notifications
        )
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
