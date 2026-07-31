package com.uplinkstatus.app.prefs

import com.uplinkstatus.core.probe.ProbeTarget

/**
 * The full set of user-editable preferences from the spec's "User Preferences" section, as
 * a single immutable snapshot. [UplinkPreferencesRepository.preferencesFlow] emits one of
 * these on every change; nothing in `:app` should read an individual DataStore key directly.
 *
 * Defaults here matter: they're what a fresh install (no DataStore file yet) reads as, and
 * they're chosen to match [com.uplinkstatus.core.visibility.VisibilityDecider]'s
 * `ENABLED`-by-default posture that Stage 2's `VisibilityInputs` stand-in used, plus the
 * spec's documented defaults for scope and ping target.
 */
data class UplinkPreferences(
    val masterToggleEnabled: Boolean = true,
    val hideWhenDisabled: Boolean = false,
    val networkScope: NetworkScope = NetworkScope.WIFI_ONLY,
    val ssidWhitelist: Set<String> = emptySet(),
    val pingTargetHost: String = ProbeTarget.DEFAULT_HOST,
)
