package com.uplinkstatus.app.service

import android.Manifest
import android.app.NotificationManager
import com.uplinkstatus.app.R
import com.uplinkstatus.app.prefs.FakeUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.state.UplinkActivityStatus
import com.uplinkstatus.app.state.UplinkIconDisplay
import com.uplinkstatus.app.state.UplinkProbeHistory
import com.uplinkstatus.app.state.UplinkRuntimeStatus
import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober
import com.uplinkstatus.core.tracer.AckSource
import com.uplinkstatus.core.tracer.BarPosition
import com.uplinkstatus.core.tracer.CycleEvent
import com.uplinkstatus.core.tracer.FreezeReason
import com.uplinkstatus.core.visibility.UplinkVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * tests. Stage 3 adds [FakeUplinkPreferencesRepository] (no real DataStore file) and an
 * `Dispatchers.Unconfined` [UplinkStatusService.visibilityScope] for the `onStartCommand`
 * tests, so the preferences-driven visibility recompute also runs synchronously. Stage 4 adds
 * [FakeNetworkScopeStatus] (no real `ConnectivityManager`), replacing what used to be a
 * process-wide `NetworkScopeStatus.inScope` singleton mutation with a per-test instance
 * injected the same way [fakePreferencesRepository] already is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UplinkStatusServiceTest {

    private lateinit var controller: ServiceController<UplinkStatusService>
    private lateinit var service: UplinkStatusService
    private lateinit var fakeProber: FakeProber
    private lateinit var fakeScheduler: FakeScheduler
    private lateinit var fakePreferencesRepository: FakeUplinkPreferencesRepository
    private lateinit var fakeNetworkScopeStatus: FakeNetworkScopeStatus
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        // UplinkNotificationController (which this service delegates all notify() calls
        // to) checks POST_NOTIFICATIONS before posting -- grant it the same way a real
        // device would after MainActivity's permission prompt is accepted, since
        // Robolectric doesn't auto-grant dangerous permissions just because they're
        // manifest-declared.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Process-wide singleton -- reset so one test's applied-visibility reports (and its
        // sequence counter) can't be mistaken for another's, per UplinkRuntimeStatus's doc.
        UplinkRuntimeStatus.resetForTest()
        // Same reasoning: `null` here means "this service has claimed nothing yet," which
        // several tests below assert on directly, so a previous test's last claim must not
        // leak in.
        UplinkActivityStatus.resetForTest()
        // Same reasoning -- a previous test's last mirrored icon must not leak into this
        // one's assertions on it.
        UplinkIconDisplay.resetForTest()
        // Same reasoning again -- and this one carries a window as well as samples, so a
        // previous test's history-window assertion must not be what makes this one's pass.
        UplinkProbeHistory.resetForTest()

        controller = Robolectric.buildService(UplinkStatusService::class.java)
        service = controller.create().get()

        fakeProber = FakeProber(ProbeResult.Success(latencyMs = 7))
        fakeScheduler = FakeScheduler()
        fakePreferencesRepository = FakeUplinkPreferencesRepository()
        fakeNetworkScopeStatus = FakeNetworkScopeStatus(initial = true)
        service.prober = fakeProber
        service.probeTarget = ProbeTarget(host = "probe.invalid")
        service.schedulerFactory = { fakeScheduler }
        service.preferencesRepository = fakePreferencesRepository
        service.networkScopeStatus = fakeNetworkScopeStatus
        // Unconfined so the onStartCommand tests' preferences-collector coroutine runs
        // synchronously to completion (up to its first suspension point) on the test
        // thread, rather than hopping to a real background dispatcher -- same reasoning as
        // overriding runOnWorker below for the probe cycle.
        service.visibilityScope = CoroutineScope(Dispatchers.Unconfined)
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
        // End-to-end confirmation that UplinkIconDisplay -- the settings screen's live
        // scanner-preview mirror -- tracks the real notification through the whole service,
        // not just at UplinkNotificationController's own unit-test level. Checked against
        // the *latest* icon, not the initial BAR_1 placeholder above: with the fake scheduler
        // running synchronously, the ack that produces `latest` has already happened by the
        // time applyVisibility() returns, and UplinkIconDisplay -- unlike the captured
        // `initialForeground` snapshot -- always reflects the current value, not a moment
        // frozen in time.
        assertEquals(R.drawable.ic_scan_2, UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `ENABLED is idempotent while already running -- does not restart the cycle`() {
        service.applyVisibility(UplinkVisibility.ENABLED)
        val callsAfterFirstEnable = fakeProber.callCount

        service.applyVisibility(UplinkVisibility.ENABLED)

        assertEquals(callsAfterFirstEnable, fakeProber.callCount)
    }

    @Test
    fun `DISABLED stops the visible tracer and shows the sixth (all-dim) icon, not a running tracer`() {
        service.applyVisibility(UplinkVisibility.ENABLED)
        val callsWhileEnabled = fakeProber.callCount

        service.applyVisibility(UplinkVisibility.DISABLED)

        val foregroundNotification = checkNotNull(shadowService().lastForegroundNotification)
        assertEquals(R.drawable.ic_scan_disabled, foregroundNotification.smallIcon.resId)
        // Exactly one more probe happened -- the new background history loop's own first
        // attempt (see the class below) -- not the visible cycle continuing to advance the
        // bar/notification. The icon stays the fixed dim frame regardless.
        assertEquals(callsWhileEnabled + 1, fakeProber.callCount)
        assertEquals(R.drawable.ic_scan_disabled, UplinkIconDisplay.iconRes.value)
    }

    /**
     * Regression test for the reported bug: on a real device, going out of network scope (e.g.
     * airplane mode under a "cellular only" scope) froze the history graphs instead of letting
     * them keep recording the outage -- exactly the kind of event a connectivity history exists
     * to show. `DISABLED` now keeps a throttled, independent probe loop running specifically to
     * feed [UplinkProbeHistory] while the visible tracer is paused.
     */
    @Test
    fun `DISABLED keeps recording real probe attempts into the history graphs`() {
        service.applyVisibility(UplinkVisibility.ENABLED)
        val attemptsWhileEnabled = UplinkProbeHistory.history.value.attemptCount

        service.applyVisibility(UplinkVisibility.DISABLED)

        assertEquals(attemptsWhileEnabled + 1, UplinkProbeHistory.history.value.attemptCount)

        fakeScheduler.scheduled.toList().forEach { it() }

        assertEquals(attemptsWhileEnabled + 2, UplinkProbeHistory.history.value.attemptCount)
    }

    @Test
    fun `the background history loop paces every attempt at least 250ms apart, regardless of a lower step delay`() = runTest {
        fakePreferencesRepository.setStepDelayMs(0L)
        controller.startCommand(0, 1)
        fakeNetworkScopeStatus.inScope = false // ENABLED -> DISABLED

        fakeScheduler.delays.clear()
        fakeScheduler.scheduled.toList().forEach { it() }

        assertEquals(listOf(250L), fakeScheduler.delays)
    }

    @Test
    fun `the background history loop uses the configured step delay when it is above the floor`() = runTest {
        fakePreferencesRepository.setStepDelayMs(600L)
        controller.startCommand(0, 1)
        fakeNetworkScopeStatus.inScope = false // ENABLED -> DISABLED

        fakeScheduler.delays.clear()
        fakeScheduler.scheduled.toList().forEach { it() }

        assertEquals(listOf(600L), fakeScheduler.delays)
    }

    @Test
    fun `going back to ENABLED stops the background history loop so probing is not doubled up`() {
        service.applyVisibility(UplinkVisibility.DISABLED)
        val attemptsWhileDisabled = UplinkProbeHistory.history.value.attemptCount

        service.applyVisibility(UplinkVisibility.ENABLED)
        val attemptsJustAfterEnabled = UplinkProbeHistory.history.value.attemptCount

        // Firing every callback the background loop might still have pending must not add a
        // second attempt on top of what the now-live visible cycle already produced.
        fakeScheduler.scheduled.toList().forEach { it() }

        assertEquals(attemptsJustAfterEnabled + 1, UplinkProbeHistory.history.value.attemptCount)
        assertTrue(attemptsWhileDisabled < attemptsJustAfterEnabled)
    }

    @Test
    fun `HIDDEN stops the background history loop too -- master toggle off means the entire service stops`() {
        service.applyVisibility(UplinkVisibility.DISABLED)
        val attemptsWhileDisabled = UplinkProbeHistory.history.value.attemptCount

        service.applyVisibility(UplinkVisibility.HIDDEN)
        fakeScheduler.scheduled.toList().forEach { it() }

        assertEquals(attemptsWhileDisabled, UplinkProbeHistory.history.value.attemptCount)
    }

    @Test
    fun `HIDDEN removes the notification entirely rather than showing any icon`() {
        service.applyVisibility(UplinkVisibility.ENABLED)

        service.applyVisibility(UplinkVisibility.HIDDEN)

        assertNull(shadowOf(notificationManager).getNotification(UplinkNotificationController.NOTIFICATION_ID))
        assertTrue(shadowService().isForegroundStopped)
        assertTrue(shadowService().notificationShouldRemoved)
        assertTrue(shadowService().isStoppedBySelf)
        // Same end-to-end confirmation as the ENABLED test above, for the other end of the
        // mirror: HIDDEN must reach UplinkIconDisplay as null, not just remove the real
        // notification.
        assertNull(UplinkIconDisplay.iconRes.value)
    }

    @Test
    fun `HIDDEN from a never-started state still stops the service cleanly`() {
        service.applyVisibility(UplinkVisibility.HIDDEN)

        assertTrue(shadowService().isStoppedBySelf)
        assertEquals(0, fakeProber.callCount)
    }

    @Test
    fun `onStartCommand reads real preferences -- master toggle off drives HIDDEN regardless of network scope`() = runTest {
        // Per spec: the master toggle always wins, unconditionally -- this is exactly the
        // truth-table case Stage 1's VisibilityDecider guarantees structurally; this test
        // confirms the service actually consults the persisted preference the same way.
        fakePreferencesRepository.setMasterToggleEnabled(false)
        fakeNetworkScopeStatus.inScope = true

        controller.startCommand(0, 1)

        assertTrue(shadowService().isStoppedBySelf)
        assertEquals(0, fakeProber.callCount)
    }

    @Test
    fun `onStartCommand with master toggle on and network in scope drives ENABLED`() = runTest {
        fakePreferencesRepository.setMasterToggleEnabled(true)
        fakeNetworkScopeStatus.inScope = true

        controller.startCommand(0, 1)

        assertEquals(1, fakeProber.callCount)
        checkNotNull(shadowService().lastForegroundNotification)
    }

    @Test
    fun `onStartCommand wires the persisted ping target host into the probe cycle`() = runTest {
        fakePreferencesRepository.setPingTargetHost("custom.example.invalid")
        fakeNetworkScopeStatus.inScope = true

        controller.startCommand(0, 1)

        assertEquals("custom.example.invalid", service.probeTarget.host)
    }

    @Test
    fun `onStartCommand wires the persisted step delay into the probe cycle`() = runTest {
        fakePreferencesRepository.setStepDelayMs(137L)
        fakeNetworkScopeStatus.inScope = true

        controller.startCommand(0, 1)

        assertEquals(137L, service.stepDelayMs)
    }

    /**
     * Regression test: `applyVisibility(ENABLED)` deliberately no-ops when the cycle is
     * already running -- re-confirming ENABLED must not reset bar position or session state
     * over an unrelated preference edit. But that no-op used to mean a *running* cycle never
     * saw a step-delay change at all: the service's own [UplinkStatusService.stepDelayMs]
     * field updated, but the already-constructed [com.uplinkstatus.core.tracer.ProbeCycleRunner]
     * captured the old value at construction and never re-read it. The user-visible symptom
     * was "changing the pacing slider does nothing" for as long as the tracer kept running.
     */
    @Test
    fun `changing the step delay while already running reaches the live cycle, not just the service field`() = runTest {
        controller.startCommand(0, 1)
        assertEquals(1, fakeProber.callCount)
        // One step (500ms, the default) is already pending at this point, scheduled *before*
        // the change below -- updating stepDelayMs can't rewrite a callback already handed to
        // the scheduler. What matters is the *next* one, scheduled once this one fires.

        fakePreferencesRepository.setStepDelayMs(137L)
        assertEquals(137L, service.stepDelayMs) // the field updates -- this was never the bug

        fakeScheduler.delays.clear()
        fakeScheduler.scheduled.toList().forEach { it() } // let the already-pending step fire

        // The bug: did the running cycle's *next* scheduled step actually use the new value?
        assertEquals(listOf(137L), fakeScheduler.delays)
    }

    /** Same bug, same fix, the other setting it affects: a ping-target-host change made while
     * the tracer is already running must reach the live cycle's next probe, not only the
     * service's own field. */
    @Test
    fun `changing the ping target host while already running reaches the live cycle, not just the service field`() = runTest {
        controller.startCommand(0, 1)
        assertEquals(1, fakeProber.callCount)

        fakePreferencesRepository.setPingTargetHost("custom.example.invalid")
        fakeScheduler.scheduled.toList().forEach { it() } // let the next step fire

        assertTrue(fakeProber.targetsProbed.any { it.host == "custom.example.invalid" })
    }

    @Test
    fun `onStartCommand applies the persisted history window to the sample history`() = runTest {
        fakePreferencesRepository.setHistoryWindowMs(2 * 60_000L)
        fakeNetworkScopeStatus.inScope = true

        controller.startCommand(0, 1)

        assertEquals(2 * 60_000L, UplinkProbeHistory.history.value.windowMs)
    }

    /** The window is a live setting, not a start-up one: the collector that already re-derives
     * visibility on every preferences emission has to carry this too, or a window narrowed
     * while the service runs would keep retaining samples under the old one until something
     * else happened to restart it. */
    @Test
    fun `a history window change while the service is running is applied without restarting it`() = runTest {
        controller.startCommand(0, 1)

        fakePreferencesRepository.setHistoryWindowMs(60_000L)

        assertEquals(60_000L, UplinkProbeHistory.history.value.windowMs)
    }

    @Test
    fun `a preference change while the service is already running is applied without restarting it`() = runTest {
        controller.startCommand(0, 1)
        assertEquals(1, fakeProber.callCount)

        // Simulates the settings screen writing straight to the (shared) DataStore-backed
        // repository while this service instance keeps running -- the collector started by
        // onStartCommand above should react on its own, with no second startCommand call.
        fakePreferencesRepository.setMasterToggleEnabled(false)

        assertTrue(shadowService().isStoppedBySelf)
    }

    @Test
    fun `a connectivity change alone -- no preference change -- drives HIDDEN while the service is already running`() = runTest {
        // Master toggle stays on and hide-when-disabled stays on for this test -- only
        // networkScopeStatus flips, simulating a real ConnectivityManager.NetworkCallback
        // reporting the device left its in-scope network (e.g. WiFi disconnected under a
        // WIFI_ONLY scope setting).
        fakePreferencesRepository.setHideWhenDisabled(true)
        controller.startCommand(0, 1)
        assertEquals(1, fakeProber.callCount)

        fakeNetworkScopeStatus.inScope = false

        assertTrue(shadowService().isStoppedBySelf)
    }

    @Test
    fun `a connectivity change alone -- no preference change -- drives DISABLED (dimmed icon) while the service is already running`() = runTest {
        // hide-when-disabled off this time: going out of scope should dim the icon rather
        // than remove it, and it should do so purely from networkScopeStatus flipping.
        fakePreferencesRepository.setHideWhenDisabled(false)
        controller.startCommand(0, 1)
        val callsWhileInScope = fakeProber.callCount

        fakeNetworkScopeStatus.inScope = false

        val foregroundNotification = checkNotNull(shadowService().lastForegroundNotification)
        assertEquals(R.drawable.ic_scan_disabled, foregroundNotification.smallIcon.resId)
        // The visible tracer really did stop -- exactly one more probe happened (the
        // background history loop's own first attempt while DISABLED), not the visible
        // cycle continuing to advance the icon.
        assertEquals(callsWhileInScope + 1, fakeProber.callCount)
    }

    @Test
    fun `going back in scope after being out of scope resumes ENABLED, still with no second onStartCommand`() = runTest {
        fakePreferencesRepository.setHideWhenDisabled(false)
        fakeNetworkScopeStatus.inScope = false
        controller.startCommand(0, 1)
        // The background history loop's own first probe attempt while DISABLED -- not the
        // visible tracer, which never starts while out of scope.
        assertEquals(1, fakeProber.callCount)

        fakeNetworkScopeStatus.inScope = true

        assertEquals(2, fakeProber.callCount)
        val foregroundNotification = checkNotNull(shadowService().lastForegroundNotification)
        assertEquals(R.drawable.ic_scan_1, foregroundNotification.smallIcon.resId)
    }

    // --- History graph markers: master-toggle transitions -----------------------------------

    @Test
    fun `turning the master toggle off records a marker in the history graphs`() = runTest {
        controller.startCommand(0, 1)
        assertTrue(UplinkProbeHistory.history.value.markers.isEmpty())

        fakePreferencesRepository.setMasterToggleEnabled(false)

        assertEquals(1, UplinkProbeHistory.history.value.markers.size)
    }

    @Test
    fun `a fresh start with the master toggle already on does not itself count as a transition`() = runTest {
        controller.startCommand(0, 1)

        assertTrue(UplinkProbeHistory.history.value.markers.isEmpty())
    }

    @Test
    fun `an unrelated preference change while enabled does not add a spurious marker`() = runTest {
        controller.startCommand(0, 1)

        fakePreferencesRepository.setStepDelayMs(137L)

        assertTrue(UplinkProbeHistory.history.value.markers.isEmpty())
    }

    @Test
    fun `turning the master toggle off does not clear the samples already recorded`() = runTest {
        controller.startCommand(0, 1)
        val attemptsBeforeToggleOff = UplinkProbeHistory.history.value.attemptCount

        fakePreferencesRepository.setMasterToggleEnabled(false)

        assertEquals(attemptsBeforeToggleOff, UplinkProbeHistory.history.value.attemptCount)
    }

    // --- Issue #22: a fresh start must not spend "nothing reported yet" as a real verdict ---

    /**
     * Regression test for the reported fresh-install bug: master toggle on (its default),
     * device on a network in scope, and the tracer nonetheless sits on the paused/disabled
     * frame until the user toggles the master switch off and back on.
     *
     * [FakeNetworkScopeStatus.inScope] starting at `null` reproduces the state a real
     * subscription genuinely begins in -- connectivity has not reported anything yet -- which
     * the previous code could not represent at all: it substituted a placeholder
     * `NetworkSnapshot.NONE`, so this first emission arrived as a hard `false` and the very
     * first user-visible decision the service ever made was DISABLED, derived from nothing.
     *
     * Asserting on [UplinkRuntimeStatus] rather than on the notification is deliberate:
     * `onStartCommand` posts a disabled-looking placeholder notification unconditionally (to
     * satisfy Android's startForeground deadline), so the notification alone cannot tell
     * "we haven't decided yet" apart from "we decided DISABLED." The runtime report can --
     * `sequence` counts decisions actually applied, so 0 means no verdict was reached.
     */
    @Test
    fun `a start whose connectivity has not reported yet reaches ENABLED with no toggle off and on`() = runTest {
        fakePreferencesRepository.setMasterToggleEnabled(true)
        fakeNetworkScopeStatus.inScope = null

        controller.startCommand(0, 1)

        assertEquals(
            "a visibility verdict was applied before connectivity reported anything",
            0,
            UplinkRuntimeStatus.reports.value.sequence,
        )
        assertNull(UplinkRuntimeStatus.reports.value.visibility)
        assertEquals(0, fakeProber.callCount)

        // Connectivity reports in scope -- no second startCommand, no preference write, and
        // above all no master-toggle bounce. This alone has to start the tracer.
        fakeNetworkScopeStatus.inScope = true

        assertEquals(UplinkVisibility.ENABLED, UplinkRuntimeStatus.reports.value.visibility)
        assertEquals(1, fakeProber.callCount)
        val foregroundNotification = checkNotNull(shadowService().lastForegroundNotification)
        assertEquals(R.drawable.ic_scan_1, foregroundNotification.smallIcon.resId)
    }

    /**
     * The same defect's more destructive form. With hide-when-disabled on, treating "nothing
     * reported yet" as "out of scope" doesn't merely dim the icon -- it resolves to HIDDEN,
     * which this service implements as `stopSelf()`. The service would destroy itself before
     * connectivity ever got a chance to report, so no later report could bring it back and
     * only a fresh `startForegroundService` (i.e. the user toggling the master switch) would.
     */
    @Test
    fun `an unreported connectivity state does not tear the service down when hide-when-disabled is on`() = runTest {
        fakePreferencesRepository.setHideWhenDisabled(true)
        fakeNetworkScopeStatus.inScope = null

        controller.startCommand(0, 1)

        assertFalse(
            "the service stopped itself on the strength of a connectivity report that never happened",
            shadowService().isStoppedBySelf,
        )

        fakeNetworkScopeStatus.inScope = true

        assertEquals(1, fakeProber.callCount)
    }

    /**
     * The guard against over-correcting the above into "always wait for connectivity." The
     * spec's master-toggle-wins rule never consults the network, so an explicit "off" must
     * still resolve immediately even while connectivity is unknown -- there is nothing for it
     * to wait for, and stranding it behind a connectivity report would be the opposite bug.
     */
    @Test
    fun `master toggle off still resolves immediately while connectivity is still unknown`() = runTest {
        fakePreferencesRepository.setMasterToggleEnabled(false)
        fakeNetworkScopeStatus.inScope = null

        controller.startCommand(0, 1)

        assertTrue(shadowService().isStoppedBySelf)
        assertEquals(UplinkVisibility.HIDDEN, UplinkRuntimeStatus.reports.value.visibility)
        assertEquals(0, fakeProber.callCount)
    }

    // --- The on-screen status line must only ever state what the service has confirmed -----

    /**
     * The status line is a small honest log the user reads to see what the service is really
     * doing. That only works if every value it can hold came from a real transition — so the
     * two things it must never do are (a) name a verdict before one has been reached and
     * (b) claim a connection before anything has answered. The tests in this section pin both,
     * plus the states that fill the gaps those bugs left behind.
     *
     * `UplinkActivityStatus.activity` being `null` is the load-bearing "nothing has been
     * claimed yet" signal here, the same way `UplinkRuntimeStatus.sequence == 0` means "no
     * decision has been applied yet" — asserting on notification content alone could not tell
     * the two apart, since the notification is posted unconditionally either way.
     */
    @Test
    fun `a start with no visibility decision yet reports starting, never a paused verdict`() = runTest {
        // Connectivity has reported nothing, so there is no decision to make yet -- exactly
        // the window in which onStartCommand has to post its required placeholder
        // notification. That placeholder names a reason ("network out of scope"); the status
        // line must not repeat it, because nothing has looked at the network.
        fakeNetworkScopeStatus.inScope = null

        controller.startCommand(0, 1)

        assertEquals(
            "no visibility decision was reached, so nothing should have been claimed about one",
            0,
            UplinkRuntimeStatus.reports.value.sequence,
        )
        assertEquals(UplinkActivityStatus.Activity.Starting, UplinkActivityStatus.activity.value)
    }

    @Test
    fun `ENABLED reports checking-connection and does not claim connected until a probe answers`() {
        // Swallow the cycle's work instead of running it, so the test can stand in the window
        // between "ENABLED was applied" and "the first probe completed" -- which on a real
        // device is however long the first TCP connect takes, and on an unreachable network
        // is the full 1000ms probe timeout, repeatedly.
        service.runOnWorker = { }

        service.applyVisibility(UplinkVisibility.ENABLED)

        assertEquals(0, fakeProber.callCount)
        assertEquals(
            UplinkActivityStatus.Activity.CheckingConnection,
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `a probe that actually answers is what turns checking-connection into connected`() {
        service.applyVisibility(UplinkVisibility.ENABLED)

        assertEquals(1, fakeProber.callCount)
        assertEquals(
            UplinkActivityStatus.Activity.Connected(latencyMs = 7),
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `a real DISABLED decision is the only thing that reports paused`() {
        service.applyVisibility(UplinkVisibility.DISABLED)

        assertEquals(
            UplinkActivityStatus.Activity.Paused(NetworkScope.WIFI_ONLY),
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `a real DISABLED decision under an SSID whitelist reports what is being waited for`() = runTest {
        fakePreferencesRepository.setNetworkScope(NetworkScope.SSID_WHITELIST)
        fakeNetworkScopeStatus.inScope = false
        fakePreferencesRepository.setHideWhenDisabled(false)

        controller.startCommand(0, 1)

        assertEquals(
            UplinkActivityStatus.Activity.Paused(NetworkScope.SSID_WHITELIST),
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `a real HIDDEN decision reports hidden`() {
        service.applyVisibility(UplinkVisibility.HIDDEN)

        assertEquals(UplinkActivityStatus.Activity.Hidden, UplinkActivityStatus.activity.value)
    }

    @Test
    fun `a nudge restart while already running does not claim to be starting up again`() = runTest {
        controller.startCommand(0, 1)
        assertEquals(
            UplinkActivityStatus.Activity.Connected(latencyMs = 7),
            UplinkActivityStatus.activity.value,
        )

        // A settings change calls startForegroundService() again to nudge the running
        // instance. Nothing is starting -- the service is up and connected -- so saying
        // "starting up…" would be as false as the paused placeholder was.
        controller.startCommand(0, 2)

        assertEquals(
            UplinkActivityStatus.Activity.Connected(latencyMs = 7),
            UplinkActivityStatus.activity.value,
        )
    }

    @Test
    fun `a destroyed service reports stopped instead of leaving a stale connected line`() {
        service.applyVisibility(UplinkVisibility.ENABLED)
        assertEquals(
            UplinkActivityStatus.Activity.Connected(latencyMs = 7),
            UplinkActivityStatus.activity.value,
        )

        controller.destroy()

        // Nothing will update this again until a new instance starts, so "connected, 7ms"
        // would quietly become a lie the moment this one went away.
        assertEquals(UplinkActivityStatus.Activity.Stopped, UplinkActivityStatus.activity.value)
    }

    @Test
    fun `a service destroyed after HIDDEN keeps the more specific hidden reason`() {
        service.applyVisibility(UplinkVisibility.HIDDEN)

        controller.destroy()

        assertEquals(UplinkActivityStatus.Activity.Hidden, UplinkActivityStatus.activity.value)
    }

    // --- Stage 5: DNS-vs-generic-failure and no-back-off, end to end through the real ------
    // --- running service (not just ProbeCycleRunnerTest's or                             ---
    // --- UplinkNotificationControllerTest's standalone unit level) -------------------------

    @Test
    fun `a real cycle run inside the service reports generic and DNS failures as distinct CycleEvents in order`() {
        val recordingController = RecordingNotificationController(RuntimeEnvironment.getApplication())
        service.notificationController = recordingController
        fakeProber = FakeProber(
            ProbeResult.Failure,
            ProbeResult.DnsResolutionFailure,
            ProbeResult.Success(5),
        )
        service.prober = fakeProber

        service.applyVisibility(UplinkVisibility.ENABLED)

        // Three probe attempts happened synchronously, back to back, with no wait in
        // between -- exactly what "immediate retry, no adaptive back-off" requires even
        // when driven by the real service (not a standalone ProbeCycleRunner in isolation).
        assertEquals(3, fakeProber.callCount)
        assertEquals(
            listOf(
                CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.PROBE_FAILURE),
                CycleEvent.Frozen(BarPosition.BAR_1, FreezeReason.DNS_RESOLUTION_FAILURE),
                CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 5),
            ),
            recordingController.events,
        )
        // The DNS failure changed *why* the tracer was frozen relative to the previous
        // generic failure, so it must not be suppressed as a repeat -- three distinct
        // notify() calls (one per real state change), not deduplicated down to fewer.
        assertEquals(3, recordingController.notifyCallCount)
    }

    @Test
    fun `repeated generic failures inside a real running cycle only post the failure notification once`() {
        val recordingController = RecordingNotificationController(RuntimeEnvironment.getApplication())
        service.notificationController = recordingController
        fakeProber = FakeProber(
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Success(1),
        )
        service.prober = fakeProber

        service.applyVisibility(UplinkVisibility.ENABLED)

        assertEquals(5, fakeProber.callCount)
        // Four Frozen events with the *same* reason, then one Advanced -- but only the
        // first Frozen and the eventual Advanced should have actually posted, per the
        // spec's "not on every internal timer tick" rule -- proven here through the real
        // service's own cycle, not just UplinkNotificationControllerTest's direct calls.
        assertEquals(2, recordingController.notifyCallCount)
        val posted = checkNotNull(
            shadowOf(notificationManager).getNotification(UplinkNotificationController.NOTIFICATION_ID),
        )
        assertEquals(
            RuntimeEnvironment.getApplication().getString(R.string.notification_text_connected_with_latency, 1),
            shadowOf(posted).contentText,
        )
    }

    // --- Outage-driven retry loop vs. a single-threaded worker queue ----------------------

    /**
     * Regression test for the "an outage can permanently starve the cycle's own stop()"
     * defect.
     *
     * Unlike every other test in this class, this one does *not* run worker work
     * synchronously on the test thread: it hands [UplinkStatusService.runOnWorker] a real
     * single-threaded executor, which is the essential property of the production
     * `HandlerThread`/`Handler` pairing (one queued unit of work runs to completion before
     * the next is dispatched). Without that, the failure is structurally unreachable — a
     * synchronous `runOnWorker` cannot express two pieces of work contending for one thread.
     *
     * The prober here models a continuous outage the way production actually behaves: each
     * probe blocks (as a real TCP connect does) and then fails, so `runProbeAttempts()`
     * loops straight back around with no gap and never yields the worker thread. If
     * `stopCycle()` posts `runner.stop()` onto that same queue, it sits behind a loop that
     * will not return until the network recovers, and the cycle keeps probing and keeps
     * posting notifications after the service has torn itself down.
     */
    @Test
    fun `HIDDEN during a continuous outage stops the cycle instead of queueing stop behind it`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            service.runOnWorker = { worker.execute(it) }
            val probeEntered = CountDownLatch(1)
            val releaseProbe = Semaphore(0)
            val probeCount = AtomicInteger()
            service.prober = Prober {
                probeCount.incrementAndGet()
                probeEntered.countDown()
                // Blocks like a real connect() attempt does, until the test decides this
                // attempt's "timeout" has elapsed.
                releaseProbe.acquire()
                ProbeResult.Failure
            }

            service.applyVisibility(UplinkVisibility.ENABLED)
            assertTrue("the cycle never reached its first probe", probeEntered.await(5, TimeUnit.SECONDS))

            // The service leaves ENABLED while the outage is still in progress -- the probe
            // is in flight and the worker thread is inside the retry loop right now.
            service.applyVisibility(UplinkVisibility.HIDDEN)
            releaseProbe.release() // the in-flight probe returns Failure

            // With the cycle genuinely stopped, the retry loop returns and the worker thread
            // becomes available again, so this marker task runs. If stop() had been queued
            // behind the loop instead, the loop would have gone straight into probe #2 (for
            // which no permit is released, standing in for an outage that simply continues)
            // and this would time out -- which is precisely the starvation being fixed.
            worker.submit { }.get(5, TimeUnit.SECONDS)

            assertEquals(1, probeCount.get())
            // ...and nothing resurrected the notification the HIDDEN transition removed.
            assertNull(shadowOf(notificationManager).getNotification(UplinkNotificationController.NOTIFICATION_ID))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `a real running cycle schedules no delay at all for failed attempts -- only the post-success automatic ack`() {
        fakeProber = FakeProber(
            ProbeResult.Failure,
            ProbeResult.DnsResolutionFailure,
            ProbeResult.Failure,
            ProbeResult.Success(3),
        )
        service.prober = fakeProber

        service.applyVisibility(UplinkVisibility.ENABLED)

        assertEquals(4, fakeProber.callCount)
        // The only thing ever scheduled is the 500ms automatic ack that follows the
        // eventual success -- nothing was scheduled for any of the three failed attempts,
        // confirming "no adaptive back-off" holds with a real ProbeCycleRunner instance
        // owned and started by the real, running UplinkStatusService.
        assertEquals(1, fakeScheduler.scheduled.size)
    }
}
