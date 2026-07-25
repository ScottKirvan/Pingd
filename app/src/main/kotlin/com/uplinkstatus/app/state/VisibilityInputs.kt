package com.uplinkstatus.app.state

import com.uplinkstatus.core.visibility.UplinkVisibility
import com.uplinkstatus.core.visibility.VisibilityDecider

/**
 * Stage 2 stand-in for the enabled/disabled/hidden inputs that Stage 3 (settings screen +
 * DataStore preferences) and Stage 4 (live `ConnectivityManager.NetworkCallback` scope
 * matching) will eventually supply for real. Neither of those exists yet, and building
 * throwaway settings UI or fake connectivity plumbing to feed this stage would be reaching
 * into their scope — so for now this is deliberately just three in-memory booleans with
 * defaults that resolve to `ENABLED` (master toggle on, network in scope), computed through
 * the same [VisibilityDecider] Stage 1 already built and tested.
 *
 * This object is also [UplinkStatusService][com.uplinkstatus.app.service.UplinkStatusService]'s
 * test-only entry point for driving visibility transitions: a test (or, later, the real
 * Stage 3/4 wiring) sets these fields and re-triggers the service, rather than the service
 * inventing its own ad hoc mechanism for "what should the icon be doing right now."
 *
 * Deliberately dumb: no persistence, no listeners, no feature logic. Stage 3/4 replace the
 * fields here with real reads from DataStore / ConnectivityManager — they should delete
 * this object rather than build on top of it.
 */
object VisibilityInputs {
    @Volatile
    var masterToggleEnabled: Boolean = true

    @Volatile
    var networkInScope: Boolean = true

    @Volatile
    var hideWhenDisabled: Boolean = false

    fun currentVisibility(): UplinkVisibility = VisibilityDecider.decide(
        masterToggleEnabled = masterToggleEnabled,
        networkInScope = networkInScope,
        hideWhenDisabled = hideWhenDisabled,
    )
}
