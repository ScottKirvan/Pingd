package com.uplinkstatus.app.service

import android.Manifest
import android.app.NotificationManager
import com.uplinkstatus.app.R
import com.uplinkstatus.app.state.VisibilityInputs
import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.visibility.UplinkVisibility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Verifies [UplinkStatusService]'s reaction to visibility transitions (the spec's
 * ENABLED/DISABLED/HIDDEN state logic wired into real foreground-service behavior), using
 * Robolectric's [ServiceController] instead of a real device/emulator.
 *
 * The real production defaults ([UplinkStatusService.prober], `.schedulerFactory`, backed
 * by a real socket and a real background `HandlerThread`) are swapped for
 * [FakeProber]/[FakeScheduler] and a synchronous [UplinkStatusService.runOnWorker] before
 * any visibility transition runs, so these tests never touch a real socket, never wait on
 * real time, and never depend on a real Looper thread actually pumping — per the
 * project's standing rule (carried over from Stage 1) against real network calls in unit
 * tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UplinkStatusServiceTest {

    private lateinit var controller: ServiceController<UplinkStatusService>
    private lateinit var service: UplinkStatusService
    private lateinit var fakeProber: FakeProber
    private lateinit var fakeScheduler: FakeScheduler
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        // VisibilityInputs is a process-wide singleton (Stage 2's stand-in for Stage 3/4's
        // real preferences/connectivity plumbing) — reset it before each test so tests
        // don't leak state into one another.
        VisibilityInputs.masterToggleEnabled = true
        VisibilityInputs.networkInScope = true
        VisibilityInputs.hideWhenDisabled = false

        // UplinkNotificationController (which this service delegates all notify() calls
        // to) checks POST_NOTIFICATIONS before posting -- grant it the same way a real
        // device would after MainActivity's permission prompt is accepted, since
        // Robolectric doesn't auto-grant dangerous permissions just because they're
        // manifest-declared.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        controller = Robolectric.buildService(UplinkStatusService::class.java)
        service = controller.create().get()

        fakeProber = FakeProber(ProbeResult.Success(latencyMs = 7))
        fakeScheduler = FakeScheduler()
        service.prober = fakeProber
        service.probeTarget = ProbeTarget(host = "probe.invalid")
        service.schedulerFactory = { fakeScheduler }
        // Dispatch "worker" work synchronously on the test thread rather than hopping to a
        // real background HandlerThread, so cycle start/stop happens deterministically
        // within the test call itself.
        service.runOnWorker = { it.run() }

        notificationManager = checkNotNull(
            RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java),
        )
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun shadowService() = shadowOf(service)

    @Test
    fun `ENABLED starts the foreground notification and drives the probe cycle`() {
        service.applyVisibility(UplinkVisibility.ENABLED)

        // The very first notification is the fresh-cycle placeholder at BAR_1, posted via
        // startForeground() before the cycle has had any chance to ack — this is the one
        // and only startForeground() call this transition makes.
        val initialForeground = checkNotNull(shadowService().lastForegroundNotification)
        assertEquals(R.drawable.ic_scan_1, initialForeground.smallIcon.resId)

        // The (fake, always-succeeding) probe cycle acks synchronously via runOnWorker,
        // advancing to BAR_2 — but that update reaches the notification through
        // UplinkNotificationController reacting to the CycleEvent and calling
        // NotificationManagerCompat.notify() directly, not through another
        // startForeground() call. Reading it back via the NotificationManager (rather than
        // shadowService().lastForegroundNotification) confirms the ack path is really what
        // updated it.
        assertEquals(1, fakeProber.callCount)
        val latest = checkNotNull(
            shadowOf(notificationManager).getNotification(UplinkNotificationController.NOTIFICATION_ID),
        )
        assertEquals(R.drawable.ic_scan_2, latest.smallIcon.resId)
    }

    @Test
    fun `ENABLED is idempotent while already running -- does not restart the cycle`() {
        service.applyVisibility(UplinkVisibility.ENABLED)
        val callsAfterFirstEnable = fakeProber.callCount

        service.applyVisibility(UplinkVisibility.ENABLED)

        assertEquals(callsAfterFirstEnable, fakeProber.callCount)
    }

    @Test
    fun `DISABLED stops the cycle and shows the sixth (all-dim) icon, not a running tracer`() {
        service.applyVisibility(UplinkVisibility.ENABLED)
        val callsWhileEnabled = fakeProber.callCount

        service.applyVisibility(UplinkVisibility.DISABLED)

        val foregroundNotification = checkNotNull(shadowService().lastForegroundNotification)
        assertEquals(R.drawable.ic_scan_disabled, foregroundNotification.smallIcon.resId)
        // No further probes after DISABLED stops the cycle -- confirms it's actually
        // stopped, not just visually paused while still running underneath.
        assertEquals(callsWhileEnabled, fakeProber.callCount)
    }

    @Test
    fun `HIDDEN removes the notification entirely rather than showing any icon`() {
        service.applyVisibility(UplinkVisibility.ENABLED)

        service.applyVisibility(UplinkVisibility.HIDDEN)

        assertNull(shadowOf(notificationManager).getNotification(UplinkNotificationController.NOTIFICATION_ID))
        assertTrue(shadowService().isForegroundStopped)
        assertTrue(shadowService().notificationShouldRemoved)
        assertTrue(shadowService().isStoppedBySelf)
    }

    @Test
    fun `HIDDEN from a never-started state still stops the service cleanly`() {
        service.applyVisibility(UplinkVisibility.HIDDEN)

        assertTrue(shadowService().isStoppedBySelf)
        assertEquals(0, fakeProber.callCount)
    }

    @Test
    fun `onStartCommand reads VisibilityInputs -- master toggle off drives HIDDEN regardless of network scope`() {
        // Per spec: the master toggle always wins, unconditionally -- this is exactly the
        // truth-table case Stage 1's VisibilityDecider guarantees structurally; this test
        // confirms the service actually consults it the same way.
        VisibilityInputs.masterToggleEnabled = false
        VisibilityInputs.networkInScope = true

        controller.startCommand(0, 1)

        assertTrue(shadowService().isStoppedBySelf)
        assertEquals(0, fakeProber.callCount)
    }

    @Test
    fun `onStartCommand with master toggle on and network in scope drives ENABLED`() {
        VisibilityInputs.masterToggleEnabled = true
        VisibilityInputs.networkInScope = true

        controller.startCommand(0, 1)

        assertEquals(1, fakeProber.callCount)
        checkNotNull(shadowService().lastForegroundNotification)
    }
}
