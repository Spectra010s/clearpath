package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "CameraController"

class CameraController(context: Context) {

    private val appContext = context.applicationContext
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sceneDetector = SceneChangeDetector()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var sceneWatchActive: Boolean = false

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _sceneChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sceneChanges: SharedFlow<Unit> = _sceneChanges.asSharedFlow()

    fun setSceneWatchEnabled(enabled: Boolean) {
        if (sceneWatchActive != enabled) {
            sceneWatchActive = enabled
            sceneDetector.reset()
        }
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = previewView?.let { pv ->
                    Preview.Builder().build().also {
                        it.surfaceProvider = pv.surfaceProvider
                    }
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(cameraExecutor) { proxy ->
                            try {
                                if (sceneWatchActive) {
                                    val sig = FrameProcessor.sampleLumaSignature(proxy)
                                    if (sig != null && sceneDetector.evaluate(sig, System.currentTimeMillis())) {
                                        _sceneChanges.tryEmit(Unit)
                                    }
                                }
                            } finally {
                                proxy.close()
                            }
                        }
                    }

                provider.unbindAll()
                val useCases = listOfNotNull(preview, capture, analysis).toTypedArray()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases
                )
                _isCameraReady.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX use cases", e)
                _isCameraReady.value = false
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    suspend fun captureFrame(): Result<Bitmap> {
        val capture = imageCapture
            ?: return Result.failure(IllegalStateException("Camera is not ready yet. Please grant camera access."))

        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val raw = image.toBitmap()
                            val rotation = image.imageInfo.rotationDegrees
                            val processed = FrameProcessor.rotateAndDownscale(raw, rotation, 1024)
                            if (continuation.isActive) {
                                continuation.resume(Result.success(processed))
                            } else {
                                processed.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed processing captured frame", e)
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(e))
                            }
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Camera capture failed", exception)
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(exception))
                        }
                    }
                }
            )
        }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        imageCapture = null
        _isCameraReady.value = false
    }

    fun release() {
        unbind()
        cameraExecutor.shutdown()
    }
}
