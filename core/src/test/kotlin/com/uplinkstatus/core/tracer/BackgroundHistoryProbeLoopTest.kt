package com.uplinkstatus.core.tracer

import com.uplinkstatus.core.fakes.FakeProber
import com.uplinkstatus.core.fakes.FakeTracerScheduler
import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHistoryProbeLoopTest {

    private val target = ProbeTarget(host = "one.one.one.one")

    private fun loop(
        prober: FakeProber,
        scheduler: FakeTracerScheduler,
        results: MutableList<ProbeResult> = mutableListOf(),
        retryDelayMs: Long = BackgroundHistoryProbeLoop.MIN_RETRY_DELAY_MS,
    ) = BackgroundHistoryProbeLoop(
        prober = prober,
        initialTarget = target,
        scheduler = scheduler,
        onResult = { results += it },
        initialRetryDelayMs = retryDelayMs,
    )

    @Test
    fun `starting the loop makes exactly one immediate probe attempt`() {
        val prober = FakeProber(ProbeResult.Success(42))
        val scheduler = FakeTracerScheduler()
        val results = mutableListOf<ProbeResult>()

        loop(prober, scheduler, results).start()

        assertEquals(1, prober.callCount)
        assertEquals(listOf<ProbeResult>(ProbeResult.Success(42)), results)
    }

    @Test
    fun `every attempt is paced by retryDelayMs, success or failure alike -- no immediate-retry branch`() {
        val prober = FakeProber(ProbeResult.Failure, ProbeResult.Success(10))
        val scheduler = FakeTracerScheduler()

        loop(prober, scheduler, retryDelayMs = 300L).start()

        // Unlike ProbeCycleRunner (which paces a success by the user's step-delay preference
        // but a failure by its own separate fixed floor), this class paces every attempt --
        // success or failure alike -- by the same single retryDelayMs.
        assertEquals(listOf(300L), scheduler.history)
        assertEquals(1, prober.callCount)

        scheduler.fireNext()

        assertEquals(2, prober.callCount)
        assertEquals(listOf(300L, 300L), scheduler.history)
    }

    @Test
    fun `stop prevents further probe attempts`() {
        val prober = FakeProber(ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val instance = loop(prober, scheduler)

        instance.start()
        instance.stop()

        assertFalse(scheduler.hasPending())
        assertFalse(instance.isRunning)
    }

    @Test
    fun `stop discards the result of a probe already in flight`() {
        val scheduler = FakeTracerScheduler()
        val results = mutableListOf<ProbeResult>()
        var stopBeforeReturning: (() -> Unit)? = null
        val instance = BackgroundHistoryProbeLoop(
            prober = Prober { stopBeforeReturning?.invoke(); ProbeResult.Success(1) },
            initialTarget = target,
            scheduler = scheduler,
            onResult = { results += it },
        )
        stopBeforeReturning = { instance.stop() }

        instance.start()

        assertTrue(results.isEmpty())
        assertFalse(scheduler.hasPending())
    }

    @Test
    fun `start is a no-op while already running`() {
        val prober = FakeProber(ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val instance = loop(prober, scheduler)

        instance.start()
        instance.start()

        assertEquals(1, prober.callCount)
    }

    @Test
    fun `updateTarget changes the host used for the next probe`() {
        val prober = FakeProber(ProbeResult.Success(1), ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val instance = loop(prober, scheduler)

        instance.start()
        instance.updateTarget(ProbeTarget(host = "custom.example.invalid"))
        scheduler.fireNext()

        assertEquals("custom.example.invalid", prober.targetsProbed.last().host)
    }

    @Test
    fun `updateRetryDelayMs changes the delay used for the next scheduled attempt`() {
        val prober = FakeProber(ProbeResult.Success(1), ProbeResult.Success(1))
        val scheduler = FakeTracerScheduler()
        val instance = loop(prober, scheduler, retryDelayMs = 300L)

        instance.start()
        instance.updateRetryDelayMs(500L)
        scheduler.fireNext()

        assertEquals(listOf(300L, 500L), scheduler.history)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a retry delay below the floor is rejected at construction`() {
        loop(FakeProber(), FakeTracerScheduler(), retryDelayMs = BackgroundHistoryProbeLoop.MIN_RETRY_DELAY_MS - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateRetryDelayMs rejects a value below the floor, same as the constructor`() {
        val instance = loop(FakeProber(), FakeTracerScheduler())
        instance.updateRetryDelayMs(BackgroundHistoryProbeLoop.MIN_RETRY_DELAY_MS - 1)
    }
}
