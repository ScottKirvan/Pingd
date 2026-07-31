package com.uplinkstatus.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.uplinkstatus.app.permissions.LocationPermissionStatus
import com.uplinkstatus.app.prefs.DataStoreUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.uplinkPreferencesDataStore
import com.uplinkstatus.app.service.UplinkStatusService
import com.uplinkstatus.app.ui.NotificationPermissionScreen
import com.uplinkstatus.app.ui.SettingsScreen
import com.uplinkstatus.app.ui.theme.UplinkStatusTheme

/**
 * Stage 2 added the `POST_NOTIFICATIONS` runtime-permission request/rationale flow (the
 * status-bar icon can't be shown at all without it) and starts [UplinkStatusService] once
 * it's granted. Stage 3 adds the real settings screen behind that gate: once notifications
 * are allowed, [SettingsScreen] reads/writes preferences directly through the same
 * DataStore-backed repository the service observes, so a change here is picked up by an
 * already-running service without needing to restart it (see
 * [UplinkStatusService]'s preferences-collector doc).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferencesRepository = DataStoreUplinkPreferencesRepository(
            applicationContext.uplinkPreferencesDataStore,
        )

        setContent {
            UplinkStatusTheme {
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
                    SettingsScreen(repository = preferencesRepository)
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

    /**
     * The one moment this app is guaranteed to be looking when a permission granted *outside*
     * it takes effect. Granting precise location from the system app-info screen changes what
     * the service is allowed to read about the current WiFi network, but the platform will not
     * re-deliver that network's capabilities just because an app's permissions changed — see
     * [LocationPermissionStatus]. Coming back to this activity is the app's first opportunity to
     * notice, and reporting here is what turns it into a fresh connectivity read.
     *
     * Redundant with the settings screen's own permission-result reporting for the in-app
     * request path, and deliberately so: [LocationPermissionStatus.report] ignores a state it
     * has already recorded, so whichever of the two observes the change first is the one that
     * announces it, and the other costs nothing.
     */
    override fun onResume() {
        super.onResume()
        LocationPermissionStatus.report(hasLocationPermission())
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun startUplinkService() {
        ContextCompat.startForegroundService(this, UplinkStatusService.createStartIntent(this))
    }
}
