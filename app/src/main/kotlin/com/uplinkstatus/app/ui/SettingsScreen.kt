package com.uplinkstatus.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferences
import com.uplinkstatus.app.prefs.UplinkPreferencesRepository
import com.uplinkstatus.app.prefs.isValidHostname
import com.uplinkstatus.core.probe.ProbeTarget
import kotlinx.coroutines.launch

// Compose test tags: stable identifiers for UI tests, since some of the controls below
// (Switch, RadioButton) aren't reliably targetable by visible text alone once there's more
// than one on screen with similar labels.
const val TAG_MASTER_TOGGLE = "settings_master_toggle"
const val TAG_HIDE_WHEN_DISABLED_TOGGLE = "settings_hide_when_disabled_toggle"
const val TAG_SCOPE_WIFI_ONLY = "settings_scope_wifi_only"
const val TAG_SCOPE_ANY_CONNECTION = "settings_scope_any_connection"
const val TAG_SCOPE_CELLULAR_ONLY = "settings_scope_cellular_only"
const val TAG_SCOPE_SSID_WHITELIST = "settings_scope_ssid_whitelist"
const val TAG_SSID_INPUT = "settings_ssid_input"
const val TAG_SSID_ADD_BUTTON = "settings_ssid_add_button"
const val TAG_PING_TARGET_DEFAULT = "settings_ping_target_default"
const val TAG_PING_TARGET_ALTERNATE = "settings_ping_target_alternate"
const val TAG_PING_TARGET_CUSTOM_OPTION = "settings_ping_target_custom_option"
const val TAG_PING_TARGET_CUSTOM_INPUT = "settings_ping_target_custom_input"
const val TAG_PING_TARGET_CUSTOM_SAVE = "settings_ping_target_custom_save"

/** Test tag for a whitelist entry's remove button; one per SSID, so it's parameterized. */
fun ssidRemoveButtonTag(ssid: String) = "settings_ssid_remove_$ssid"

/**
 * Stage 3's real settings screen — replaces Stage 0's throwaway `PlaceholderScreen`. Covers
 * every preference in the spec's "User Preferences" section: master enable/disable, hide
 * when disabled, network scope (including SSID whitelist management), and ping target host
 * (defaults + custom override).
 *
 * Deliberately does not read or display "is the current network in scope right now" —
 * that's Stage 4's live `ConnectivityManager` job (see
 * [com.uplinkstatus.app.state.NetworkScopeStatus]); this screen only edits the *settings*
 * that decide what counts as in scope, not the live signal itself.
 *
 * Talks directly to [UplinkPreferencesRepository] rather than through a `ViewModel` — the
 * repository's `Flow` is already the single source of truth
 * [com.uplinkstatus.app.service.UplinkStatusService] reads from, and this screen has no
 * other state to own (no loading/error states beyond "not yet loaded", handled by
 * [UplinkPreferences]'s own defaults as the `collectAsState` seed). Introducing a
 * `ViewModel` layer with nothing of its own to coordinate would be structure for its own
 * sake.
 */
