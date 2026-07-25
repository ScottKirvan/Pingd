package com.uplinkstatus.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 3 replaces Stage 2's `VisibilityInputs` (a mutable singleton bundling all three
 * inputs to [com.uplinkstatus.core.visibility.VisibilityDecider]) because two of those three
 * inputs — the master toggle and hide-when-disabled — are now real, persisted
 * [com.uplinkstatus.app.prefs.UplinkPreferences] fields read through
 * [com.uplinkstatus.app.prefs.UplinkPreferencesRepository]'s `Flow`. Folding them back into
 * this object would mean keeping two sources of truth for the same values in sync by hand.
 *
 * The third input — "is the current network in scope" — has no real source yet: that's
 * Stage 4's `ConnectivityManager.NetworkCallback` job, matching live connectivity against
 * [com.uplinkstatus.app.prefs.UplinkPreferences.networkScope] /
 * `.ssidWhitelist`. Until then, this is what Stage 2's `VisibilityInputs.networkInScope`
 * was: a manually-set stand-in, defaulting to "in scope" so a fresh install resolves to
 * `ENABLED` rather than silently sitting in `DISABLED`.
 *
 * It's exposed as a [StateFlow] (not just a plain `var`, though [inScope] still reads/writes
 * like one) specifically so [com.uplinkstatus.app.service.UplinkStatusService] can `combine`
 * it with the real preferences `Flow` and react continuously to either changing — the same
 * shape Stage 4's real `NetworkCallback`-backed flow will need to slot into.
 *
 * TODO(Stage 4): replace this object (and the manual `inScope` setter) with a real
 * `ConnectivityManager.NetworkCallback`-driven flow that matches live connectivity against
 * the persisted network-scope preference.
 */
object NetworkScopeStatus {
    private val state = MutableStateFlow(true)

    val inScopeFlow: StateFlow<Boolean> = state.asStateFlow()

    var inScope: Boolean
        get() = state.value
        set(value) {
            state.value = value
        }
}
