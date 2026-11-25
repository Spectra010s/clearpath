package com.example.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Distinct non-visual tactile cues so blind and low-vision users can feel
 * state transitions (listening, processing, answer ready, navigation turns, errors).
 */
class AssistiveHaptics(context: Context) {

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile
    var enabled: Boolean = true

    fun onListeningStart() {
        vibratePattern(longArrayOf(0, 45), intArrayOf(0, 180))
    }

    fun onListeningStop() {
        vibratePattern(longArrayOf(0, 28, 35, 28), intArrayOf(0, 130, 0, 130))
    }

    fun onProcessingTick() {
        vibratePattern(longArrayOf(0, 22), intArrayOf(0, 90))
    }

    fun onAnswerReady() {
        vibratePattern(longArrayOf(0, 35, 45, 60), intArrayOf(0, 140, 0, 210))
    }

    fun onNavigationTurn() {
        vibratePattern(longArrayOf(0, 70, 60, 70), intArrayOf(0, 220, 0, 220))
    }

    fun onErrorAlert() {
        vibratePattern(longArrayOf(0, 90, 60, 90, 60, 90), intArrayOf(0, 255, 0, 255, 0, 255))
    }

    @Suppress("DEPRECATION")
    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                v.vibrate(timings, -1)
            }
        } catch (_: SecurityException) {
            // Ignore if vibration permission/hardware unavailable in test environments
        }
    }
}
