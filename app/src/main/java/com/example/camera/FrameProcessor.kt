package com.example.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure frame pre-processing and lightweight on-device scene-change gating
 * so ClearPath never continuously streams camera video to the cloud.
 */
object FrameProcessor {

    fun rotateAndDownscale(
        source: Bitmap,
        rotationDegrees: Int,
        maxEdgePx: Int = 1024
    ): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val w = source.width
        val h = source.height
        val longest = max(w, h).coerceAtLeast(1)
        val scale = if (longest > maxEdgePx) maxEdgePx.toFloat() / longest else 1f

        if (normalizedRotation == 0 && scale == 1f) {
            return source
        }

        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (normalizedRotation != 0) postRotate(normalizedRotation.toFloat())
        }

        val transformed = Bitmap.createBitmap(source, 0, 0, w, h, matrix, true)
        if (transformed !== source) {
            source.recycle()
        }
        return transformed
    }

    fun sampleLumaSignature(imageProxy: ImageProxy, gridSize: Int = 12): FloatArray? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return null

        val signature = FloatArray(gridSize * gridSize)
        val stepX = (width / gridSize).coerceAtLeast(1)
        val stepY = (height / gridSize).coerceAtLeast(1)

        var idx = 0
        for (gy in 0 until gridSize) {
            val y = (gy * stepY + stepY / 2).coerceAtMost(height - 1)
            for (gx in 0 until gridSize) {
                val x = (gx * stepX + stepX / 2).coerceAtMost(width - 1)
                val offset = y * rowStride + x * pixelStride
                if (offset in 0 until buffer.limit()) {
                    signature[idx] = (buffer.get(offset).toInt() and 0xFF).toFloat()
                }
                idx++
            }
        }
        return signature
    }
}

class SceneChangeDetector(
    private val meanDeltaThreshold: Float = 24f,
    private val cooldownMs: Long = 9_000L,
    private val sampleIntervalMs: Long = 1_500L
) {
    private var previousSignature: FloatArray? = null
    private var lastSampleMs: Long = Long.MIN_VALUE / 2
    private var lastTriggeredMs: Long = Long.MIN_VALUE / 2

    fun reset() {
        previousSignature = null
        lastSampleMs = Long.MIN_VALUE / 2
        lastTriggeredMs = Long.MIN_VALUE / 2
    }

    fun evaluate(signature: FloatArray, nowMs: Long): Boolean {
        if (nowMs - lastSampleMs < sampleIntervalMs) return false
        lastSampleMs = nowMs

        val prev = previousSignature
        previousSignature = signature
        if (prev == null || prev.size != signature.size) return false

        var diffSum = 0f
        for (i in signature.indices) {
            diffSum += abs(signature[i] - prev[i])
        }
        val meanDiff = diffSum / signature.size

        if (meanDiff >= meanDeltaThreshold && nowMs - lastTriggeredMs >= cooldownMs) {
            lastTriggeredMs = nowMs
            return true
        }
        return false
    }
}
