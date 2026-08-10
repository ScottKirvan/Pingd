package com.uplinkstatus.core.tracer

import com.uplinkstatus.core.fakes.FakeProber
import com.uplinkstatus.core.fakes.FakeTracerScheduler
import com.uplinkstatus.core.fakes.RecordingCycleListener
import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProbeCycleRunnerTest {

    private val target = ProbeTarget(host = "one.one.one.one")

    private fun runner(
        prober: FakeProber,
        scheduler: FakeTracerScheduler,
        listener: RecordingCycleListener,
        tracer: AckTracer = AckTracer(),
        stepDelayMs: Long = ProbeCycleRunner.DEFAULT_STEP_DELAY_MS,
    ) = ProbeCycleRunner(prober, target, scheduler, listener, tracer, stepDelayMs)

    // --- Ack cycle timing/sequencing: ping, ping, fake ------------------------------

    @Test
    fun `probe success immediately fires an ack and advances the tracer`() {
        val prober = FakeProber(ProbeResult.Success(42))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        assertEquals(1, prober.callCount)
        assertEquals(
            listOf(CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 42)),
            listener.events,
        )
    }

    @Test
    fun `after the first probe success, schedules the step delay before a second real probe`() {
        val prober = FakeProber(ProbeResult.Success(42))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()

        assertEquals(listOf(500L), scheduler.history)
        assertTrue(scheduler.hasPending())
        // Only one real probe so far -- the second one is what the pending delay leads to.
        assertEquals(1, prober.callCount)
    }

    @Test
    fun `the second real probe fires after the step delay and advances the tracer again`() {
        val prober = FakeProber(ProbeResult.Success(42), ProbeResult.Success(17))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        scheduler.fireNext() // step delay elapses -> second real probe

        assertEquals(2, prober.callCount)
        assertEquals(
            listOf(
                CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 42),
                CycleEvent.Advanced(BarPosition.BAR_3, AckSource.PROBE_SUCCESS, latencyMs = 17),
            ),
            listener.events,
        )
    }

    @Test
    fun `the automatic ack fires only after two real probe successes, not after the first`() {
        val prober = FakeProber(ProbeResult.Success(42), ProbeResult.Success(17))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        scheduler.fireNext() // second real probe
        scheduler.fireNext() // step delay after the second real probe -> automatic ack

        assertEquals(
            listOf(
                CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 42),
                CycleEvent.Advanced(BarPosition.BAR_3, AckSource.PROBE_SUCCESS, latencyMs = 17),
                CycleEvent.Advanced(BarPosition.BAR_4, AckSource.AUTOMATIC, latencyMs = null),
            ),
            listener.events,
        )
        // Still only two real probes -- the automatic ack never touches the prober.
        assertEquals(2, prober.callCount)
    }

    @Test
    fun `the delay after the automatic ack leads directly to a new real probe -- no separate silent gap step`() {
        val prober = FakeProber(ProbeResult.Success(10), ProbeResult.Success(20), ProbeResult.Success(30))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        scheduler.fireNext() // second real probe
        scheduler.fireNext() // automatic ack

        val eventsBeforeNextDelay = listener.events.size
        scheduler.fireNext() // the delay after the automatic ack elapses

        // Straight into a new real probe -- not a distinct no-op step first.
        assertEquals(eventsBeforeNextDelay + 1, listener.events.size)
        assertEquals(AckSource.PROBE_SUCCESS, (listener.events.last() as CycleEvent.Advanced).source)
        assertEquals(3, prober.callCount)
    }

    @Test
    fun `a full ping, ping, fake cycle repeats and advances three positions per cycle`() {
        val prober = FakeProber(
            ProbeResult.Success(10),
            ProbeResult.Success(20),
            ProbeResult.Success(30),
            ProbeResult.Success(40),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start() // cycle 1: real probe 1
        scheduler.fireNext() // cycle 1: real probe 2
        scheduler.fireNext() // cycle 1: automatic ack
        scheduler.fireNext() // cycle 2: real probe 1
        scheduler.fireNext() // cycle 2: real probe 2
        scheduler.fireNext() // cycle 2: automatic ack

        assertEquals(4, prober.callCount)
        assertEquals(
            listOf(
                CycleEvent.Advanced(BarPosition.BAR_2, AckSource.PROBE_SUCCESS, latencyMs = 10),
                CycleEvent.Advanced(BarPosition.BAR_3, AckSource.PROBE_SUCCESS, latencyMs = 20),
                CycleEvent.Advanced(BarPosition.BAR_4, AckSource.AUTOMATIC, latencyMs = null),
                CycleEvent.Advanced(BarPosition.BAR_5, AckSource.PROBE_SUCCESS, latencyMs = 30),
                CycleEvent.Advanced(BarPosition.BAR_4, AckSource.PROBE_SUCCESS, latencyMs = 40),
                CycleEvent.Advanced(BarPosition.BAR_3, AckSource.AUTOMATIC, latencyMs = null),
            ),
            listener.events,
        )
    }

    @Test
    fun `an outage between the two real probes does not restart the ping, ping, fake sequence`() {
        // Regression test for the "a failure doesn't consume a pattern slot" rule: one real
        // success, then a run of failures, then the retry that finally succeeds should be
        // treated as the *second* real probe (triggering the automatic ack next) -- not as a
        // fresh first probe that would need another full pair before the automatic ack.
        val prober = FakeProber(
            ProbeResult.Success(10),
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Success(20),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start() // real probe 1 (success)
        scheduler.fireNext() // step delay -> real probe 2, attempt 1: failure
        scheduler.fireNext() // retry delay -> real probe 2, attempt 2: failure
        scheduler.fireNext() // retry delay -> real probe 2, attempt 3: success
        scheduler.fireNext() // step delay after that success -> should be the automatic ack

        assertEquals(
            AckSource.AUTOMATIC,
            (listener.events.last() as CycleEvent.Advanced).source,
        )
    }

    // --- Configurable step delay -----------------------------------------------------

    @Test
    fun `a custom step delay is used instead of the default`() {
        val prober = FakeProber(ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener, stepDelayMs = 137L).start()

        assertEquals(listOf(137L), scheduler.history)
    }

    @Test
    fun `a step delay of zero is still scheduled through the scheduler, not skipped`() {
        // "Free wheeling" means no *added* wait, not that pacing stops going through the
        // scheduler abstraction -- ProbeCycleRunner must stay ignorant of wall-clock time
        // either way, per the class doc.
        val prober = FakeProber(ProbeResult.Success(1), ProbeResult.Success(2))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener, stepDelayMs = 0L).start()

        assertEquals(listOf(0L), scheduler.history)
        assertTrue(scheduler.hasPending())

        scheduler.fireNext()
        assertEquals(2, prober.callCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative step delay is rejected`() {
        ProbeCycleRunner(
            FakeProber(ProbeResult.Success(1)),
            target,
            FakeTracerScheduler(),
            RecordingCycleListener(),
            initialStepDelayMs = -1L,
        )
    }

    @Test
    fun `a failure retry is paced by the fixed retry floor, never by the configured step delay`() {
        val prober = FakeProber(ProbeResult.Failure, ProbeResult.Failure, ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener, stepDelayMs = 1000L).start()
        scheduler.fireNext() // first failure's retry delay elapses -> second attempt
        scheduler.fireNext() // second failure's retry delay elapses -> third attempt (success)

        assertEquals(3, prober.callCount)
        // Both retries used the fixed floor, not the configured 1000ms step delay -- only the
        // final entry, after the eventual success, is the step delay.
        assertEquals(
            listOf(
                ProbeCycleRunner.FAILURE_RETRY_DELAY_MS,
                ProbeCycleRunner.FAILURE_RETRY_DELAY_MS,
                1000L,
            ),
            scheduler.history,
        )
    }

    // --- Live-updatable target and pacing (a cycle already running, not a fresh one) ------

    /**
     * Regression test for the "changing the pacing slider does nothing" defect: a caller
     * whose visibility logic treats "already running" as a no-op (see
     * `UplinkStatusService.applyVisibility`'s `ENABLED` branch) has no way to reach an
     * already-constructed cycle except through [ProbeCycleRunner.updateStepDelayMs]. Without
     * it, a `stepDelayMs` preference change made while the tracer was running would silently
     * apply to nothing until an unrelated event happened to stop and restart the cycle.
     */
    @Test
    fun `updateStepDelayMs changes the delay used for the next scheduled step`() {
        val prober = FakeProber(ProbeResult.Success(1), ProbeResult.Success(2))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()
        val cycleRunner = runner(prober, scheduler, listener, stepDelayMs = 500L)

        cycleRunner.start()
        assertEquals(listOf(500L), scheduler.history)

        // The already-pending step keeps the delay it was scheduled with -- there is nothing
        // to rewrite mid-wait. What has to change is the *next* one.
        cycleRunner.updateStepDelayMs(137L)
        scheduler.fireNext()

        assertEquals(listOf(500L, 137L), scheduler.history)
    }

    @Test
    fun `updateTarget changes the host used for the next probe`() {
        val prober = FakeProber(ProbeResult.Success(1), ProbeResult.Success(2))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()
        val cycleRunner = runner(prober, scheduler, listener)

        cycleRunner.start()
        val newTarget = ProbeTarget(host = "custom.example.invalid")

        cycleRunner.updateTarget(newTarget)
        scheduler.fireNext() // the already-pending step -> the second, now-updated probe

        assertEquals(listOf(target, newTarget), prober.targetsProbed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateStepDelayMs rejects a negative value, same as the constructor`() {
        val cycleRunner = runner(FakeProber(ProbeResult.Success(1)), FakeTracerScheduler(), RecordingCycleListener())

        cycleRunner.updateStepDelayMs(-1L)
    }

    // --- Freeze-on-failure / resume-on-success --------------------------------------

    @Test
    fun `probe failure fires no ack and freezes the tracer at its current position`() {
        val prober = FakeProber(ProbeResult.Failure, ProbeResult.Failure, ProbeResult.Success(5))
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()
        val tracer = AckTracer(BarPosition.BAR_3)

        runner(prober, scheduler, listener, tracer).start() // attempt 1: failure
        scheduler.fireNext() // retry delay -> attempt 2: failure
        scheduler.fireNext() // retry delay -> attempt 3: success

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

        runner(prober, scheduler, listener, tracer).start() // attempt 1: failure
        scheduler.fireNext() // retry delay -> attempt 2: failure
        scheduler.fireNext() // retry delay -> attempt 3: success

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

        runner(prober, scheduler, listener).start() // attempt 1: dns failure
        scheduler.fireNext() // retry delay -> attempt 2: generic failure
        scheduler.fireNext() // retry delay -> attempt 3: success

        val frozenEvents = listener.events.filterIsInstance<CycleEvent.Frozen>()
        assertEquals(2, frozenEvents.size)
        assertEquals(FreezeReason.DNS_RESOLUTION_FAILURE, frozenEvents[0].reason)
        assertEquals(FreezeReason.PROBE_FAILURE, frozenEvents[1].reason)
    }

    // --- Failure-retry pacing (the reconnect-burst battery fix) -----------------------

    @Test
    fun `failed probes are paced by the fixed retry floor, not retried back-to-back`() {
        // Regression test for the reconnect-burst battery drain: a run of near-instant
        // failures (e.g. DNS resolution failing for a moment while reconnecting) must not
        // spin the retry loop as fast as the CPU allows -- each retry has to wait on a
        // scheduled delay, not happen inline in the same call.
        val prober = FakeProber(
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Failure,
            ProbeResult.Success(1),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        // Only the first attempt has happened -- the next one is waiting on a scheduled
        // retry, not looping inline the way it used to.
        assertEquals(1, prober.callCount)
        assertEquals(listOf(ProbeCycleRunner.FAILURE_RETRY_DELAY_MS), scheduler.history)

        scheduler.fireNext()
        scheduler.fireNext()
        assertEquals(3, prober.callCount)

        scheduler.fireNext() // third retry delay elapses -> the eventual success
        assertEquals(4, prober.callCount)
        assertEquals(
            listOf(
                ProbeCycleRunner.FAILURE_RETRY_DELAY_MS,
                ProbeCycleRunner.FAILURE_RETRY_DELAY_MS,
                ProbeCycleRunner.FAILURE_RETRY_DELAY_MS,
                500L,
            ),
            scheduler.history,
        )
    }

    @Test
    fun `dns failures are paced by the fixed retry floor too, not just generic failures`() {
        val prober = FakeProber(
            ProbeResult.DnsResolutionFailure,
            ProbeResult.DnsResolutionFailure,
            ProbeResult.Success(1),
        )
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()

        runner(prober, scheduler, listener).start()
        scheduler.fireNext()
        scheduler.fireNext()

        assertEquals(3, prober.callCount)
        assertEquals(
            listOf(ProbeCycleRunner.FAILURE_RETRY_DELAY_MS, ProbeCycleRunner.FAILURE_RETRY_DELAY_MS, 500L),
            scheduler.history,
        )
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
    fun `stop from another thread discards the result of a probe still in flight`() {
        // Regression test for the outage-starves-stop() defect: a probe attempt that is
        // itself still blocking inside Prober.probe (up to its own timeout) has no
        // pendingTask for stop() to cancel -- per the class doc's threading contract, that's
        // the one case stop() still has to work from a *different* thread, mid-probe, rather
        // than relying on cancelling a scheduled retry. This models exactly that: the probe
        // blocks until the test releases it, and stop() is called from the test thread while
        // it's blocked.
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val probeCount = AtomicInteger()
        val prober = object : Prober {
            override fun probe(target: ProbeTarget): ProbeResult {
                probeCount.incrementAndGet()
                probeEntered.countDown()
                releaseProbe.await()
                return ProbeResult.Failure
            }
        }
        val scheduler = FakeTracerScheduler()
        val listener = RecordingCycleListener()
        val cycleRunner = ProbeCycleRunner(prober, target, scheduler, listener)

        val cycleThread = Thread { cycleRunner.start() }.apply { start() }
        assertTrue("the cycle never reached its first probe", probeEntered.await(5, TimeUnit.SECONDS))

        cycleRunner.stop()
        releaseProbe.countDown() // the in-flight probe now returns Failure
        cycleThread.join(5_000)

        assertFalse("the cycle thread never exited after stop()", cycleThread.isAlive)
        // The result of the probe that was already in flight when stop() landed must be
        // discarded outright: emitting it would post a notification into a listener whose
        // owner (the service) has already torn down -- the user-visible half of this bug.
        assertEquals(emptyList<CycleEvent>(), listener.events)
        assertEquals(1, probeCount.get())
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
