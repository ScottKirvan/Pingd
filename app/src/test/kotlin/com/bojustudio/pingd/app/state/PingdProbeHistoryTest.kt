package com.bojustudio.pingd.app.state

import com.bojustudio.pingd.core.history.ProbeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PingdProbeHistory] as a publisher: what it holds, what the user's reset does to it, and
 * that its default clock produces usable, ordered timestamps on a real Android runtime.
 *
 * The windowing/aggregation rules themselves belong to
 * [com.bojustudio.pingd.core.history.ProbeHistory] and are covered by `:core`'s own plain-JVM
 * `ProbeHistoryTest`; which *events* produce a sample belongs to
 * [com.bojustudio.pingd.app.service.PingdNotificationController] and is covered there. This file
 * is only about the singleton in between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PingdProbeHistoryTest {

    @Before
    fun reset() = PingdProbeHistory.resetForTest()

    @Test
    fun `starts empty -- a fresh process has measured nothing, per the session-only rule`() {
        assertEquals(0, PingdProbeHistory.history.value.attemptCount)
        assertNull(PingdProbeHistory.history.value.successPercent)
        assertEquals(ProbeHistory.DEFAULT_WINDOW_MS, PingdProbeHistory.history.value.windowMs)
    }

    @Test
    fun `records successes and failures into the published history`() {
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)
        PingdProbeHistory.recordFailure(timestampMs = 2_000)
        PingdProbeHistory.recordSuccess(latencyMs = 30, timestampMs = 3_000)

        val history = PingdProbeHistory.history.value
        assertEquals(3, history.attemptCount)
        assertEquals(2, history.successCount)
        assertEquals(20L, history.averageLatencyMs)
    }

    @Test
    fun `reset clears every sample immediately, without waiting on the service`() {
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)
        PingdProbeHistory.recordFailure(timestampMs = 2_000)

        PingdProbeHistory.reset()

        assertEquals(0, PingdProbeHistory.history.value.attemptCount)
        assertNull(PingdProbeHistory.history.value.successPercent)
    }

    @Test
    fun `reset keeps the configured window -- it clears history, it does not undo a setting`() {
        PingdProbeHistory.setWindowMs(90_000)
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)

        PingdProbeHistory.reset()

        assertEquals(90_000L, PingdProbeHistory.history.value.windowMs)
    }

    @Test
    fun `setWindowMs changes what is displayed immediately, without discarding older samples`() {
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 0)
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 120_000)
        assertEquals(2, PingdProbeHistory.history.value.attemptCount)

        PingdProbeHistory.setWindowMs(60_000)

        assertEquals(1, PingdProbeHistory.history.value.attemptCount)
        assertEquals(60_000L, PingdProbeHistory.history.value.windowMs)
        // The narrower window changed what's displayed, not what's retained (issue #39).
        assertEquals(2, PingdProbeHistory.history.value.samples.size)

        PingdProbeHistory.setWindowMs(150_000)

        // Widening back reveals the older sample again -- it was never actually discarded.
        assertEquals(2, PingdProbeHistory.history.value.attemptCount)
    }

    @Test
    fun `recording republishes a new value so a Compose collector actually recomposes`() {
        val before = PingdProbeHistory.history.value

        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)

        // StateFlow only emits on a changed value, so an equal instance would leave the
        // graphs frozen on screen while samples piled up behind them.
        assertNotEquals(before, PingdProbeHistory.history.value)
    }

    @Test
    fun `recordMasterToggleTransition adds a marker without counting as an attempt`() {
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 0)
        PingdProbeHistory.recordMasterToggleTransition(timestampMs = 500)
        PingdProbeHistory.recordSuccess(latencyMs = 20, timestampMs = 1_000)

        val history = PingdProbeHistory.history.value
        assertEquals(2, history.attemptCount)
        assertEquals(listOf(500L), history.markers)
    }

    @Test
    fun `reset clears markers along with samples`() {
        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 0)
        PingdProbeHistory.recordMasterToggleTransition(timestampMs = 500)

        PingdProbeHistory.reset()

        assertTrue(PingdProbeHistory.history.value.markers.isEmpty())
    }

    /**
     * The default timestamp source has to be monotonic and has to produce a *duration* the
     * window can be measured against — `SystemClock.elapsedRealtime`, not the wall clock,
     * which an NTP correction can move backwards and corrupt the sample ordering the whole
     * window depends on. This exercises the real default rather than the explicit-timestamp
     * overload every other test here uses.
     */
    @Test
    fun `the default clock timestamps samples in the order they were recorded`() {
        PingdProbeHistory.recordSuccess(latencyMs = 10)
        PingdProbeHistory.recordFailure()
        PingdProbeHistory.recordSuccess(latencyMs = 20)

        val timestamps = PingdProbeHistory.history.value.samples.map { it.timestampMs }
        assertEquals(3, timestamps.size)
        assertEquals(timestamps.sorted(), timestamps)
        assertTrue(timestamps.all { it >= 0 })
    }
}
