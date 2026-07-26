package com.uplinkstatus.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.uplinkstatus.app.R
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.core.tracer.AckSource
import com.uplinkstatus.core.tracer.BarPosition
import com.uplinkstatus.core.tracer.CycleEvent
import com.uplinkstatus.core.tracer.FreezeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // --- CycleEvent.Frozen: must update accessibility text, never the icon, and must not --
    // --- spam notify() on repeated immediate retries with the same reason -----------------

    @Test
    fun `frozen event before any ack has ever fired still posts distinct failure text`() {
        // No CycleEvent.Advanced has happened yet this session -- a freeze from the very
        // first probe attempt must still be surfaced, not silently swallowed because
        // nothing was "already showing" to compare against.
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE))

        val posted = checkNotNull(postedNotification())
        assertEquals(R.drawable.ic_scan_1, posted.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_probe_failure), textOf(posted))
    }

    @Test
    fun `generic probe failure after a connected notification keeps the same icon but shows distinct failure text`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 10))
        val before = checkNotNull(postedNotification())
        val beforeIcon = before.smallIcon.resId

        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE))

        val after = checkNotNull(postedNotification())
        // Per spec: freezing in place is the only failure indication for the icon -- no
        // distinct "lost" frame -- so the icon must be unchanged from the last ack.
        assertEquals(beforeIcon, after.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_probe_failure), textOf(after))
    }

    @Test
    fun `dns resolution failure shows text genuinely distinct from generic probe failure`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 10))
        val before = checkNotNull(postedNotification())
        val beforeIcon = before.smallIcon.resId

        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.DNS_RESOLUTION_FAILURE))

        val after = checkNotNull(postedNotification())
        assertEquals(beforeIcon, after.smallIcon.resId)
        val dnsText = context.getString(R.string.notification_text_dns_failure)
        assertEquals(dnsText, textOf(after))
        assertNotEquals(context.getString(R.string.notification_text_probe_failure), dnsText)
    }

    @Test
    fun `repeated frozen events with the same reason during immediate no-back-off retries post only once`() {
        // Per spec: "Only call notify() on an ack (tracer advance) or a state transition" --
        // a sustained outage retries immediately with no back-off, so ProbeCycleRunner emits
        // one Frozen per attempt. Repeats that don't change *why* it's frozen must not each
        // trigger a fresh notify() call -- that would be exactly the "bare timer tick" spam
        // the spec rules out, even though the visible end state (same icon/text) would look
        // identical either way. notifyCallCount is the seam that lets this test tell the
        // difference between "posted once" and "posted five times with identical content."
        repeat(5) {
            controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_4, FreezeReason.PROBE_FAILURE))
        }

        assertEquals(1, controller.notifyCallCount)
        val posted = checkNotNull(postedNotification())
        assertEquals(R.drawable.ic_scan_4, posted.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_probe_failure), textOf(posted))
    }

    @Test
    fun `freeze reason changing mid-outage posts fresh distinct text, not suppressed as a repeat`() {
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.PROBE_FAILURE))
        val callsAfterFirstFreeze = controller.notifyCallCount

        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.DNS_RESOLUTION_FAILURE))

        assertEquals(callsAfterFirstFreeze + 1, controller.notifyCallCount)
        val posted = checkNotNull(postedNotification())
        assertEquals(context.getString(R.string.notification_text_dns_failure), textOf(posted))
    }

    @Test
    fun `an ack after a freeze resumes connected text and un-suppresses the next freeze`() {
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.PROBE_FAILURE))
        val callsAfterFreeze = controller.notifyCallCount

        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_3, AckSource.PROBE_SUCCESS, latencyMs = 8))
        assertEquals(callsAfterFreeze + 1, controller.notifyCallCount)
        assertEquals(
            context.getString(R.string.notification_text_connected_with_latency, 8),
            textOf(checkNotNull(postedNotification())),
        )

        // A fresh freeze with the *same* reason as before the ack is not treated as a
        // duplicate of the pre-ack freeze -- the connected state in between resets tracking.
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_3, FreezeReason.PROBE_FAILURE))
        assertEquals(callsAfterFreeze + 2, controller.notifyCallCount)
    }

    @Test
    fun `resetSession clears freeze de-duplication so a fresh session's first freeze always posts`() {
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE))
        val callsBeforeReset = controller.notifyCallCount

        controller.resetSession()
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE))

        assertEquals(callsBeforeReset + 1, controller.notifyCallCount)
    }

    // --- Visibility-state notification builders -----------------------------------------

    @Test
    fun `notificationForDisabled builds the sixth icon frame with paused text`() {
        val disabled = controller.notificationForDisabled(NetworkScope.WIFI_ONLY)

        assertEquals(R.drawable.ic_scan_disabled, disabled.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_disabled), textOf(disabled))
    }

    @Test
    fun `notificationForDisabled under an SSID whitelist scope uses more specific text`() {
        val disabled = controller.notificationForDisabled(NetworkScope.SSID_WHITELIST)

        assertEquals(R.drawable.ic_scan_disabled, disabled.smallIcon.resId)
        assertEquals(
            context.getString(R.string.notification_text_disabled_ssid_scope),
            textOf(disabled),
        )
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
