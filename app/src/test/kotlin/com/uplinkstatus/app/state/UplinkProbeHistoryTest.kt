package com.uplinkstatus.app.state

import com.uplinkstatus.core.history.ProbeHistory
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
 * [UplinkProbeHistory] as a publisher: what it holds, what the user's reset does to it, and
 * that its default clock produces usable, ordered timestamps on a real Android runtime.
 *
 * The windowing/aggregation rules themselves belong to
 * [com.uplinkstatus.core.history.ProbeHistory] and are covered by `:core`'s own plain-JVM
 * `ProbeHistoryTest`; which *events* produce a sample belongs to
 * [com.uplinkstatus.app.service.UplinkNotificationController] and is covered there. This file
 * is only about the singleton in between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UplinkProbeHistoryTest {

    @Before
    fun reset() = UplinkProbeHistory.resetForTest()

    @Test
    fun `starts empty -- a fresh process has measured nothing, per the session-only rule`() {
        assertEquals(0, UplinkProbeHistory.history.value.attemptCount)
        assertNull(UplinkProbeHistory.history.value.successPercent)
        assertEquals(ProbeHistory.DEFAULT_WINDOW_MS, UplinkProbeHistory.history.value.windowMs)
    }

    @Test
    fun `records successes and failures into the published history`() {
        UplinkProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)
        UplinkProbeHistory.recordFailure(timestampMs = 2_000)
        UplinkProbeHistory.recordSuccess(latencyMs = 30, timestampMs = 3_000)

        val history = UplinkProbeHistory.history.value
        assertEquals(3, history.attemptCount)
        assertEquals(2, history.successCount)
        assertEquals(20L, history.averageLatencyMs)
    }

    @Test
    fun `reset clears every sample immediately, without waiting on the service`() {
        UplinkProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)
        UplinkProbeHistory.recordFailure(timestampMs = 2_000)

        UplinkProbeHistory.reset()

        assertEquals(0, UplinkProbeHistory.history.value.attemptCount)
        assertNull(UplinkProbeHistory.history.value.successPercent)
    }

    @Test
    fun `reset keeps the configured window -- it clears history, it does not undo a setting`() {
        UplinkProbeHistory.setWindowMs(90_000)
        UplinkProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)

        UplinkProbeHistory.reset()

        assertEquals(90_000L, UplinkProbeHistory.history.value.windowMs)
    }

    @Test
    fun `setWindowMs prunes what the narrower window has already outlived`() {
        UplinkProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 0)
        UplinkProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 120_000)
        assertEquals(2, UplinkProbeHistory.history.value.attemptCount)

        UplinkProbeHistory.setWindowMs(60_000)

        assertEquals(1, UplinkProbeHistory.history.value.attemptCount)
        assertEquals(60_000L, UplinkProbeHistory.history.value.windowMs)
    }

    @Test
    fun `recording republishes a new value so a Compose collector actually recomposes`() {
        val before = UplinkProbeHistory.history.value

        UplinkProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)

        // StateFlow only emits on a changed value, so an equal instance would leave the
        // graphs frozen on screen while samples piled up behind them.
        assertNotEquals(before, UplinkProbeHistory.history.value)
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
        UplinkProbeHistory.recordSuccess(latencyMs = 10)
        UplinkProbeHistory.recordFailure()
        UplinkProbeHistory.recordSuccess(latencyMs = 20)

        val timestamps = UplinkProbeHistory.history.value.samples.map { it.timestampMs }
        assertEquals(3, timestamps.size)
        assertEquals(timestamps.sorted(), timestamps)
        assertTrue(timestamps.all { it >= 0 })
    }
}