@Composable
fun SettingsScreen(
    repository: UplinkPreferencesRepository,
    modifier: Modifier = Modifier,
) {
    val preferences by repository.preferencesFlow.collectAsState(initial = UplinkPreferences())
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // Requests FINE and COARSE together, not FINE alone: Android 12+ lets the user grant
    // only COARSE even when an app asks for FINE, and (separately from that OS behavior)
    // lint's CoarseFineLocation check requires an app that wants FINE to declare/request
    // COARSE alongside it. This app has no use for coarse-only location -- reading the
    // connected SSID (Stage 4) needs FINE -- so [locationPermissionGranted] below only ever
    // reflects the FINE grant.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationPermissionGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    var newSsidText by rememberSaveable { mutableStateOf("") }
    var customHostText by rememberSaveable(preferences.pingTargetHost) {
        mutableStateOf(
            if (isPresetHost(preferences.pingTargetHost)) "" else preferences.pingTargetHost,
        )
    }
    var customHostError by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(text = "Uplink status settings", style = MaterialTheme.typography.titleLarge)

            // --- Master toggle ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = preferences.masterToggleEnabled,
                        onValueChange = { enabled ->
                            coroutineScope.launch { repository.setMasterToggleEnabled(enabled) }
                        },
                    )
                    .testTag(TAG_MASTER_TOGGLE),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = "Enable uplink status icon")
                    Text(
                        text = "Master on/off switch for the whole feature.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = preferences.masterToggleEnabled, onCheckedChange = null)
            }

            // --- Hide when disabled ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = preferences.hideWhenDisabled,
                        onValueChange = { hide ->
                            coroutineScope.launch { repository.setHideWhenDisabled(hide) }
                        },
                    )
                    .testTag(TAG_HIDE_WHEN_DISABLED_TOGGLE),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = "Hide icon when out of scope")
                    Text(
                        text = "Otherwise the icon stays visible, dimmed, while enabled but out of scope.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = preferences.hideWhenDisabled, onCheckedChange = null)
            }

            HorizontalDivider()

            // --- Network scope ---
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Network scope", style = MaterialTheme.typography.titleMedium)

                NetworkScopeOption(
                    label = "Wi-Fi only (default)",
                    selected = preferences.networkScope == NetworkScope.WIFI_ONLY,
                    tag = TAG_SCOPE_WIFI_ONLY,
                    onSelect = {
                        coroutineScope.launch { repository.setNetworkScope(NetworkScope.WIFI_ONLY) }
                    },
                )
                NetworkScopeOption(
                    label = "Any connection (Wi-Fi + cellular)",
                    selected = preferences.networkScope == NetworkScope.ANY_CONNECTION,
                    tag = TAG_SCOPE_ANY_CONNECTION,
                    onSelect = {
                        coroutineScope.launch { repository.setNetworkScope(NetworkScope.ANY_CONNECTION) }
                    },
                )
                NetworkScopeOption(
                    label = "Cellular only",
                    selected = preferences.networkScope == NetworkScope.CELLULAR_ONLY,
                    tag = TAG_SCOPE_CELLULAR_ONLY,
                    onSelect = {
                        coroutineScope.launch { repository.setNetworkScope(NetworkScope.CELLULAR_ONLY) }
                    },
                )
                NetworkScopeOption(
                    label = "Specific Wi-Fi networks (SSID whitelist)",
                    selected = preferences.networkScope == NetworkScope.SSID_WHITELIST,
                    tag = TAG_SCOPE_SSID_WHITELIST,
                    onSelect = {
                        // Per spec: ACCESS_FINE_LOCATION is requested only at the point the
                        // user actually turns SSID whitelisting on, never up front. Reading
                        // the live SSID to match against this list is Stage 4's job; this
                        // request just gets the permission in place ahead of that, exactly
                        // when the user opts into needing it.
                        if (!locationPermissionGranted) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                        coroutineScope.launch { repository.setNetworkScope(NetworkScope.SSID_WHITELIST) }
                    },
                )

                if (preferences.networkScope == NetworkScope.SSID_WHITELIST) {
                    SsidWhitelistEditor(
                        ssids = preferences.ssidWhitelist,
                        newSsidText = newSsidText,
                        onNewSsidTextChange = { newSsidText = it },
                        onAdd = {
                            val trimmed = newSsidText.trim()
                            if (trimmed.isNotEmpty() && trimmed !in preferences.ssidWhitelist) {
                                coroutineScope.launch {
                                    repository.setSsidWhitelist(preferences.ssidWhitelist + trimmed)
                                }
                                newSsidText = ""
                            }
                        },
                        onRemove = { ssid ->
                            coroutineScope.launch {
                                repository.setSsidWhitelist(preferences.ssidWhitelist - ssid)
                            }
                        },
                    )
                }
            }

            HorizontalDivider()

            // --- Ping target host ---
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Ping target host", style = MaterialTheme.typography.titleMedium)

                NetworkScopeOption(
                    label = "Cloudflare (${ProbeTarget.DEFAULT_HOST})",
                    selected = preferences.pingTargetHost == ProbeTarget.DEFAULT_HOST,
                    tag = TAG_PING_TARGET_DEFAULT,
                    onSelect = {
                        coroutineScope.launch { repository.setPingTargetHost(ProbeTarget.DEFAULT_HOST) }
                    },
                )
                NetworkScopeOption(
                    label = "Google (${ProbeTarget.ALTERNATE_HOST})",
                    selected = preferences.pingTargetHost == ProbeTarget.ALTERNATE_HOST,
                    tag = TAG_PING_TARGET_ALTERNATE,
                    onSelect = {
                        coroutineScope.launch { repository.setPingTargetHost(ProbeTarget.ALTERNATE_HOST) }
                    },
                )
                val customSelected = !isPresetHost(preferences.pingTargetHost)
                NetworkScopeOption(
                    label = "Custom",
                    selected = customSelected,
                    tag = TAG_PING_TARGET_CUSTOM_OPTION,
                    onSelect = {
                        // Selecting "Custom" alone doesn't persist anything until the user
                        // actually enters and saves a valid hostname below -- there's
                        // nothing valid to fall back to otherwise.
                    },
                )

                // The custom-host field is always available (not just when "Custom" is
                // already the active selection) so the user can type a value and hit Save
                // in one step -- Save is itself what makes it the active pingTargetHost.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customHostText,
                        onValueChange = {
                            customHostText = it
                            customHostError = false
                        },
                        label = { Text("Custom hostname") },
                        isError = customHostError,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_PING_TARGET_CUSTOM_INPUT),
                    )
                    Button(
                        onClick = {
                            val trimmed = customHostText.trim()
                            if (isValidHostname(trimmed)) {
                                customHostError = false
                                coroutineScope.launch { repository.setPingTargetHost(trimmed) }
                            } else {
                                customHostError = true
                            }
                        },
                        modifier = Modifier.testTag(TAG_PING_TARGET_CUSTOM_SAVE),
                    ) {
                        Text("Save")
                    }
                }
                if (customHostError) {
                    Text(
                        text = "Enter a valid hostname (e.g. probe.example.com).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkScopeOption(
    label: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SsidWhitelistEditor(
    ssids: Set<String>,
    newSsidText: String,
    onNewSsidTextChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "Whitelisted networks", style = MaterialTheme.typography.bodyMedium)
        if (ssids.isEmpty()) {
            Text(text = "No networks added yet.", style = MaterialTheme.typography.bodySmall)
        }
        // A plain Column + forEach, not a LazyColumn: this list is nested inside the
        // screen's own `verticalScroll` (a realistic SSID whitelist is a handful of
        // entries, not a dataset that needs virtualization), and nesting one scrollable
        // inside another is exactly what Compose disallows -- a LazyColumn measured with
        // the infinite height a `verticalScroll` parent hands its child throws at runtime.
        ssids.toList().forEach { ssid ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = ssid)
                TextButton(
                    onClick = { onRemove(ssid) },
                    modifier = Modifier.testTag(ssidRemoveButtonTag(ssid)),
                ) {
                    Text("Remove")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newSsidText,
                onValueChange = onNewSsidTextChange,
                label = { Text("SSID") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TAG_SSID_INPUT),
            )
            Button(onClick = onAdd, modifier = Modifier.testTag(TAG_SSID_ADD_BUTTON)) {
                Text("Add")
            }
        }
    }
}

private fun isPresetHost(host: String): Boolean =
    host == ProbeTarget.DEFAULT_HOST || host == ProbeTarget.ALTERNATE_HOST
