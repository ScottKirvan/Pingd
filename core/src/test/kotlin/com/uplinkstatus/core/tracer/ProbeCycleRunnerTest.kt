package com.uplinkstatus.core.tracer

import com.uplinkstatus.core.fakes.FakeProber
import com.uplinkstatus.core.fakes.FakeTracerScheduler
import com.uplinkstatus.core.fakes.RecordingCycleListener
import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeCycleRunnerTest {

    private val target = ProbeTarget(host = "one.one.one.one")

    private fun runner(
        prober: FakeProber,
        scheduler: FakeTracerScheduler,
        listener: RecordingCycleListener,
        tracer: AckTracer = AckTracer(),
    ) = ProbeCycleRunner(prober, target, scheduler, listener, tracer)

    // --- Ack cycle timing/sequencing -----------------------------------------------

    @Test
    fun `probe success immediately fires an ack and advances the tracer`() {
        val prober = FakeProber(ProbeResult.Success(42))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        assertEquals(1, prober.callCount)
        assertEquals(
            listOf(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS)),
            listener.events,
        )
    }

    @Test
    fun `after probe success ack, schedules the automatic ack 500ms later`() {
        val prober = FakeProber(ProbeResult.Success(42))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        assertEquals(listOf(500L), scheduler.history)
        assertTrue(scheduler.hasPending())
    }

    @Test
    fun `automatic ack fires 500ms after probe success and advances the tracer again`() {
        val prober = FakeProber(ProbeResult.Success(42))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        scheduler.fireNext() // the 500ms automatic-ack timer fires

        assertEquals(
            listOf(
                CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS),
                CycleEvent.Advanced(BarPosition.BAR_3, AckSource.AUTOMATIC),
            ),
            listener.events,
        )
    }

    @Test
    fun `after the automatic ack, schedules a second 500ms gap that produces no ack`() {
        val prober = FakeProber(ProbeResult.Success(42), ProbeResult.Success(10))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        scheduler.fireNext() // automatic ack

        assertEquals(listOf(500L, 500L), scheduler.history)

        val eventsBeforeGap = listener.events.size
        scheduler.fireNext() // the non-ack gap elapses -> triggers the next probe

        // The gap itself must not have produced any new ack — only the next probe cycle's
        // own success does (and that adds exactly one Advanced event here).
        assertEquals(eventsBeforeGap + 1, listener.events.size)
        assertEquals(AckSource.PROBE_SUCCESS, (listener.events.last() as CycleEvent.Advanced).source)
    }

    @Test
    fun `a full successful cycle repeats step 1 through 5 and advances two positions per cycle`() {
        val prober = FakeProber(
            ProbeResult.Success(10),
            ProbeResult.Success(20),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start() // cycle 1, step 1-2
        scheduler.fireNext() // cycle 1, step 3
        scheduler.fireNext() // cycle 1, step 4-5 -> cycle 2, step 1-2
        scheduler.fireNext() // cycle 2, step 3

        assertEquals(2, prober.callCount)
        assertEquals(
            listOf(
                CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS),
                CycleEvent.Advanced(BarPosition.BAR_3, AckSource.AUTOMATIC),
                CycleEvent.Advanced(BarPosition.BAR_4, AckSource.PROBE_SUCCESS),
                CycleEvent.Advanced(BarPosition.BAR_5, AckSource.AUTOMATIC),
            ),
            listener.events,
        )
    }

    // --- Freeze-on-failure / resume-on-success --------------------------------------

    @Test
    fun `probe failure fires no ack and freezes the tracer at its current position`() {
        // Bounded (fails twice, then succeeds) so start() can't spin forever — the
        // runner retries immediately with no back-off, so an all-failing fake would
        // never return control to the test.
        val prober = FakeProber(ProbeResult.Failure, ProbeResult.Failure, ProbeResult.Success(5))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()
        val tracer = AckTracer(BarPosition.BAR_3)

        runner(prober, scheduler, listener, tracer).start()

        val frozenEvents = listener.events.filterIsInstance<CycleEvent.Frozen>()
        assertEquals(2, frozenEvents.size)
        assertTrue(frozenEvents.all { it.position == BarPosition.BAR_3 })
        assertTrue(frozenEvents.all { it.reason == FreezeReason.PROBE_FAILURE })
    }

    @Test
    fun `tracer resumes from its frozen position once a probe succeeds`() {
        val prober = FakeProber(ProbeResult.Failure, ProbeResult.Failure, ProbeResult.Success(5))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()
        val tracer = AckTracer(BarPosition.BAR_3)

        runner(prober, scheduler, listener, tracer).start()

        val advanced = listener.events.filterIsInstance<CycleEvent.Advanced>()
        assertEquals(1, advanced.size)
        // Resumed from BAR_3 (where it was frozen), not reset to BAR_1.
        assertEquals(BarPosition.BAR_4, advanced.single().position)
    }

    @Test
    fun `dns resolution failure is reported as a distinct freeze reason from generic failure`() {
        val prober = FakeProber(
            ProbeResult.DnsResolutionFailure,
            ProbeResult.Failure,
            ProbeResult.Success(5),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        val frozenEvents = listener.events.filterIsInstance<CycleEvent.Frozen>()
        assertEquals(2, frozenEvents.size)
        assertEquals(FreezeReason.DNS_RESOLUTION_FAILURE, frozenEvents[0].reason)
        assertEquals(FreezeReason.PROBE_FAILURE, frozenEvents[1].reason)
    }

    // --- Immediate no-back-off retry -------------------------------------------------

    @Test
    fun `failed probes are retried immediately with no scheduled delay between attempts`() {
        val prober = FakeProber(
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Success(1),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        assertEquals(4, prober.callCount)
        // The only scheduled delay is the 500ms automatic-ack after the eventual success —
        // nothing was scheduled for any of the three failed attempts.
        assertEquals(listOf(500L), scheduler.history)
    }

    @Test
    fun `dns failures also retry immediately with no back-off`() {
        val prober = FakeProber(
            ProbeResult.DnsResolutionFailure,
            ProbeResult.DnsResolutionFailure,
            ProbeResult.Success(1),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        assertEquals(3, prober.callCount)
        assertEquals(listOf(500L), scheduler.history)
    }

    // --- stop() / lifecycle -----------------------------------------------------------

    @Test
    fun `stop cancels a pending scheduled step and prevents further probes`() {
        val prober = FakeProber(ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        val cycleRunner = runner(prober, scheduler, listener)
        cycleRunner.start()
        assertTrue(scheduler.hasPending())

        cycleRunner.stop()
        assertFalse(scheduler.hasPending())
        assertEquals(1, scheduler.cancelledCount)
        assertFalse(cycleRunner.isRunning)
    }

    @Test
    fun `start is a no-op if already running`() {
        val prober = FakeProber(defaultResult = ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        val cycleRunner = runner(prober, scheduler, listener)
        cycleRunner.start()
        val callsAfterFirstStart = prober.callCount
        cycleRunner.start()

        assertEquals(callsAfterFirstStart, prober.callCount)
    }

    @Test
    fun `currentPosition reflects the tracer without requiring an event`() {
        val prober = FakeProber(ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        val cycleRunner = runner(prober, scheduler, listener)
        assertEquals(BarPosition.BAR_1, cycleRunner.currentPosition)

        cycleRunner.start()
        assertEquals(BarPosition.BAR_2, cycleRunner.currentPosition)
    }
}
