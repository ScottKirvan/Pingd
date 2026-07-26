package com.uplinkstatus.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferences
import com.uplinkstatus.app.prefs.UplinkPreferencesRepository
import com.uplinkstatus.app.prefs.isValidHostname
import com.uplinkstatus.app.service.UplinkStatusService
import com.uplinkstatus.app.state.UplinkActivityStatus
import com.uplinkstatus.app.state.UplinkRuntimeStatus
import com.uplinkstatus.core.probe.ProbeTarget
import kotlinx.coroutines.launch

// Compose test tags: stable identifiers for UI tests, since some of the controls below
// (Switch, dropdown entries) aren't reliably targetable by visible text alone once there's
// more than one on screen with similar labels.
const val TAG_MASTER_TOGGLE = "settings_master_toggle"
const val TAG_HIDE_WHEN_DISABLED_TOGGLE = "settings_hide_when_disabled_toggle"
const val TAG_NETWORK_SCOPE_DROPDOWN = "settings_network_scope_dropdown"
const val TAG_SCOPE_WIFI_ONLY = "settings_scope_wifi_only"
const val TAG_SCOPE_ANY_CONNECTION = "settings_scope_any_connection"
const val TAG_SCOPE_CELLULAR_ONLY = "settings_scope_cellular_only"
const val TAG_SCOPE_SSID_WHITELIST = "settings_scope_ssid_whitelist"
const val TAG_SSID_INPUT = "settings_ssid_input"
const val TAG_SSID_ADD_BUTTON = "settings_ssid_add_button"
const val TAG_PING_TARGET_DROPDOWN = "settings_ping_target_dropdown"
const val TAG_PING_TARGET_DEFAULT = "settings_ping_target_default"
const val TAG_PING_TARGET_ALTERNATE = "settings_ping_target_alternate"
const val TAG_PING_TARGET_CUSTOM_OPTION = "settings_ping_target_custom_option"
const val TAG_PING_TARGET_CUSTOM_INPUT = "settings_ping_target_custom_input"
const val TAG_PING_TARGET_CUSTOM_SAVE = "settings_ping_target_custom_save"
const val TAG_STATUS_LINE = "settings_status_line"

/** Test tag for a whitelist entry's remove button; one per SSID, so it's parameterized. */
fun ssidRemoveButtonTag(ssid: String) = "settings_ssid_remove_$ssid"

/** Visual weight for a disabled control group -- Material's own convention for "present but
 * not interactive," not an arbitrary number. */
private const val DISABLED_ALPHA = 0.38f

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
 *
 * Everything below the master toggle is visually dimmed and non-interactive while the master
 * toggle itself is off — with the whole feature disabled, there's nothing for those settings
 * to affect right now, and graying them out makes that legible at a glance instead of leaving
 * a settings screen that looks fully live while doing nothing.
 */
