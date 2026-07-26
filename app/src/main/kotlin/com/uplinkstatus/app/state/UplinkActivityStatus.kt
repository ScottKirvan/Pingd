package com.uplinkstatus.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest human-readable line describing what [com.uplinkstatus.app.service.UplinkStatusService]
 * is actually doing right now -- "connected, 42ms", "connection trouble, retrying…", "waiting
 * for a whitelisted Wi-Fi network…", and so on. Purely for on-screen transparency in
 * `SettingsScreen`; nothing reads this to make a decision (that's [UplinkRuntimeStatus]'s job,
 * kept separate on purpose so this object's only responsibility is "what to show," not "has
 * the service caught up yet").
 *
 * [com.uplinkstatus.app.service.UplinkNotificationController] is the source for most of these
 * strings -- it already builds this exact text for the notification itself, so this reuses
 * that instead of maintaining a second copy of the same wording.
 */
object UplinkActivityStatus {
    private val state = MutableStateFlow<String?>(null)

    val text: StateFlow<String?> = state.asStateFlow()

    fun update(text: String) {
        state.value = text
    }

    /** Process-wide singleton; tests must reset it between runs. Production code never calls
     * this. */
    internal fun resetForTest() {
        state.value = null
    }
}
