package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app.configureAppCheck
import com.example.ui.ClearPathApp
import com.example.ui.theme.ClearPathTheme
import com.example.voice.WakePhraseService

class MainActivity : ComponentActivity() {

    private var assistantTriggerSequence by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before any UI or ViewModel
        configureAppCheck(this, intent)

        if (isAssistantOrWakeIntent(intent)) {
            assistantTriggerSequence++
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            ClearPathTheme {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideScreen = maxWidth >= 840.dp
                    ClearPathApp(
                        isWideScreen = isWideScreen,
                        assistantTriggerSequence = assistantTriggerSequence
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssistantOrWakeIntent(intent)) {
            assistantTriggerSequence++
        }
    }

    private fun isAssistantOrWakeIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.getBooleanExtra(WakePhraseService.EXTRA_WAKE_ACTIVATE, false)
    }
}
