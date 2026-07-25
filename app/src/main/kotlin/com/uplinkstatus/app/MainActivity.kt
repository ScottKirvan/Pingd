package com.uplinkstatus.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.uplinkstatus.app.service.UplinkStatusService
import com.uplinkstatus.app.ui.NotificationPermissionScreen
import com.uplinkstatus.app.ui.PlaceholderScreen

/**
 * Stage 2 adds the `POST_NOTIFICATIONS` runtime-permission request/rationale flow (the
 * status-bar icon can't be shown at all without it) and starts [UplinkStatusService] once
 * it's granted. No settings/preferences UI belongs here yet — that's Stage 3.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                var permissionGranted by remember { mutableStateOf(hasNotificationPermission()) }
                var permissionDenied by remember { mutableStateOf(false) }

                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    permissionGranted = granted
                    permissionDenied = !granted
                    if (granted) startUplinkService()
                }

                if (permissionGranted) {
                    PlaceholderScreen()
                } else {
                    NotificationPermissionScreen(
                        showDenied = permissionDenied,
                        onRequestPermission = {
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                }
            }
        }

        if (hasNotificationPermission()) {
            startUplinkService()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun startUplinkService() {
        ContextCompat.startForegroundService(this, UplinkStatusService.createStartIntent(this))
    }
}
