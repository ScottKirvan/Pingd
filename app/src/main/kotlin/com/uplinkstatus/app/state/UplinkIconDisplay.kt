package com.uplinkstatus.app.state

import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The exact drawable resource [com.uplinkstatus.app.service.UplinkNotificationController] is
 * showing as the status-bar icon right now, published so an in-app surface (the settings
 * screen's scanner preview) can render the identical frame in real time without depending on
 * the notification itself — which requires `POST_NOTIFICATIONS` and isn't observable from
 * inside the app once posted.
 *
 * Reported from the one function that builds every notification this app ever posts
 * ([com.uplinkstatus.app.service.UplinkNotificationController.buildNotification]), so this is
 * never a second interpretation of "what icon should show" — it's the same value, at the same
 * moment. `null` means `HIDDEN`: per spec, the absence of the icon, not a seventh frame.
 */
object UplinkIconDisplay {
    private val state = MutableStateFlow<Int?>(null)

    val iconRes: StateFlow<Int?> = state.asStateFlow()

    fun report(@DrawableRes iconRes: Int?) {
        state.value = iconRes
    }

    /** Process-wide singleton; tests must reset it between runs. Production code never calls
     * this. */
    internal fun resetForTest() {
        state.value = null
    }
}