@Composable
fun SettingsScreen(
    repository: UplinkPreferencesRepository,
    modifier: Modifier = Modifier,
) {
    val preferences by repository.preferencesFlow.collectAsState(initial = UplinkPreferences())
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // The service applying a settings change (writing the DataStore value, that reaching its
    // collector, and it actually calling startForeground()/stopSelf()) is three separate
    // asynchronous steps, not one atomic operation -- there's a real, measurable window where
    // "what's requested" and "what's actually running" disagree. `isPending` locks every
    // control on this screen for exactly that window, using [UplinkRuntimeStatus]'s sequence
    // number (not the visibility value alone, which can't distinguish "nothing happened yet"
    // from "the service re-confirmed the same state it was already in") to know when the
    // service has genuinely caught up, rather than guessing at a delay.
    val activityStatusText by UplinkActivityStatus.text.collectAsState()
    val runtimeReport by UplinkRuntimeStatus.reports.collectAsState()
    var pendingBaselineSequence by remember { mutableStateOf<Int?>(null) }
    val isPending = pendingBaselineSequence?.let { runtimeReport.sequence <= it } ?: false
    fun markChangePending() {
        pendingBaselineSequence = runtimeReport.sequence
        ensureServiceRunning(context)
    }

    val controlsEnabled = preferences.masterToggleEnabled && !isPending

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
    // Seeded from whether the *persisted* host is already a custom one (so reopening the
    // screen with a saved custom host shows the field right away), but settable
    // independently of that -- selecting "Custom" in the dropdown reveals the field before
    // anything is actually saved, and picking a preset hides it again immediately.
    var showCustomHostInput by rememberSaveable(preferences.pingTargetHost) {
        mutableStateOf(!isPresetHost(preferences.pingTargetHost))
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Content draws behind the status/navigation bars under enableEdgeToEdge()
                // by design -- this is what keeps it from actually sitting under them (and,
                // at the bottom, out of the way of the system gesture-nav swipe area, which
                // it was otherwise competing with for touch input).
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(text = "Uplink status settings", style = MaterialTheme.typography.titleLarge)

            SettingsToggleRow(
                title = "Enable uplink status icon",
                description = "Master on/off switch for the whole feature.",
                checked = preferences.masterToggleEnabled,
                enabled = !isPending,
                tag = TAG_MASTER_TOGGLE,
                onCheckedChange = { enabled ->
                    markChangePending()
                    coroutineScope.launch { repository.setMasterToggleEnabled(enabled) }
                },
            )

            // Micro-log transparency: the same text the notification itself shows (see
            // UplinkActivityStatus's doc), reused here so a user who wants more detail than
            // the icon alone conveys can see what the service is actually doing right now --
            // looking for a whitelisted SSID, a probe failing, starting up, hidden, etc.
            if (activityStatusText != null) {
                Text(
                    text = "Status: " + activityStatusText.orEmpty().removePrefix("Uplink: "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(TAG_STATUS_LINE),
                )
            }

            Column(
                modifier = Modifier.alpha(if (controlsEnabled) 1f else DISABLED_ALPHA),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // --- Network scope ---
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Network scope", style = MaterialTheme.typography.titleMedium)

                    NetworkScopeDropdown(
                        selected = preferences.networkScope,
                        enabled = controlsEnabled,
                        onSelect = { scope ->
                            // Per spec: ACCESS_FINE_LOCATION is requested only at the point
                            // the user actually turns SSID whitelisting on, never up front.
                            // Reading the live SSID to match against this list is Stage 4's
                            // job; this request just gets the permission in place ahead of
                            // that, exactly when the user opts into needing it.
                            if (scope == NetworkScope.SSID_WHITELIST && !locationPermissionGranted) {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            }
                            markChangePending()
                            coroutineScope.launch { repository.setNetworkScope(scope) }
                        },
                    )

                    if (preferences.networkScope == NetworkScope.SSID_WHITELIST) {
                        SsidWhitelistEditor(
                            ssids = preferences.ssidWhitelist,
                            enabled = controlsEnabled,
                            newSsidText = newSsidText,
                            onNewSsidTextChange = { newSsidText = it },
                            onAdd = {
                                val trimmed = newSsidText.trim()
                                if (trimmed.isNotEmpty() && trimmed !in preferences.ssidWhitelist) {
                                    markChangePending()
                                    coroutineScope.launch {
                                        repository.setSsidWhitelist(preferences.ssidWhitelist + trimmed)
                                    }
                                    newSsidText = ""
                                }
                            },
                            onRemove = { ssid ->
                                markChangePending()
                                coroutineScope.launch {
                                    repository.setSsidWhitelist(preferences.ssidWhitelist - ssid)
                                }
                            },
                        )
                    }
                }

                SettingsToggleRow(
                    title = "Hide icon when out of scope",
                    description = "Otherwise the icon stays visible, dimmed, while enabled but out of scope.",
                    checked = preferences.hideWhenDisabled,
                    enabled = controlsEnabled,
                    tag = TAG_HIDE_WHEN_DISABLED_TOGGLE,
                    onCheckedChange = { hide ->
                        markChangePending()
                        coroutineScope.launch { repository.setHideWhenDisabled(hide) }
                    },
                )

                HorizontalDivider()

                // --- Ping target host ---
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Ping target host", style = MaterialTheme.typography.titleMedium)

                    PingTargetDropdown(
                        host = preferences.pingTargetHost,
                        enabled = controlsEnabled,
                        onSelectPreset = { host ->
                            showCustomHostInput = false
                            markChangePending()
                            coroutineScope.launch { repository.setPingTargetHost(host) }
                        },
                        onSelectCustom = { showCustomHostInput = true },
                    )

                    // Only shown once "Custom" is actually the active selection -- not
                    // always-visible plumbing for a preset the dropdown already covers.
                    if (showCustomHostInput) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                enabled = controlsEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(TAG_PING_TARGET_CUSTOM_INPUT),
                            )
                            Button(
                                enabled = controlsEnabled,
                                onClick = {
                                    val trimmed = customHostText.trim()
                                    if (isValidHostname(trimmed)) {
                                        customHostError = false
                                        markChangePending()
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
    }
}

/**
 * A titled on/off row shared by the master toggle and "hide when out of scope." Both the
 * label/description [Column] and the [Switch] live in a `fillMaxWidth` [Row] with the text
 * column given `Modifier.weight(1f)` — that's what makes every row's switch line up at the
 * same x position regardless of how long its description text is, and what keeps a long
 * description wrapping within its own column instead of shoving the switch off the edge of
 * the screen (the previous layout used `Arrangement.SpaceBetween` with an unconstrained
 * text column, which did exactly that once a description got long enough).
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
        ) {
            Text(text = title)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkScopeDropdown(
    selected: NetworkScope,
    enabled: Boolean,
    onSelect: (NetworkScope) -> Unit,
) {
    val options = listOf(
        Triple(NetworkScope.WIFI_ONLY, "Wi-Fi only (default)", TAG_SCOPE_WIFI_ONLY),
        Triple(NetworkScope.ANY_CONNECTION, "Any connection (Wi-Fi + cellular)", TAG_SCOPE_ANY_CONNECTION),
        Triple(NetworkScope.CELLULAR_ONLY, "Cellular only", TAG_SCOPE_CELLULAR_ONLY),
        Triple(NetworkScope.SSID_WHITELIST, "Specific Wi-Fi networks (SSID whitelist)", TAG_SCOPE_SSID_WHITELIST),
    )
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.first { it.first == selected }.second

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .testTag(TAG_NETWORK_SCOPE_DROPDOWN),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (scope, label, tag) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(scope)
                    },
                    modifier = Modifier.testTag(tag),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PingTargetDropdown(
    host: String,
    enabled: Boolean,
    onSelectPreset: (String) -> Unit,
    onSelectCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when (host) {
        ProbeTarget.DEFAULT_HOST -> "Cloudflare (${ProbeTarget.DEFAULT_HOST})"
        ProbeTarget.ALTERNATE_HOST -> "Google (${ProbeTarget.ALTERNATE_HOST})"
        else -> "Custom ($host)"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .testTag(TAG_PING_TARGET_DROPDOWN),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Cloudflare (${ProbeTarget.DEFAULT_HOST})") },
                onClick = {
                    expanded = false
                    onSelectPreset(ProbeTarget.DEFAULT_HOST)
                },
                modifier = Modifier.testTag(TAG_PING_TARGET_DEFAULT),
            )
            DropdownMenuItem(
                text = { Text("Google (${ProbeTarget.ALTERNATE_HOST})") },
                onClick = {
                    expanded = false
                    onSelectPreset(ProbeTarget.ALTERNATE_HOST)
                },
                modifier = Modifier.testTag(TAG_PING_TARGET_ALTERNATE),
            )
            DropdownMenuItem(
                text = { Text("Custom") },
                onClick = {
                    // Selecting "Custom" alone doesn't persist anything until the user
                    // actually enters and saves a valid hostname below -- there's nothing
                    // valid to fall back to otherwise. It does reveal the input, though.
                    expanded = false
                    onSelectCustom()
                },
                modifier = Modifier.testTag(TAG_PING_TARGET_CUSTOM_OPTION),
            )
        }
    }
}

@Composable
private fun SsidWhitelistEditor(
    ssids: Set<String>,
    enabled: Boolean,
    newSsidText: String,
    onNewSsidTextChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
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
                    enabled = enabled,
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
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TAG_SSID_INPUT),
            )
            Button(onClick = onAdd, enabled = enabled, modifier = Modifier.testTag(TAG_SSID_ADD_BUTTON)) {
                Text("Add")
            }
        }
    }
}

private fun isPresetHost(host: String): Boolean =
    host == ProbeTarget.DEFAULT_HOST || host == ProbeTarget.ALTERNATE_HOST

/**
 * Called from every preference-write callback on this screen, not just the master toggle.
 * [UplinkStatusService] tears itself down completely (`stopSelf()`) on any transition to
 * `HIDDEN` -- once that's happened, writing a new preference value alone does nothing, since
 * there's no running service left to observe the change; only [Context.startForegroundService]
 * makes Android deliver a fresh `onStartCommand`, which is what actually re-evaluates
 * visibility against the new preferences and can bring the service back. Safe to call
 * unconditionally (including from a change that will resolve to HIDDEN again) --
 * `onStartCommand`'s own preferences/connectivity observation is idempotent and figures out
 * the real answer regardless of whether this call was actually necessary.
 */
private fun ensureServiceRunning(context: Context) {
    ContextCompat.startForegroundService(context, UplinkStatusService.createStartIntent(context))
}
