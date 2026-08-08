package com.uplinkstatus.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM unit tests for the recording/windowing/aggregation rules behind the settings
 * screen's history graphs — no Android dependency, no Robolectric, because [ProbeHistory] is
 * a pure value and every rule below is a function of the samples it was handed.
 *
 * The `:app` side (which real events actually produce a sample, and which must produce none)
 * is covered by `UplinkNotificationControllerTest`; this file covers what happens to samples
 * once they arrive.
 */
class ProbeHistoryTest {

    private val window = 60_000L

    // --- Success percentage -------------------------------------------------------------

    @Test
    fun `an empty history has no percentage at all -- not zero`() {
        // "Nothing measured yet" and "everything failed" are completely different states;
        // seeding the display with 0% would report an outage that never happened.
        assertNull(ProbeHistory(windowMs = window).successPercent)
        assertEquals(0, ProbeHistory(windowMs = window).attemptCount)
    }

    @Test
    fun `success percentage counts every real attempt, successes and failures alike`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(1_000, latencyMs = 10)
            .recordSuccess(2_000, latencyMs = 12)
            .recordSuccess(3_000, latencyMs = 14)
            .recordFailure(4_000)

        assertEquals(4, history.attemptCount)
        assertEquals(3, history.successCount)
        assertEquals(75f, history.successPercent!!, 0.001f)
    }

    @Test
    fun `a sustained outage's repeated failures each count, driving the percentage down`() {
        // ProbeCycleRunner retries immediately with no back-off, emitting one Frozen per
        // attempt -- every one of those is a real probe that really failed.
        var history = ProbeHistory(windowMs = window).recordSuccess(0, latencyMs = 20)
        repeat(9) { attempt -> history = history.recordFailure(1_000L + attempt) }

        assertEquals(10, history.attemptCount)
        assertEquals(10f, history.successPercent!!, 0.001f)
    }

    @Test
    fun `an all-failure history reports zero percent, which is a real measurement`() {
        val history = ProbeHistory(windowMs = window)
            .recordFailure(1_000)
            .recordFailure(1_100)

        assertEquals(0f, history.successPercent!!, 0.001f)
        assertNotNull(history.successPercent)
    }

    // --- Latency aggregation ------------------------------------------------------------

    @Test
    fun `average latency ignores failures rather than counting them as zero`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(1_000, latencyMs = 10)
            .recordFailure(2_000)
            .recordSuccess(3_000, latencyMs = 20)

        // (10 + 20) / 2 = 15, not (10 + 0 + 20) / 3 = 10.
        assertEquals(15L, history.averageLatencyMs)
    }

    @Test
    fun `average latency is null while nothing has succeeded, even with failed attempts`() {
        val history = ProbeHistory(windowMs = window).recordFailure(1_000).recordFailure(1_100)

        assertNull(history.averageLatencyMs)
        assertNull(history.latestLatencyMs)
    }

    @Test
    fun `latest latency is the newest successful probe, not the newest attempt`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(1_000, latencyMs = 11)
            .recordSuccess(2_000, latencyMs = 22)
            .recordFailure(3_000)

        assertEquals(22L, history.latestLatencyMs)
    }

    // --- Windowing / pruning ------------------------------------------------------------

    @Test
    fun `samples older than the window are dropped as newer ones arrive`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 100)
            .recordSuccess(5_000, latencyMs = 50)
            .recordSuccess(12_000, latencyMs = 20)

        // t=0 is 12s behind the newest sample, past the 10s window; t=5000 is 7s behind, inside.
        assertEquals(listOf(5_000L, 12_000L), history.samples.map { it.timestampMs })
    }

    @Test
    fun `a sample exactly at the window edge is kept, one millisecond older is not`() {
        val atEdge = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 100)
            .recordSuccess(10_000, latencyMs = 20)
        assertEquals(2, atEdge.attemptCount)

        val justPast = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 100)
            .recordSuccess(10_001, latencyMs = 20)
        assertEquals(1, justPast.attemptCount)
    }

    @Test
    fun `pruning drops failures too -- the percentage is over the window, not all time`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordFailure(0)
            .recordFailure(1_000)
            .recordSuccess(20_000, latencyMs = 30)

        assertEquals(1, history.attemptCount)
        assertEquals(100f, history.successPercent!!, 0.001f)
    }

    @Test
    fun `shortening the window prunes immediately, not at the next probe`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(30_000, latencyMs = 20)
            .recordSuccess(50_000, latencyMs = 30)

        val narrowed = history.withWindowMs(25_000)

        assertEquals(listOf(30_000L, 50_000L), narrowed.samples.map { it.timestampMs })
        assertEquals(25_000L, narrowed.windowMs)
    }

    @Test
    fun `widening the window keeps what is left but cannot resurrect pruned samples`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(30_000, latencyMs = 20)

        val widened = history.withWindowMs(60_000)

        // The t=0 sample was already gone when it fell out of the 10s window -- widening
        // afterward changes retention going forward, it does not un-drop history.
        assertEquals(listOf(30_000L), widened.samples.map { it.timestampMs })
        assertEquals(60_000L, widened.windowMs)
    }

    @Test
    fun `the sample cap bounds memory even when the window would keep everything`() {
        // Free-wheeling pacing (0ms step delay) can produce probes far faster than the window
        // retires them; MAX_SAMPLES is what keeps that from growing without bound.
        var history = ProbeHistory(windowMs = Long.MAX_VALUE / 4)
        repeat(ProbeHistory.MAX_SAMPLES + 500) { index ->
            history = history.recordSuccess(index.toLong(), latencyMs = 5)
        }

        assertEquals(ProbeHistory.MAX_SAMPLES, history.attemptCount)
        // Oldest go first, so the newest sample is always retained.
        assertEquals(
            (ProbeHistory.MAX_SAMPLES + 499).toLong(),
            history.samples.last().timestampMs,
        )
    }

    @Test
    fun `span is what the retained samples actually cover, never more than the window`() {
        assertEquals(0L, ProbeHistory(windowMs = window).spanMs)
        assertEquals(0L, ProbeHistory(windowMs = window).recordSuccess(1_000, 10).spanMs)

        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(4_000, latencyMs = 10)
        assertEquals(4_000L, history.spanMs)

        assertTrue(history.recordSuccess(30_000, latencyMs = 10).spanMs <= 10_000L)
    }

    // --- Reset --------------------------------------------------------------------------

    @Test
    fun `clearing drops every sample but keeps the configured window`() {
        val history = ProbeHistory(windowMs = 12_345)
            .recordSuccess(1_000, latencyMs = 10)
            .recordFailure(2_000)

        val cleared = history.cleared()

        assertEquals(0, cleared.attemptCount)
        assertNull(cleared.successPercent)
        assertNull(cleared.averageLatencyMs)
        assertEquals(12_345L, cleared.windowMs)
    }

    // --- Latency sparkline: gaps ---------------------------------------------------------

    @Test
    fun `a failed probe is a gap in the latency line -- not a zero, not an omitted point`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)
            .recordSuccess(2_000, latencyMs = 20)

        val points = history.latencySparkline()

        // Three points for three attempts: the failure keeps its place on the timeline (so
        // the line breaks exactly where the outage was) and carries no value.
        assertEquals(3, points.size)
        assertNotNull(points[0].y)
        assertNull(points[1].y)
        assertNotNull(points[2].y)
        // ...and it is genuinely absent rather than plotted at the bottom of the scale.
        assertEquals(0f, points[0].y!!, 0.001f)
        assertEquals(1f, points[2].y!!, 0.001f)
    }

    @Test
    fun `latency points are positioned by when they happened, across the retained span`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(2_500, latencyMs = 20)
            .recordSuccess(10_000, latencyMs = 30)

        val points = history.latencySparkline()

        assertEquals(0f, points[0].x, 0.001f)
        assertEquals(0.25f, points[1].x, 0.001f)
        assertEquals(1f, points[2].x, 0.001f)
    }

    @Test
    fun `identical latencies sit on the middle line rather than being pinned to an edge`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 42)
            .recordSuccess(1_000, latencyMs = 42)

        history.latencySparkline().forEach { point ->
            assertEquals(ProbeHistory.FLAT_LINE_Y, point.y!!, 0.001f)
        }
    }

    @Test
    fun `a single sample is plotted at the newest end of the axis`() {
        val points = ProbeHistory(windowMs = window).recordSuccess(5_000, latencyMs = 9).latencySparkline()

        assertEquals(1, points.size)
        assertEquals(1f, points[0].x, 0.001f)
    }

    @Test
    fun `an empty history draws no latency line at all`() {
        assertTrue(ProbeHistory(windowMs = window).latencySparkline().isEmpty())
        assertTrue(ProbeHistory(windowMs = window).successSparkline().isEmpty())
    }

    @Test
    fun `an all-failure history draws a latency line of nothing but gaps`() {
        var history = ProbeHistory(windowMs = window)
        repeat(5) { index -> history = history.recordFailure(index * 1_000L) }

        val points = history.latencySparkline()

        assertEquals(5, points.size)
        assertTrue(points.all { it.y == null })
    }

    // --- Success sparkline: bucketing -----------------------------------------------------

    @Test
    fun `the success line plots a rate per time bucket, not a zero-or-one square wave`() {
        // 8 attempts over 8 seconds, 6 of them successful, split evenly across two buckets:
        // bucket 1 has 3/4, bucket 2 has 3/4.
        var history = ProbeHistory(windowMs = window)
        listOf(true, true, true, false, true, true, true, false).forEachIndexed { index, ok ->
            val at = index * 1_000L
            history = if (ok) history.recordSuccess(at, latencyMs = 10) else history.recordFailure(at)
        }

        val points = history.successSparkline()

        assertEquals(2, points.size)
        assertEquals(0.75f, points[0].y!!, 0.001f)
        assertEquals(0.75f, points[1].y!!, 0.001f)
    }

    @Test
    fun `success buckets use an absolute zero-to-one scale, not an autoscaled one`() {
        var history = ProbeHistory(windowMs = window)
        repeat(8) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        // All-success buckets sit at the top of the scale, and would still sit at the top if
        // the rate were, say, uniformly 50% -- an autoscaled percentage graph would be
        // unreadable, since "always 50%" and "always 100%" would look identical.
        assertTrue(history.successSparkline().all { it.y == 1f })
    }

    @Test
    fun `resolution grows with the data instead of dotting out one sample per bucket`() {
        var history = ProbeHistory(windowMs = window)
        repeat(3) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }
        // Fewer attempts than one bucket's worth: a single honest point, not three.
        assertEquals(1, history.successSparkline().size)

        repeat(200) { index -> history = history.recordSuccess(10_000L + index * 100L, latencyMs = 10) }
        val points = history.successSparkline(maxBuckets = 10)
        // Plenty of samples now, but never more buckets than asked for.
        assertEquals(10, points.size)
    }

    @Test
    fun `a time bucket with no attempts in it is a gap, same rule as the latency line`() {
        // A long silence in the middle (nothing probed between t=1s and t=59s) must read as
        // "no data here," not as an interpolated rate nobody measured.
        var history = ProbeHistory(windowMs = 120_000)
        repeat(4) { index -> history = history.recordSuccess(index * 250L, latencyMs = 10) }
        repeat(4) { index -> history = history.recordFailure(60_000L + index * 250L) }

        val points = history.successSparkline(maxBuckets = 8)

        assertEquals(2, points.size)
        assertEquals(1f, points.first().y!!, 0.001f)
        assertEquals(0f, points.last().y!!, 0.001f)

        // With more buckets than the two clusters can fill, the middle really is empty.
        var sparse = ProbeHistory(windowMs = 120_000)
        repeat(20) { index -> sparse = sparse.recordSuccess(index * 100L, latencyMs = 10) }
        repeat(20) { index -> sparse = sparse.recordFailure(60_000L + index * 100L) }
        val sparsePoints = sparse.successSparkline(maxBuckets = 10)
        assertTrue(sparsePoints.any { it.y == null })
    }

    @Test
    fun `success buckets span the full axis from oldest to newest`() {
        var history = ProbeHistory(windowMs = window)
        repeat(40) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        val points = history.successSparkline(maxBuckets = 5)

        assertEquals(0f, points.first().x, 0.001f)
        assertEquals(1f, points.last().x, 0.001f)
    }

    // --- Markers: master-toggle transitions -----------------------------------------------

    @Test
    fun `a marker does not count as an attempt and does not affect the percentage or latency`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(500)
            .recordSuccess(1_000, latencyMs = 20)

        assertEquals(2, history.attemptCount)
        assertEquals(15L, history.averageLatencyMs)
        assertEquals(listOf(500L), history.markers)
    }

    @Test
    fun `markers are pruned by the same window as samples`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordMarker(0)
            .recordSuccess(20_000, latencyMs = 10)

        assertTrue(history.markers.isEmpty())
    }

    @Test
    fun `narrowing the window prunes markers immediately too`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(1_000)
            .recordSuccess(50_000, latencyMs = 20)

        val narrowed = history.withWindowMs(10_000)

        assertTrue(narrowed.markers.isEmpty())
    }

    @Test
    fun `clearing drops markers along with samples`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(500)

        assertTrue(history.cleared().markers.isEmpty())
    }

    @Test
    fun `marker fractions are positioned across the same axis the sparklines use`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(2_500)
            .recordSuccess(10_000, latencyMs = 20)

        assertEquals(listOf(0.25f), history.markerFractions())
    }

    @Test
    fun `a marker outside the retained samples' own span contributes no fraction`() {
        // The samples span 0..1000; a marker from before the app was even installed (or from
        // after every retained sample -- e.g. the app was switched off and nothing has probed
        // since) has no meaningful position on that axis.
        val before = ProbeHistory(windowMs = window)
            .recordMarker(0)
            .recordSuccess(500, latencyMs = 10)
            .recordSuccess(1_000, latencyMs = 20)
        assertTrue(before.markerFractions().isEmpty())

        val after = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(500, latencyMs = 20)
            .recordMarker(1_000)
        assertTrue(after.markerFractions().isEmpty())
    }

    @Test
    fun `fewer than two samples means no axis to place a marker on`() {
        assertTrue(ProbeHistory(windowMs = window).recordMarker(0).markerFractions().isEmpty())
        assertTrue(
            ProbeHistory(windowMs = window).recordSuccess(0, 10).recordMarker(0).markerFractions().isEmpty(),
        )
    }

    // --- Guard rails ----------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive window is rejected rather than silently retaining nothing`() {
        ProbeHistory(windowMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative latency is rejected -- it is not a measurement`() {
        ProbeHistory(windowMs = window).recordSuccess(1_000, latencyMs = -1)
    }
}
