package com.uplinkstatus.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.uplinkstatus.app.R
import com.uplinkstatus.core.tracer.AckSource
import com.uplinkstatus.core.tracer.BarPosition
import com.uplinkstatus.core.tracer.CycleEvent
import com.uplinkstatus.core.tracer.FreezeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies [UplinkNotificationController]'s reaction to [CycleEvent]s and its
 * visibility-transition notification builders — this is the test coverage for the spec's
 * "notify() only on an ack or a state transition" rule and for the icon/content mapping,
 * run on the JVM via Robolectric (no device/emulator needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UplinkNotificationControllerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val notificationManager: NotificationManager =
        checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val controller = UplinkNotificationController(context)

    @Before
    fun grantNotificationsPermission() {
        // UplinkNotificationController checks POST_NOTIFICATIONS before calling notify()
        // (see its class doc) -- Robolectric doesn't auto-grant dangerous/runtime
        // permissions just because they're manifest-declared, so tests exercising the
        // notify() path need to grant it explicitly, same as a real device would after the
        // user accepts MainActivity's permission prompt.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun postedNotification(): Notification? =
        shadowOf(notificationManager).getNotification(UplinkNotificationController.NOTIFICATION_ID)

    private fun textOf(notification: Notification): CharSequence? = shadowOf(notification).contentText

    private fun titleOf(notification: Notification): CharSequence? = shadowOf(notification).contentTitle

    // --- CycleEvent.Advanced: the ack path that must update the notification ------------

    @Test
    fun `advanced with a fresh probe-success latency posts the matching icon and latency text`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_3, AckSource.PROBE_SUCCESS, latencyMs = 42))

        val posted = checkNotNull(postedNotification())
        assertEquals(R.drawable.ic_scan_3, posted.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_connected_with_latency, 42), textOf(posted))
        assertEquals(context.getString(R.string.notification_title), titleOf(posted))
    }

    @Test
    fun `automatic ack after a probe-success ack keeps the last known latency but updates the icon`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 17))
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_3, AckSource.AUTOMATIC, latencyMs = null))

        val posted = checkNotNull(postedNotification())
        assertEquals(R.drawable.ic_scan_3, posted.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_connected_with_latency, 17), textOf(posted))
    }

    @Test
    fun `automatic ack with no prior latency shows unknown-latency text`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.AUTOMATIC, latencyMs = null))

        val posted = checkNotNull(postedNotification())
        assertEquals(R.drawable.ic_scan_2, posted.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_connected_unknown_latency), textOf(posted))
    }

    @Test
    fun `resetSession clears remembered latency so a fresh cycle does not show stale latency`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 99))
        controller.resetSession()
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.AUTOMATIC, latencyMs = null))

        val posted = checkNotNull(postedNotification())
        assertEquals(context.getString(R.string.notification_text_connected_unknown_latency), textOf(posted))
    }

    // --- CycleEvent.Frozen: must NOT trigger notify() ------------------------------------

    @Test
    fun `frozen event never posts a notification`() {
        // Per spec: "Only call notify() on an ack (tracer advance) or a state transition" —
        // a frozen attempt (no ack fired) is neither, so this must be a pure no-op.
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.PROBE_FAILURE))

        assertNull(postedNotification())
    }

    @Test
    fun `frozen event after an existing notification leaves it completely unchanged`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 10))
        val before = checkNotNull(postedNotification())
        val beforeText = textOf(before)
        val beforeIcon = before.smallIcon.resId

        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.DNS_RESOLUTION_FAILURE))

        val after = checkNotNull(postedNotification())
        assertEquals(beforeIcon, after.smallIcon.resId)
        assertEquals(beforeText, textOf(after))
    }

    @Test
    fun `repeated frozen events during immediate no-back-off retries never post anything`() {
        repeat(5) {
            controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_4, FreezeReason.PROBE_FAILURE))
        }

        assertNull(postedNotification())
    }

    // --- Visibility-state notification builders -----------------------------------------

    @Test
    fun `notificationForDisabled builds the sixth icon frame with paused text`() {
        val disabled = controller.notificationForDisabled()

        assertEquals(R.drawable.ic_scan_disabled, disabled.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_disabled), textOf(disabled))
    }

    @Test
    fun `notificationForEnabled builds the correct icon for a given bar position`() {
        val enabled = controller.notificationForEnabled(BarPosition.BAR_5)

        assertEquals(R.drawable.ic_scan_5, enabled.smallIcon.resId)
    }

    @Test
    fun `hide cancels the notification entirely rather than showing a seventh icon`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 5))
        checkNotNull(postedNotification()) // sanity: something is showing before hide()

        controller.hide()

        assertNull(postedNotification())
    }
}
