package com.example.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "WakePhraseService"

/**
 * Play-compliant foreground microphone service for hands-free wake-phrase detection
 * ("ClearPath activate" / "Hey ClearPath") while explicitly opted in by the user.
 * Displays a persistent Android foreground notification and pauses automatically
 * whenever VoiceCoordinator owns the microphone.
 */
class WakePhraseService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var sessionObserverJob: Job? = null
    private var isListeningActive = false
    private var isDestroyed = false

    private val restartRunnable = Runnable {
        if (!isDestroyed && !WakePhraseBridge.isVoiceSessionBusy.value) {
            startWakeListeningInternal()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeVoiceSessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WakePhraseBridge.emitStoppedByNotification()
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            else -> {
                // Must be the first thing we do: Android kills the app if
                // startForeground() isn't called promptly after startForegroundService().
                startForegroundWithNotification()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "RECORD_AUDIO not granted; stopping WakePhraseService.")
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                WakePhraseBridge.setServiceRunning(true)
                if (!WakePhraseBridge.isVoiceSessionBusy.value) {
                    scheduleNextListen(250L)
                }
                return START_STICKY
            }
        }
    }

    private fun observeVoiceSessionState() {
        sessionObserverJob?.cancel()
        sessionObserverJob = serviceScope.launch {
            WakePhraseBridge.isVoiceSessionBusy.collect { busy ->
                if (busy) {
                    mainHandler.removeCallbacks(restartRunnable)
                    stopWakeRecognizerInternal()
                } else if (WakePhraseBridge.isServiceRunning.value && !isDestroyed) {
                    scheduleNextListen(600L)
                }
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_WAKE_ACTIVATE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, WakePhraseService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ClearPath Wake-Phrase Active")
            .setContentText("Say \"ClearPath activate\" or \"Hey ClearPath\" to start speaking.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ClearPath Wake-Phrase Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent indicator shown while hands-free wake-phrase detection is turned on."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun scheduleNextListen(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun startWakeListeningInternal() {
        if (isDestroyed || isListeningActive || WakePhraseBridge.isVoiceSessionBusy.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer unavailable; stopping WakePhraseService.")
            stopSelfCleanly()
            return
        }

        stopWakeRecognizerInternal()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        isListeningActive = true

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    val match = WakePhraseDetector.match(partial)
                    if (match != null) {
                        handleWakePhraseMatched(match)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                stopWakeRecognizerInternal()
                val match = candidates.firstNotNullOfOrNull { WakePhraseDetector.match(it) }
                if (match != null) {
                    handleWakePhraseMatched(match)
                } else {
                    scheduleNextListen(350L)
                }
            }

            override fun onError(error: Int) {
                stopWakeRecognizerInternal()
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.w(TAG, "Mic permission revoked; stopping WakePhraseService.")
                        stopSelfCleanly()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleNextListen(1500L)
                    else -> scheduleNextListen(450L)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake-phrase listener", e)
            stopWakeRecognizerInternal()
            scheduleNextListen(2000L)
        }
    }

    private fun handleWakePhraseMatched(match: WakePhraseMatch) {
        mainHandler.removeCallbacks(restartRunnable)
        stopWakeRecognizerInternal()
        WakePhraseBridge.setVoiceSessionBusy(true)
        WakePhraseBridge.emitActivation(match.trailingCommand)

        runCatching {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_WAKE_ACTIVATE, true)
            }
            startActivity(launchIntent)
        }.onFailure { err ->
            Log.w(TAG, "OS blocked bringing MainActivity forward from background: ${err.message}")
        }
    }

    private fun stopWakeRecognizerInternal() {
        isListeningActive = false
        speechRecognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        speechRecognizer = null
    }

    private fun stopSelfCleanly() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopWakeRecognizerInternal()
        WakePhraseBridge.setServiceRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopWakeRecognizerInternal()
        WakePhraseBridge.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "clearpath_wake_phrase"
        const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.voice.action.START_WAKE_PHRASE"
        const val ACTION_STOP = "com.example.voice.action.STOP_WAKE_PHRASE"
        const val EXTRA_WAKE_ACTIVATE = "extra_wake_activate"

        fun start(context: Context) {
            val intent = Intent(context, WakePhraseService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakePhraseService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
