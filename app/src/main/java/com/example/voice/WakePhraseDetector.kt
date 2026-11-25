package com.example.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakePhraseMatch(
    val matchedPhrase: String,
    val trailingCommand: String?
)

object WakePhraseDetector {

    private val wakePhrases = listOf(
        "clearpath activate",
        "clear path activate",
        "activate clearpath",
        "activate clear path",
        "hey clearpath",
        "hey clear path",
        "ok clearpath",
        "ok clear path",
        "okay clearpath",
        "okay clear path",
        "start clearpath",
        "start clear path"
    )

    fun match(rawTranscript: String): WakePhraseMatch? {
        val trimmed = rawTranscript.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        for (phrase in wakePhrases) {
            val idx = normalized.indexOf(phrase)
            if (idx >= 0) {
                val after = normalized.substring(idx + phrase.length).trim()
                return WakePhraseMatch(
                    matchedPhrase = phrase,
                    trailingCommand = after.takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }
}

object WakePhraseBridge {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isVoiceSessionBusy = MutableStateFlow(false)
    val isVoiceSessionBusy: StateFlow<Boolean> = _isVoiceSessionBusy.asStateFlow()

    private val _stoppedByNotification = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stoppedByNotification: SharedFlow<Unit> = _stoppedByNotification.asSharedFlow()

    private val _activationEvents = MutableSharedFlow<String?>(extraBufferCapacity = 8)
    val activationEvents: SharedFlow<String?> = _activationEvents.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun setVoiceSessionBusy(busy: Boolean) {
        _isVoiceSessionBusy.value = busy
    }

    fun emitActivation(trailingCommand: String?) {
        _activationEvents.tryEmit(trailingCommand)
    }

    fun emitStoppedByNotification() {
        _stoppedByNotification.tryEmit(Unit)
    }
}
