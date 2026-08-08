package com.uplinkstatus.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.uplinkstatus.app.R
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.state.UplinkActivityStatus
import com.uplinkstatus.app.state.UplinkIconDisplay
import com.uplinkstatus.app.state.UplinkProbeHistory
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
        // Process-wide singleton -- reset so one test's reported activity (in particular, so
        // the "building a notification claims nothing" assertions below) can't be satisfied
        // or broken by whatever an unrelated test left behind.
        UplinkActivityStatus.resetForTest()
        // Same reasoning -- a previous test's last mirrored icon must not leak into this
        // one's "nothing reported yet" or "reported null" assertions.
        UplinkIconDisplay.resetForTest()
        // Same again, and load-bearing for the history assertions below: a leftover sample
        // from an earlier test would make "the automatic ack recorded nothing" pass or fail
        // for reasons that have nothing to do with this test's own events.
        UplinkProbeHistory.resetForTest()
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
    fun `the first notification of a fresh cycle says checking, not connected`() {
        // This one is built before the cycle's first probe has even been attempted, so
        // "connected" would be a guess. (An ack with no latency is a different case -- that
        // really happened -- and is still called connected; see the test above.)
        val enabled = controller.notificationForEnabled(BarPosition.START)

        assertEquals(context.getString(R.string.notification_text_checking), textOf(enabled))
        assertNotEquals(
            context.getString(R.string.notification_text_connected_unknown_latency),
            textOf(enabled),
        )
    }

    @Test
    fun `notificationForStarting shows the dim frame without claiming a network verdict`() {
        val starting = controller.notificationForStarting()

        assertEquals(R.drawable.ic_scan_disabled, starting.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_text_starting), textOf(starting))
        // Specifically not the DISABLED text: that names a reason ("network out of scope")
        // that nothing has established at the point this notification is posted.
        assertNotEquals(context.getString(R.string.notification_text_disabled), textOf(starting))
    }

    @Test
    fun `hide cancels the notification entirely rather than showing a seventh icon`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 5))
        checkNotNull(postedNotification()) // sanity: something is showing before hide()

        controller.hide()

        assertNull(postedNotification())
    }

    // --- The on-screen status line is fed by real events only, never by notification-building --

    /**
     * The architectural regression guard. Building notification content used to update the
     * status line as a blanket side effect, which meant a placeholder built to satisfy an
     * Android API deadline was indistinguishable, downstream, from a confirmed state — and
     * the placeholder's own text names a specific network verdict. Merely *asking this class
     * for a notification* must therefore claim nothing: the status line only ever moves on a
     * real cycle event (below) or a real visibility decision ([UplinkStatusServiceTest]).
     */
    @Test
    fun `building notification content does not by itself report any status`() {
        controller.notificationForStarting()
        controller.notificationForDisabled(NetworkScope.WIFI_ONLY)
        controller.notificationForDisabled(NetworkScope.SSID_WHITELIST)
        controller.notificationForEnabled(BarPosition.START)

        assertNull(
            "a notification builder spoke for the status line",
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `an ack reports connected with the measured latency`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 23))

        assertEquals(
            UplinkActivityStatus.Activity.Connected(latencyMs = 23),
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `a freeze reports connection trouble carrying the reason that caused it`() {
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.PROBE_FAILURE))
        assertEquals(
            UplinkActivityStatus.Activity.ConnectionTrouble(FreezeReason.PROBE_FAILURE),
            UplinkActivityStatus.activity.value,
        )

        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.DNS_RESOLUTION_FAILURE))
        assertEquals(
            UplinkActivityStatus.Activity.ConnectionTrouble(FreezeReason.DNS_RESOLUTION_FAILURE),
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `an ack still reports connected when POST_NOTIFICATIONS has been revoked`() {
        // The status line describes what the service established, not what it managed to
        // draw in the status bar -- a revoked notification permission silently drops the
        // notify() call, but the probe still answered.
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 11))

        assertEquals(
            UplinkActivityStatus.Activity.Connected(latencyMs = 11),
            UplinkActivityStatus.activity.value,
        )
    }

    // --- UplinkIconDisplay mirrors exactly what icon this class just built, unconditionally --

    /**
     * The deliberate contrast with the "architectural regression guard" test above: that one
     * proves building notification content claims nothing about connectivity, and this one
     * proves it *does* always mirror the icon it built -- the two obligations are different on
     * purpose (see [UplinkNotificationController.buildNotification]'s doc), and a reader
     * skimming only the status-line test could otherwise assume neither side effect happens
     * for the starting/disabled placeholders.
     */
    @Test
    fun `every notification builder mirrors its icon to UplinkIconDisplay, even the starting placeholder`() {
        controller.notificationForStarting()
        assertEquals(R.drawable.ic_scan_disabled, UplinkIconDisplay.iconRes.value)

        controller.notificationForEnabled(BarPosition.BAR_5)
        assertEquals(R.drawable.ic_scan_5, UplinkIconDisplay.iconRes.value)

        controller.notificationForDisabled(NetworkScope.WIFI_ONLY)
        assertEquals(R.drawable.ic_scan_disabled, UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `an ack mirrors the new bar position`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_4, AckSource.PROBE_SUCCESS, latencyMs = 30))

        assertEquals(R.drawable.ic_scan_4, UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `a freeze mirrors the frozen-in-place icon, unchanged from the last ack`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 5))
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.PROBE_FAILURE))

        assertEquals(R.drawable.ic_scan_2, UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `a repeated freeze with the same reason is suppressed -- and does not re-mirror either`() {
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_3, FreezeReason.PROBE_FAILURE))
        // Overwrite directly to prove the second, suppressed Frozen genuinely does not run
        // buildNotification() again -- if it did, this would be clobbered back to BAR_3.
        UplinkIconDisplay.report(R.drawable.ic_scan_1)

        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_3, FreezeReason.PROBE_FAILURE))

        assertEquals(R.drawable.ic_scan_1, UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `hide reports null -- HIDDEN is the absence of the icon, not a seventh frame`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 5))
        assertEquals(R.drawable.ic_scan_1, UplinkIconDisplay.iconRes.value)

        controller.hide()

        assertNull(UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `an ack still mirrors the icon when POST_NOTIFICATIONS has been revoked`() {
        // Same reasoning as the analogous UplinkActivityStatus test above: buildNotification()
        // runs before notify()'s permission check, so the mirror reflects what the app knows
        // the icon to be regardless of whether Android actually shows the real notification.
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 11))

        assertEquals(R.drawable.ic_scan_2, UplinkIconDisplay.iconRes.value)
    }

    // --- UplinkProbeHistory records real probe attempts, and only those ----------------------

    /**
     * The rule the history graphs live or die by, and the easiest one to get subtly wrong: the
     * automatic ack is a timer firing between probes, not a probe. It updates the notification,
     * the icon mirror and the status line exactly like a real ack does — three of this class's
     * four side effects — which is precisely why the fourth one skipping it needs its own test
     * rather than being assumed from the others.
     */
    @Test
    fun `the automatic ack contributes no sample at all -- it is not a probe attempt`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.AUTOMATIC, latencyMs = null))
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_3, AckSource.AUTOMATIC, latencyMs = null))

        // Not "0% success" and not "0ms latency" -- no attempt was made, so there is nothing
        // to average and nothing to count.
        assertEquals(0, UplinkProbeHistory.history.value.attemptCount)
        assertNull(UplinkProbeHistory.history.value.successPercent)
        assertNull(UplinkProbeHistory.history.value.averageLatencyMs)
    }

    /**
     * The teeth behind the test above. Today's automatic ack carries no latency, so "record
     * whatever latency arrived" happens to produce the right answer for the wrong reason --
     * and the test above would keep passing if the source check were deleted outright.
     *
     * The question being asked is "was this a real probe attempt," not "did a number come
     * with it," and this pins that: an ack that is not a probe contributes nothing even when
     * a latency is attached to it. [com.uplinkstatus.core.tracer.ProbeCycleRunner] does not
     * emit this event today; if it ever did (a cached or estimated figure on the automatic
     * step, say), that figure would still not be a measurement this app made.
     */
    @Test
    fun `an automatic ack is excluded by its source, not merely by having no latency`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.AUTOMATIC, latencyMs = 99))

        assertEquals(0, UplinkProbeHistory.history.value.attemptCount)
        assertNull(UplinkProbeHistory.history.value.averageLatencyMs)
    }

    /** The same rule from the other direction: an automatic ack must not dilute a real result
     * that is already recorded. A full ping/ping/fake pass is two attempts, not three. */
    @Test
    fun `a full ping-ping-fake pass records two samples, not three`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 10))
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 30))
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_3, AckSource.AUTOMATIC, latencyMs = null))

        val history = UplinkProbeHistory.history.value
        assertEquals(2, history.attemptCount)
        assertEquals(100f, history.successPercent!!, 0.001f)
        // 20ms, the mean of the two real measurements -- a third, latency-less "sample" would
        // have had to be either dropped from this average (leaving a phantom attempt in the
        // percentage) or counted as something nobody measured.
        assertEquals(20L, history.averageLatencyMs)
    }

    @Test
    fun `a probe-success ack records its measured latency as a successful sample`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 42))

        val history = UplinkProbeHistory.history.value
        assertEquals(1, history.attemptCount)
        assertEquals(1, history.successCount)
        assertEquals(42L, history.latestLatencyMs)
    }

    @Test
    fun `a freeze records a failed sample, dropping the success percentage`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 10))
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE))

        val history = UplinkProbeHistory.history.value
        assertEquals(2, history.attemptCount)
        assertEquals(50f, history.successPercent!!, 0.001f)
        // The failure contributes no latency of any kind, so the average is still the one real
        // measurement rather than being dragged toward zero by a timeout.
        assertEquals(10L, history.averageLatencyMs)
    }

    @Test
    fun `a dns-resolution freeze is a failed probe attempt too`() {
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.DNS_RESOLUTION_FAILURE))

        assertEquals(1, UplinkProbeHistory.history.value.attemptCount)
        assertEquals(0f, UplinkProbeHistory.history.value.successPercent!!, 0.001f)
    }

    /**
     * The de-duplication that keeps repeated freezes from re-posting the notification is a
     * statement about the status bar, not about the network. Each of those suppressed retries
     * is still a real probe that really failed — a sustained outage that recorded one sample
     * total would leave the success percentage reading far better than the connection actually
     * was, which is the exact opposite of what this graph exists to show.
     */
    @Test
    fun `every immediate-retry failure is recorded, even the ones whose notification is suppressed`() {
        repeat(5) {
            controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_3, FreezeReason.PROBE_FAILURE))
        }

        assertEquals(5, UplinkProbeHistory.history.value.attemptCount)
        // ...while the notification itself was posted exactly once, per the de-duplication.
        assertEquals(1, controller.notifyCallCount)
    }

    @Test
    fun `samples are recorded even when POST_NOTIFICATIONS has been revoked`() {
        // Same reasoning as the icon mirror above: what the probes measured is not contingent
        // on whether Android is currently willing to show a notification about it.
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 11))
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_2, FreezeReason.PROBE_FAILURE))

        assertEquals(2, UplinkProbeHistory.history.value.attemptCount)
    }

    /** Per spec the sample history is process-lifetime, not cycle-lifetime: leaving and
     * re-entering ENABLED (a network dropping out of scope and coming back) is exactly the
     * event whose surrounding failures a connectivity history is for, so `resetSession` --
     * which does clear the remembered latency and the notification de-duplication state --
     * must not take the samples with it. */
    @Test
    fun `resetSession does not discard accumulated samples`() {
        controller.onEvent(CycleEvent.Advanced(BarPosition.BAR_1, AckSource.PROBE_SUCCESS, latencyMs = 10))
        controller.onEvent(CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE))

        controller.resetSession()

        assertEquals(2, UplinkProbeHistory.history.value.attemptCount)
    }
}
