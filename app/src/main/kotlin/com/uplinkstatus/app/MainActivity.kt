package com.uplinkstatus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.uplinkstatus.app.ui.PlaceholderScreen

/**
 * Stage 0 scaffold entry point. Hosts only [PlaceholderScreen]; no probe,
 * state machine, notification, or settings logic belongs here yet.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                PlaceholderScreen()
            }
        }
    }
}
