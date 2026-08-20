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
        // ProbeCycleRunner keeps retrying (each attempt paced by a small fixed floor delay,
        // not zero), emitting one Frozen per attempt -- every one of those is a real probe
        // that really failed.
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

    // --- Windowing / display filtering (issue #39: retention is decoupled from the window) ---

    @Test
    fun `samples older than the window stay in storage -- only the displayed count narrows`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 100)
            .recordSuccess(5_000, latencyMs = 50)
            .recordSuccess(12_000, latencyMs = 20)

        // t=0 is 12s behind the newest sample, past the 10s window; t=5000 is 7s behind, inside.
        // Both are still retained in storage -- the window only narrows what is *displayed*.
        assertEquals(listOf(0L, 5_000L, 12_000L), history.samples.map { it.timestampMs })
        assertEquals(2, history.attemptCount)
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
    fun `old failures are excluded from the displayed percentage, but not discarded from storage`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordFailure(0)
            .recordFailure(1_000)
            .recordSuccess(20_000, latencyMs = 30)

        assertEquals(1, history.attemptCount)
        assertEquals(100f, history.successPercent!!, 0.001f)
        assertEquals(3, history.samples.size)
    }

    @Test
    fun `shortening the window changes what is displayed immediately, not at the next probe`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(30_000, latencyMs = 20)
            .recordSuccess(50_000, latencyMs = 30)

        val narrowed = history.withWindowMs(25_000)

        // Displayed immediately reflects the narrower window...
        assertEquals(2, narrowed.attemptCount)
        assertEquals(25L, narrowed.averageLatencyMs) // (20 + 30) / 2, excludes the t=0 sample.
        assertEquals(25_000L, narrowed.windowMs)
        // ...but storage is untouched -- narrowing does not discard anything (issue #39).
        assertEquals(listOf(0L, 30_000L, 50_000L), narrowed.samples.map { it.timestampMs })
    }

    /**
     * Regression test for issue #39: "narrowing the history window slider permanently discards
     * data that widening it back can't recover." Narrowing to zoom in on recent data must not
     * throw away what a subsequent widening should be able to show again.
     */
    @Test
    fun `narrowing then widening the window redisplays samples that were only hidden, not lost`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(30_000, latencyMs = 20)

        val narrowed = history.withWindowMs(10_000)

        // Narrowed to 10s: the t=0 sample is 30s behind the newest, outside the window, so it
        // drops out of what's *displayed*...
        assertEquals(1, narrowed.attemptCount)
        assertEquals(20L, narrowed.averageLatencyMs)
        // ...but it must still be *retained* underneath -- this is the crux of the bug.
        assertEquals(2, narrowed.samples.size)

        val widened = narrowed.withWindowMs(60_000)

        // Widening back reveals the older sample again: it was hidden, never actually thrown
        // away. Before the fix, this would still show only 1 attempt / 20L average, because
        // withWindowMs had already pruned the t=0 sample out of storage when narrowing.
        assertEquals(2, widened.attemptCount)
        assertEquals(15L, widened.averageLatencyMs)
        assertEquals(listOf(0L, 30_000L), widened.samples.map { it.timestampMs })
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

    /**
     * Regression test for the on-device "the graph resets itself right when reconnecting"
     * report (predating `ProbeCycleRunner.FAILURE_RETRY_DELAY_MS`'s 250ms retry floor, which
     * a failed probe is now paced by instead of retrying with no delay at all). A
     * DNS-resolution failure specifically -- the exact condition seen for a moment while
     * reconnecting after a total outage, before the resolver is reachable again -- can still
     * return in low single-digit milliseconds, faster than the floor governs the steady case.
     * A burst of those can still rack up far more samples per second than ordinary pacing;
     * against a cap sized only for steady-state pacing, that burst alone could evict an entire
     * prior window's worth of good data, which reads exactly like the history being cleared
     * even though nothing ever called [ProbeHistory.cleared]. [MAX_SAMPLES] must have enough
     * headroom that a burst like this doesn't touch older data.
     */
    @Test
    fun `a rapid failure burst right after reconnecting does not evict prior good data`() {
        var history = ProbeHistory(windowMs = 30 * 60_000L) // the widest configurable window
        repeat(600) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 20) }

        // A burst of near-instantaneous failures, 1ms apart -- large enough to have exceeded
        // the old 4096 cap on its own, let alone combined with the 600 samples before it.
        repeat(5_000) { index -> history = history.recordFailure(600_000L + index) }

        assertEquals(5_600, history.attemptCount)
        // The very first sample recorded, from well before the burst, is still there.
        assertEquals(0L, history.samples.first().timestampMs)
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
            .recordSuccess(2_000, latencyMs = 400)

        val points = history.latencySparkline()

        // Three points for three attempts: the failure keeps its place on the timeline (so
        // the line breaks exactly where the outage was) and carries no value.
        assertEquals(3, points.size)
        assertNotNull(points[0].y)
        assertNull(points[1].y)
        assertNotNull(points[2].y)
        // ...and it is genuinely absent rather than plotted at the bottom of the scale.
        // Fast plots high, slow plots low, on the fixed absolute scale: the 10ms sample sits
        // at the top, the 400ms (at-the-red-anchor) sample at the bottom -- "up" means "better."
        assertEquals(1f, points[0].y!!, 0.001f)
        assertEquals(0f, points[2].y!!, 0.001f)
    }

    /**
     * Regression test: the latency sparkline's y-axis previously plotted the *slowest* retained
     * latency at the top and the *fastest* at the bottom -- backwards from "fast on top, slow on
     * bottom," and the opposite of "up means better." Before the fix, this test's final two
     * assertions failed (the 10ms/fastest sample sat at `y = 0`, the 100ms/slowest sample sat at
     * `y = 1`).
     */
    @Test
    fun `the fastest latency plots at the top of the scale, the slowest at the bottom`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10) // fastest, below the green anchor
            .recordSuccess(1_000, latencyMs = 200) // exactly the yellow anchor -> middle
            .recordSuccess(2_000, latencyMs = 400) // at/beyond the red anchor -> bottom

        val points = history.latencySparkline()

        assertEquals(1f, points[0].y!!, 0.001f) // fastest -> top
        assertEquals(0.5f, points[1].y!!, 0.001f) // yellow anchor -> middle
        assertEquals(0f, points[2].y!!, 0.001f) // slowest -> bottom
    }

    // --- Latency sparkline: fixed absolute scale (not session-relative) -------------------

    /**
     * Regression test for the on-device report that the latency graph "appears to be auto
     * scaling" -- it previously plotted each point relative to that *session's own* observed
     * min/max latency, so the same latency value could land at a different height depending on
     * what else happened to be in the history. The fix ties `y` to the same fixed
     * green/yellow/red anchors [latencyColorFraction] already uses for color, so a given latency
     * always plots at the same height regardless of session history.
     */
    @Test
    fun `the same latency plots at the same height regardless of what else is in the session`() {
        // Two histories with wildly different min/max, but both containing a 90ms sample.
        val narrowRange = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 85)
            .recordSuccess(1_000, latencyMs = 90)
            .recordSuccess(2_000, latencyMs = 95)

        val wideRange = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 5)
            .recordSuccess(1_000, latencyMs = 90)
            .recordSuccess(2_000, latencyMs = 900)

        val narrowY = narrowRange.latencySparkline()[1].y!!
        val wideY = wideRange.latencySparkline()[1].y!!

        assertEquals(narrowY, wideY, 0.001f)
        // And it should match the fixed scale directly, not just match itself across histories.
        assertEquals(1f - latencyColorFraction(90), narrowY, 0.001f)
    }

    @Test
    fun `y decreases monotonically as latency increases across the green-yellow-red range`() {
        // Strictly between the green and red anchors, so every step is a real interpolated
        // move rather than two points both clamped flat at the same end.
        val latencies = listOf(50L, 75L, 125L, 200L, 300L, 400L)
        var history = ProbeHistory(windowMs = window)
        latencies.forEachIndexed { index, latencyMs -> history = history.recordSuccess(index * 1_000L, latencyMs) }

        val ys = history.latencySparkline().map { it.y!! }

        ys.zipWithNext().forEach { (earlier, later) ->
            assertTrue("y must strictly decrease as latency increases: $ys", later < earlier)
        }
    }

    @Test
    fun `latency at or beyond the red anchor clamps to the bottom of the scale instead of going off-canvas`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 400) // exactly the red anchor
            .recordSuccess(1_000, latencyMs = 50_000) // a wildly slow outlier

        val points = history.latencySparkline()

        assertEquals(0f, points[0].y!!, 0.001f)
        assertEquals(0f, points[1].y!!, 0.001f)
        points.forEach { assertTrue(it.y!! in 0f..1f) }
    }

    @Test
    fun `latency at or below the green anchor clamps to the top of the scale`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 0)
            .recordSuccess(1_000, latencyMs = 50) // exactly the green anchor

        val points = history.latencySparkline()

        assertEquals(1f, points[0].y!!, 0.001f)
        assertEquals(1f, points[1].y!!, 0.001f)
    }

    @Test
    fun `each latency point carries its own raw ms value alongside the scaled y position`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)
            .recordSuccess(2_000, latencyMs = 20)

        val points = history.latencySparkline()

        assertEquals(10L, points[0].latencyMs)
        // A gap carries no raw value either -- there is nothing measured to report.
        assertNull(points[1].latencyMs)
        assertEquals(20L, points[2].latencyMs)
    }

    @Test
    fun `latency points are positioned by when they happened, scaled to a fully-packed window`() {
        // windowMs set to exactly the samples' own span: the window is entirely full, so this
        // is the case where the window-anchored axis and "spans the retained data" coincide.
        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(2_500, latencyMs = 20)
            .recordSuccess(10_000, latencyMs = 30)

        val points = history.latencySparkline()

        assertEquals(0f, points[0].x, 0.001f)
        assertEquals(0.25f, points[1].x, 0.001f)
        assertEquals(1f, points[2].x, 0.001f)
    }

    @Test
    fun `identical latencies plot at the same fixed height, not an arbitrary middle line`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 42)
            .recordSuccess(1_000, latencyMs = 42)

        history.latencySparkline().forEach { point ->
            assertEquals(1f - latencyColorFraction(42), point.y!!, 0.001f)
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

    // --- Gap shading spans (sparklineGapFractions) ----------------------------------------

    @Test
    fun `a history with no failures has no gap spans to shade`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(1_000, latencyMs = 20)

        assertTrue(sparklineGapFractions(history.latencySparkline()).isEmpty())
    }

    @Test
    fun `a single interior failure shades the span between its two flanking successes`() {
        val history = ProbeHistory(windowMs = 2_000L)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)
            .recordSuccess(2_000, latencyMs = 20)

        val spans = sparklineGapFractions(history.latencySparkline())

        assertEquals(1, spans.size)
        assertEquals(0f, spans[0].start, 0.001f)
        assertEquals(1f, spans[0].endInclusive, 0.001f)
    }

    @Test
    fun `a run of several consecutive failures shades as one span, not one per failure`() {
        val history = ProbeHistory(windowMs = 4_000L)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)
            .recordFailure(2_000)
            .recordFailure(3_000)
            .recordSuccess(4_000, latencyMs = 20)

        val spans = sparklineGapFractions(history.latencySparkline())

        assertEquals(1, spans.size)
        assertEquals(0f, spans[0].start, 0.001f)
        assertEquals(1f, spans[0].endInclusive, 0.001f)
    }

    @Test
    fun `two separate failure runs shade as two separate spans`() {
        val history = ProbeHistory(windowMs = 5_000L)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)
            .recordSuccess(2_000, latencyMs = 20)
            .recordFailure(3_000)
            .recordSuccess(5_000, latencyMs = 30)

        val spans = sparklineGapFractions(history.latencySparkline())

        assertEquals(2, spans.size)
    }

    @Test
    fun `a failure run at the very start of the window is not shaded -- indistinguishable from warm-up`() {
        val history = ProbeHistory(windowMs = window)
            .recordFailure(0)
            .recordFailure(500)
            .recordSuccess(1_000, latencyMs = 10)

        assertTrue(sparklineGapFractions(history.latencySparkline()).isEmpty())
    }

    @Test
    fun `a failure run still in progress at the newest point shades through to the right edge`() {
        val history = ProbeHistory(windowMs = 2_000L)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)
            .recordFailure(2_000)

        val spans = sparklineGapFractions(history.latencySparkline())

        assertEquals(1, spans.size)
        assertEquals(0f, spans[0].start, 0.001f)
        // The trailing failure is the newest retained point, always the axis's right edge.
        assertEquals(1f, spans[0].endInclusive, 0.001f)
    }

    @Test
    fun `an all-failure history has no gap spans -- no boundary anywhere to shade from`() {
        var history = ProbeHistory(windowMs = window)
        repeat(5) { index -> history = history.recordFailure(index * 1_000L) }

        assertTrue(sparklineGapFractions(history.latencySparkline()).isEmpty())
    }

    @Test
    fun `an empty points list has no gap spans`() {
        assertTrue(sparklineGapFractions(emptyList()).isEmpty())
    }

    @Test
    fun `the success sparkline never produces gap spans -- an empty bucket is a miss, not shaded no-data`() {
        // A history sparse enough, relative to its window, that some buckets between real
        // attempts get none at all. This used to be a shaded "no data" gap, the same as the
        // latency line's; per the current design, it is a 0% miss instead, with no gap span
        // to shade at all -- sparklineGapFractions only ever finds something to shade here if
        // successSparkline emitted a null y, which it no longer does.
        val history = steadyStateHistory(windowMs = 100_000L, bucketWidthMs = 10_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(100_000, latencyMs = 20)

        val points = history.successSparkline(bucketWidthMs = 10_000) // ceil(100_000 / 10).

        assertTrue(sparklineGapFractions(points).isEmpty())
        assertTrue(points.any { it.y == 0f })
    }

    // --- Success sparkline: fixed bucket width, window-varying bucket count (Problem 1) -----
    //
    // Direct regression tests for: the bucket grid's width used to be `windowMs / maxBuckets`,
    // so every edit to the "History window" slider changed the width of every bucket, which
    // reassigns *every* retained sample to a different bucket -- a full rebin, not a rescale,
    // undoing the fixed-slot design's own value-stability guarantee. successSparkline now takes
    // an (optional, test-only) [bucketWidthMs] instead of a bucket count, defaulting to the true
    // constant [ProbeHistory.BUCKET_WIDTH_MS]; bucket *count* is whatever fits the configured
    // window at that fixed width. Most tests below use an explicit override purely to get round,
    // easy-to-reason-about numbers -- production (`:app`) always uses the default.
    //
    // Several of these tests build their history through [steadyStateHistory], which prepends a
    // throwaway sample far enough in the past that it can never appear in the displayed window
    // *and* pushes the session's warm-up anchor (see the "Session warm-up" group further down)
    // safely behind every real sample the test records at or after `t = 0`. That isolates these
    // steady-state bucketing assertions from warm-up, which has its own dedicated tests and would
    // otherwise subdivide the first bucketWidthMs of *every* fresh history in this file.

    /**
     * Builds a history whose warm-up era (see [ProbeHistory.successSparkline]'s "Session
     * warm-up" doc) is already over, and whose primer sample is already outside the displayed
     * window, before any of a test's own `t >= 0` samples are recorded -- see this group's own
     * header comment for why.
     */
    private fun steadyStateHistory(windowMs: Long, bucketWidthMs: Long): ProbeHistory =
        ProbeHistory(windowMs = windowMs).recordFailure(-(windowMs + bucketWidthMs + 1))

    @Test
    fun `the success line plots a rate per time bucket, not a zero-or-one square wave`() {
        // 8 attempts over a fully-packed 7-second window, 6 of them successful. Buckets are
        // fixed 3.5-second slots of absolute time (not an even relative split of the retained
        // span), so the two buckets end up with 3 and 4 real attempts respectively rather than
        // 4 and 4 -- the t=0 attempt lands exactly on the window's own left edge, a boundary a
        // fixed slot grid can occasionally exclude (see successBucketSlot's doc) -- but the core
        // point stands either way: a fractional rate per bucket, not a 0/1 square wave.
        var history = steadyStateHistory(windowMs = 7_000, bucketWidthMs = 3_500)
        listOf(true, true, true, false, true, true, true, false).forEachIndexed { index, ok ->
            val at = index * 1_000L
            history = if (ok) history.recordSuccess(at, latencyMs = 10) else history.recordFailure(at)
        }

        val points = history.successSparkline(bucketWidthMs = 3_500)

        assertEquals(2, points.size)
        assertEquals(2f / 3f, points[0].y!!, 0.001f) // t=1000,2000,3000 -> 2 successes / 3 attempts.
        assertEquals(0.75f, points[1].y!!, 0.001f) // t=4000,5000,6000,7000 -> 3 successes / 4 attempts.
    }

    @Test
    fun `success buckets use an absolute zero-to-one scale, not an autoscaled one`() {
        var history = steadyStateHistory(windowMs = 7_000, bucketWidthMs = 3_500)
        repeat(8) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        // All-success buckets sit at the top of the scale, and would still sit at the top if
        // the rate were, say, uniformly 50% -- an autoscaled percentage graph would be
        // unreadable, since "always 50%" and "always 100%" would look identical. bucketWidthMs
        // is set so the window holds exactly 2 buckets, each with real attempts behind it -- at
        // the default resolution the window is small enough that most buckets would be empty
        // gaps, which is a different (already covered) rule, not what this test is about.
        assertTrue(history.successSparkline(bucketWidthMs = 3_500).all { it.y == 1f })
    }

    @Test
    fun `bucket count grows with the configured window instead of staying pinned`() {
        // With bucket width now a true constant, more of the configured window means more
        // displayed buckets -- the opposite of the old design, where count was pinned at
        // DEFAULT_MAX_BUCKETS and width varied instead. windowMs is set well past this
        // history's own warm-up era (a 2000-second span against a 1-second bucket width), so
        // this is purely ceil(windowMs / bucketWidthMs) steady-state arithmetic: exactly
        // windowMs / 1000 buckets for each of these three window sizes.
        val bucketWidthMs = 1_000L
        var history = steadyStateHistory(windowMs = 30 * 60_000L, bucketWidthMs = bucketWidthMs)
        repeat(2_000) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        assertEquals(60, history.withWindowMs(60_000L).successSparkline(bucketWidthMs = bucketWidthMs).size)
        assertEquals(420, history.withWindowMs(7 * 60_000L).successSparkline(bucketWidthMs = bucketWidthMs).size)
        assertEquals(1_800, history.withWindowMs(30 * 60_000L).successSparkline(bucketWidthMs = bucketWidthMs).size)
    }

    /**
     * The specific property this task adds beyond the pre-existing fixed-slot design: a sample's
     * bucket must be stable *across a window-length change*, not just across ticks of the clock.
     * Before this fix, bucket width was `windowMs / bucketCount`, so calling [withWindowMs] alone
     * -- no new samples, no time passing -- changed every bucket's width and silently rebinned
     * every retained sample. With width now the true constant [ProbeHistory.BUCKET_WIDTH_MS],
     * the narrower window's buckets must be exactly the trailing slice of the wider window's own
     * buckets: same real-world slot, same real samples behind each one, same value -- proof that
     * narrowing/widening only changes how many already-assigned buckets are in view.
     */
    @Test
    fun `a sample's bucket grouping is stable across a window-length change -- narrowing never rebins`() {
        val bucketWidthMs = 10_000L
        var history = steadyStateHistory(windowMs = 7 * 60_000L, bucketWidthMs = bucketWidthMs)
        // 20 minutes of steady, 1-second-paced samples with a deterministic pass/fail pattern --
        // no real outage, so any difference in a bucket's value below can only be a rebinning
        // artifact, never a genuine change in the underlying data.
        repeat(1_200) { i ->
            history = if (i % 7 != 0) {
                history.recordSuccess(i * 1_000L, latencyMs = 10)
            } else {
                history.recordFailure(i * 1_000L)
            }
        }

        val wide = history.withWindowMs(7 * 60_000L).successSparkline(bucketWidthMs)
        val narrow = history.withWindowMs(3 * 60_000L).successSparkline(bucketWidthMs)

        assertTrue("narrowing must never show more buckets than the wider window did", narrow.size <= wide.size)
        // Both windows share the same newest sample, so the narrow window's buckets are exactly
        // the wide window's own trailing buckets -- not a freshly rebinned grid of a different
        // width.
        assertEquals(wide.takeLast(narrow.size).map { it.y }, narrow.map { it.y })

        // Widening back afterward must reveal exactly the same values as before narrowing, per
        // sample -- not a second, independently rebinned grid.
        val widenedAgain = history.withWindowMs(3 * 60_000L).withWindowMs(7 * 60_000L).successSparkline(bucketWidthMs)
        assertEquals(wide.map { it.y }, widenedAgain.map { it.y })
    }

    @Test
    fun `an additional attempt within an already-covered span does not change the visible resolution`() {
        var history = steadyStateHistory(windowMs = 60_000, bucketWidthMs = 6_000)
        repeat(30) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }
        val before = history.successSparkline(bucketWidthMs = 6_000).size

        // A burst of extra attempts landing inside the same already-covered span, not extending
        // it -- must not reshuffle the resolution that was already settled on.
        repeat(500) { index -> history = history.recordSuccess(29_000L + index, latencyMs = 10) }
        val after = history.successSparkline(bucketWidthMs = 6_000).size

        assertEquals(before, after)
    }

    /**
     * Regression test for the on-device report that the ping-success graph stayed a single dot
     * for several seconds, then had its left edge "bounce" and already-plotted dips readjust
     * with every new sample. Root cause at the time: bucket count used to be `samples.size / 4`,
     * so a burst of attempts arriving without much real time passing (pacing changes, retry
     * bursts, or simply more probes landing) changed the bucket count -- and therefore every
     * bucket's boundaries -- on almost every recorded sample. Bucket count no longer depends on
     * attempt count at all any more (it depends only on the configured window at a fixed width --
     * see [ProbeHistory.successSparkline]'s own doc): this checks that property directly, with
     * default `bucketWidthMs` so both histories also exercise the very-first-sample warm-up
     * ladder identically (same first/newest timestamps in both, so the same slot arithmetic
     * either way -- see [ProbeHistory.bucketSlot]'s doc for why that only depends on the
     * timestamps involved, never on how many samples sit between them).
     */
    @Test
    fun `visible resolution depends on elapsed time, not on how many attempts arrived in it`() {
        val sparse = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(29_850, latencyMs = 10)

        var burst = ProbeHistory(windowMs = 60_000)
        // The same ~30-second span as `sparse`, but a burst of 200 attempts packed into it --
        // exactly the shape of a rapid failure-retry burst landing mid-window.
        repeat(200) { index -> burst = burst.recordSuccess(index * 150L, latencyMs = 10) }

        assertEquals(sparse.successSparkline().size, burst.successSparkline().size)
    }

    /**
     * Regression test for the on-device screenshot report: the ping-success graph was showing a
     * gray shaded "no data" rectangle sitting in the middle of the line, between two regions of
     * real data. Per the user's explicit instruction, a stretch with no real attempts must not
     * be shaded or broken -- it must render exactly like a failed probe would: a 0% dip,
     * connected normally to its neighbors.
     */
    @Test
    fun `a time bucket with no real attempts in it is a miss (y = 0), not a shaded gap`() {
        // A long silence in the middle (nothing probed between t=1s and t=59s), long enough
        // relative to the bucket width to leave at least one bucket entirely empty. windowMs
        // set to exactly the data's own span (a fully-packed window) so the two clusters land
        // cleanly in separate buckets. bucketWidthMs mirrors the old ceil(60_750 / 8) width.
        var history = steadyStateHistory(windowMs = 60_750, bucketWidthMs = 7_594)
        repeat(4) { index -> history = history.recordSuccess(index * 250L, latencyMs = 10) }
        repeat(4) { index -> history = history.recordFailure(60_000L + index * 250L) }

        val points = history.successSparkline(bucketWidthMs = 7_594)

        // The window is exactly fully covered (span == windowMs), so resolution is maxed out
        // at all 8 buckets that fit -- the two four-sample clusters land in the first and last
        // of them, with every bucket in between having zero real attempts.
        assertEquals(8, points.size)
        assertEquals(1f, points.first().y!!, 0.001f)
        assertEquals(0f, points.last().y!!, 0.001f)
        // Every bucket between the clusters has no real attempts -- each collapses straight
        // into a 0% miss (not null, not omitted), the same as if every attempt in it had
        // failed, and connects normally to its neighbors rather than breaking the line.
        points.subList(1, points.size - 1).forEach { assertEquals(0f, it.y!!, 0.001f) }
        assertTrue(sparklineGapFractions(points).isEmpty())

        // With more buckets than the two clusters can fill, the middle really is empty --
        // still all misses, never a null gap. bucketWidthMs mirrors the old ceil(61_900 / 10).
        var sparse = steadyStateHistory(windowMs = 61_900, bucketWidthMs = 6_190)
        repeat(20) { index -> sparse = sparse.recordSuccess(index * 100L, latencyMs = 10) }
        repeat(20) { index -> sparse = sparse.recordFailure(60_000L + index * 100L) }
        val sparsePoints = sparse.successSparkline(bucketWidthMs = 6_190)
        assertTrue(sparsePoints.none { it.y == null })
        assertTrue(sparsePoints.any { it.y == 0f })
    }

    // --- Bucket-value stability across ticks (the "graph reshapes instead of scrolling" bug) ---
    //
    // Direct regression test for the on-device report: "the graph changes shape every tick
    // rather than just scrolling, exacerbated by changing the ping rate while watching." Root
    // cause: successSparkline used to bin each sample by its *fractional distance from `newest`*
    // ([ProbeHistory.windowFraction]) -- a value every retained sample's position is measured
    // against, and which shifts on every single new sample. A sample sitting near a bucket
    // boundary could therefore flip to the neighboring bucket on almost any tick purely because
    // "now" moved forward slightly, with nothing about the connection actually changing --
    // confirmed by porting that exact old algorithm into a standalone simulation and measuring
    // single-tick swings of tens of percentage points in a bucket with nothing real going on, up
    // to 100 points at narrow-window/slow-pacing settings (exactly what "changing the ping rate
    // while watching" would trigger). The fix bins each sample into a fixed, absolute-time slot
    // instead (see [ProbeHistory.successSparkline]'s doc) -- a slot's membership is a pure
    // function of which real samples exist, not of when the line happens to be redrawn.

    /**
     * The test that should have caught the original bug and didn't. Setup: one real sample per
     * second in a window whose default bucket width is also exactly one second (`windowMs` =
     * `maxBuckets` * 1000ms), so every fixed slot ends up with exactly one real attempt -- no
     * averaging needed to reason about expected values -- following a known, deterministic
     * pass/fail pattern (fails every 5th attempt). Once the window is fully packed, a specific
     * real sample's point is identified purely by its distance from the *right* edge (the newest
     * point): since exactly one new sample lands and exactly one new fixed slot opens on every
     * tick, that distance increases by exactly one index per tick as long as nothing is
     * migrating between buckets. Under steady, unchanging conditions (no real outage, no setting
     * change), that tracked point must report the pattern's own known outcome on every single
     * later tick -- and therefore must never change from one tick to the next, checked directly
     * too -- for as long as it is neither the live point nor within one slot-width of aging off
     * the window's trailing edge.
     */
    @Test
    fun `an interior bucket's value never changes on a later tick under steady, unchanging conditions`() {
        val stableWindowMs = 48_000L // 48 buckets -> exactly 1_000ms fixed slots.
        val bucketWidthMs = 1_000L
        val bucketCount = 48
        fun succeededAt(i: Int) = i % 5 != 0 // deterministic, steady failure rate -- no real outage.

        // bucketCount + 1 samples, one full extra tick past a bare minimum warm-up, so the
        // retained span is exactly windowMs (not one pacing interval short of it) regardless of
        // how a given implementation decides "how full is full." No steadyStateHistory primer
        // needed here: the first sample (i=1, t=1000) is already exactly one bucketWidthMs past
        // its own warm-up anchor by the time the tracked/stability range below is exercised (see
        // this test's own values), so the session's brief warm-up era never overlaps it.
        var history = ProbeHistory(windowMs = stableWindowMs)
        for (i in 1..(bucketCount + 1)) {
            val t = i * 1_000L
            history = if (succeededAt(i)) history.recordSuccess(t, latencyMs = 10) else history.recordFailure(t)
        }
        // The window is now fully packed: one real sample per fixed slot, bucketCount points.
        assertEquals(bucketCount, history.successSparkline(bucketWidthMs).size)

        // Track the sample recorded at i = trackedI. At k ticks later it must sit exactly k
        // positions in from the right edge, still carrying its own known outcome.
        val trackedI = 20
        val trackedExpected = if (succeededAt(trackedI)) 1f else 0f
        var previousValue: Float? = null

        for (i in (bucketCount + 2)..(bucketCount + 31)) {
            val t = i * 1_000L
            history = if (succeededAt(i)) history.recordSuccess(t, latencyMs = 10) else history.recordFailure(t)

            val k = i - trackedI // ticks since the tracked sample was recorded
            // Not live (k > 0) and not within one slot-width of the trailing edge (k <= bucketCount - 2).
            if (k <= 0 || k > bucketCount - 2) continue

            val points = history.successSparkline(bucketWidthMs)
            val index = points.size - 1 - k
            if (index !in points.indices) continue

            val value = points[index].y!!
            assertEquals(
                "tracked sample (i=$trackedI) must still report its own known outcome at tick i=$i",
                trackedExpected,
                value,
                0.001f,
            )
            previousValue?.let { previous ->
                assertEquals(
                    "an interior, already-closed bucket's value must not change from one tick to the next",
                    previous,
                    value,
                    0.001f,
                )
            }
            previousValue = value
        }

        // The stability check above must have actually exercised multiple ticks, not vacuously
        // passed because the loop's range never satisfied its own guards.
        assertNotNull(previousValue)
    }

    // --- Session warm-up: resolution starts fine and coarsens into the fixed grid (Problem 2) ---
    //
    // Direct regression tests for: a fresh session's very first fixed-width bucket can span the
    // *entire* bucketWidthMs (several seconds at the default width) before a second ordinary
    // bucket ever opens, which without more shows one flatlined point pinned at the right edge
    // for that whole stretch. [ProbeHistory.bucketSlot] now subdivides just that first bucket
    // into progressively wider sub-buckets (see its own doc, and [ProbeHistory.warmupLevel]'s),
    // anchored to the very first real sample ever recorded. The hard constraint from the task:
    // whatever bucket an early sample lands in during this stretch is a *permanent* fact about
    // it, exactly like every other bucket assignment in this design -- never recomputed
    // differently as more samples (warm-up or steady-state) arrive later.

    @Test
    fun `point count increases as real samples arrive during warm-up, before a full bucket-width has passed`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS // production default, ~8_750ms.
        var history = ProbeHistory(windowMs = 60_000)
        var previousSize = 0
        var sawIncrease = false
        // These six elapsed times land in six *different* warm-up levels (unit ~= 138ms at this
        // bucket width, levels doubling from there -- see warmupLevel's own doc): under the
        // design this replaces, every one of these but the very first (t=0) would instead
        // collapse into the same single, un-subdivided ~8.75s bucket, since none of them reach
        // bucketWidthMs. windowMs (60s) stays far wider than the elapsed real time (5s)
        // throughout, so the display's left edge stays pinned at the session's own start the
        // whole time -- what makes the point count provably non-decreasing here.
        listOf(0L, 200L, 500L, 1_200L, 2_500L, 5_000L).forEach { t ->
            history = history.recordSuccess(t, latencyMs = 10)
            val size = history.successSparkline(bucketWidthMs).size
            assertTrue(
                "point count must never shrink as more real samples arrive: was $previousSize, now $size at t=$t",
                size >= previousSize,
            )
            if (size > previousSize) sawIncrease = true
            previousSize = size
        }
        assertTrue("expected the point count to grow more than once during warm-up, not jump straight to a flat count", sawIncrease)
        assertEquals(
            "each of these six samples was deliberately chosen to land in its own distinct warm-up level",
            6,
            previousSize,
        )
    }

    /**
     * The permanence guarantee the task requires explicitly, mirroring the pre-existing
     * "an interior bucket's value never changes on a later tick" test one level down. `t = 500`
     * lands in its own warm-up level (see the previous test's reasoning for these exact
     * boundaries), with a known outcome (failure) nothing else ever shares a bucket with -- every
     * later insertion below deliberately lands in a *different* level or, eventually, well past
     * warm-up into an ordinary steady-state bucket, and that tracked bucket's value must not move
     * even once. If bucket membership here were ever recomputed from "how many samples currently
     * exist" instead of each sample's own fixed timestamp, this is exactly the check that would
     * catch it.
     */
    @Test
    fun `a value assigned during warm-up never changes later, through the rest of warm-up or into steady state`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        var history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10) // level 0
            .recordSuccess(200, latencyMs = 10) // level 1
            .recordFailure(500) // level 2 -- the tracked bucket, a known failure.
        val afterSeed = history.successSparkline(bucketWidthMs)
        // Confirms the setup really did land these three in three separate, consecutive buckets
        // (not the ordinary un-subdivided grid's own single bucket) before tracking begins.
        assertEquals(3, afterSeed.size)
        val trackedValue = afterSeed[2].y
        assertEquals(0f, trackedValue)

        // t=1_200..5_000 are later warm-up levels (3, 4, 5); t=8_749 still warm-up but folds into
        // level 5's own wide catch-all bucket; t=10_000 onward are ordinary, post-warm-up,
        // steady-state buckets. None of them ever falls back into level 2's own narrow span.
        val laterTimestamps = listOf(1_200L, 2_500L, 5_000L, 8_749L, 10_000L, 30_000L, 55_000L)
        laterTimestamps.forEach { t ->
            history = history.recordSuccess(t, latencyMs = 10)
            val points = history.successSparkline(bucketWidthMs)
            assertEquals(
                "the warm-up-assigned t=500 bucket's value changed after recording t=$t -- a permanence violation",
                trackedValue,
                points[2].y,
            )
        }
    }

    // --- No-data-as-miss: the "gap" concept is gone from this graph entirely -----------------
    //
    // An earlier version of successSparkline tried to tell a "genuine gap" (a real stretch of
    // elapsed time with no real attempts) apart from a "quantization artifact" (the bucket grid
    // simply finer than real sample density supports) and shaded only the former, using an
    // adaptive real-timestamp-based threshold (see git history for that design). Per explicit
    // user instruction that design is gone: the settings screen showed a shaded "no data"
    // rectangle sitting in the middle of the line between two regions of real data, and the user
    // wants zero visual distinction between "no data" and "failed," full stop. A bucket with no
    // real attempts is now unconditionally a 0% miss, connected normally to its neighbors, with
    // the single exception of the leading run before the very first real sample ever recorded in
    // the displayed window (see successSparkline's own doc for why that one case stays blank).

    /**
     * A genuine sustained outage -- long enough to leave several buckets with no real attempts
     * at all -- collapses into a run of 0% dips connected straight through, not a shaded or
     * broken gap. This is the direct fix for the on-device screenshot report.
     */
    @Test
    fun `a sustained outage renders as a run of 0 percent dips, never shaded and never a break`() {
        var history = steadyStateHistory(windowMs = 120_000L, bucketWidthMs = 2_500)
        // Steady real sampling for the first 20 seconds...
        repeat(40) { index -> history = history.recordSuccess(index * 500L, latencyMs = 10) }
        // ...then total silence for 80 seconds (a real, sustained outage)...
        // ...then steady real sampling resumes for the last 20 seconds, up to the window edge.
        repeat(40) { index -> history = history.recordSuccess(100_000L + index * 500L, latencyMs = 10) }

        val points = history.successSparkline(bucketWidthMs = 2_500) // ceil(120_000 / 48).

        assertTrue(points.none { it.y == null })
        assertTrue(sparklineGapFractions(points).isEmpty())
        // The outage sits squarely in the middle of the axis -- every point there is a miss.
        val duringOutage = points.filter { it.x in 0.3f..0.7f }
        assertTrue(duringOutage.isNotEmpty())
        assertTrue(duringOutage.all { it.y == 0f })
    }

    /**
     * The one preserved exception: buckets before the very first real sample recorded anywhere
     * in the displayed window stay blank (omitted), not a 0% miss -- ordinary session warm-up
     * (e.g. right after a fresh install) must not read as "actively failing." Constructed so
     * real attempts fill only the newer half of the window, leaving the older half with no real
     * sample in it at all.
     */
    @Test
    fun `buckets before the very first real sample stay blank, not a 0 percent miss`() {
        // steadyStateHistory here isolates this test's subject -- the leading-blank rule *within
        // an already-displayed window* -- from session warm-up (a separate, dedicated test group
        // below): without it, the very first of these 200ms-paced real samples would itself
        // become the session's own warm-up anchor and get subdivided, which is not what this
        // test is about. 48 fixed 1_250ms slots (60_000 / 48).
        var history = steadyStateHistory(windowMs = 60_000, bucketWidthMs = 1_250)
        var t = 30_000L
        while (t <= 60_000L) {
            history = history.recordSuccess(t, latencyMs = 10)
            t += 200L
        }

        val points = history.successSparkline(bucketWidthMs = 1_250)

        // Real attempts start at t=30_000, the window's own midpoint -- the first ~23 of the 48
        // fixed slots (everything before that point) have no real sample in them at all and are
        // omitted entirely rather than plotted as a 0f miss, leaving 25 real points.
        assertEquals(25, points.size)
        assertTrue(points.none { it.x < 0.48f })
        assertTrue(points.none { it.y == 0f })
        assertTrue(points.all { it.y == 1f })
    }

    @Test
    fun `success buckets span the full axis from oldest to newest once the window is genuinely full`() {
        var history = steadyStateHistory(windowMs = 39_000, bucketWidthMs = 7_800) // ceil(39_000 / 5).
        repeat(40) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        val points = history.successSparkline(bucketWidthMs = 7_800)

        assertEquals(0f, points.first().x, 0.001f)
        assertEquals(1f, points.last().x, 0.001f)
    }

    // --- Window-anchored axis: recent data clusters at the right, not stretched to fill -----

    @Test
    fun `a short session's latency points cluster near the right edge instead of stretching to fill the width`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(1_000, latencyMs = 20)

        val points = history.latencySparkline()

        // Only 1 of the configured 60 seconds has actually elapsed -- the points sit close
        // together near x = 1, with real empty space to their left, rather than being
        // stretched across the whole width the way scaling to the retained span used to draw
        // them (which looked identical to a graph that had just been reset, every time it was
        // sparse -- see this class's own "time axis" doc).
        assertTrue(points[0].x > 0.9f)
        assertEquals(1f, points[1].x, 0.001f)
    }

    /**
     * A short real session (700ms of data in a 60-second window) is exactly the case session
     * warm-up (see the dedicated test group below) now gives *more* honest detail to, not less:
     * with a 10-second bucketWidthMs, the old design would have shown a single flatlined point
     * for this entire stretch (everything here sits inside one ordinary fixed slot); the warm-up
     * ladder instead subdivides that first slot by real elapsed time since the very first sample,
     * so these 8 samples (100ms apart, spanning warm-up levels 0-2) land in 3 distinct buckets
     * instead of 1 -- still far fewer than the 6 the configured window could hold once genuinely
     * full, not six buckets where most would just be gaps for time that hasn't happened yet.
     */
    @Test
    fun `a short session shows genuinely increasing detail instead of one flatlined point`() {
        var history = ProbeHistory(windowMs = 60_000) // bucketWidthMs=10_000 -> 6 fixed slots.
        repeat(8) { index -> history = history.recordSuccess(index * 100L, latencyMs = 10) }

        val points = history.successSparkline(bucketWidthMs = 10_000)

        assertEquals(3, points.size)
        assertTrue(points.all { it.y == 1f })
    }

    @Test
    fun `a real silence after an early sample shows as a 0 percent miss once enough time has passed`() {
        // Unlike the short-session case above, there is a genuine elapsed silence here: one
        // sample right at session start, then nothing until a cluster arrives much later, still
        // within the window. Enough real time has passed for multiple buckets, and the empty
        // stretch between the two is a real absence of measurement -- rendered as a miss, not
        // fabricated resolution and not a shaded gap.
        var history = ProbeHistory(windowMs = 60_000).recordSuccess(0, latencyMs = 10)
        repeat(8) { index -> history = history.recordSuccess(40_000L + index * 100L, latencyMs = 10) }

        val points = history.successSparkline(bucketWidthMs = 10_000)

        assertTrue(points.size > 1)
        assertTrue(points.none { it.y == null })
        assertTrue(points.any { it.y == 0f })
        assertTrue(points.filter { it.y != 0f }.all { it.y == 1f })
        assertTrue(sparklineGapFractions(points).isEmpty())
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
    fun `markers stay in storage past the window -- only the displayed fractions exclude them`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordMarker(0)
            .recordSuccess(20_000, latencyMs = 10)

        assertEquals(listOf(0L), history.markers)
        assertTrue(history.markerFractions().isEmpty())
    }

    @Test
    fun `narrowing the window excludes a marker from the displayed fractions, not from storage`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(1_000)
            .recordSuccess(50_000, latencyMs = 20)

        val narrowed = history.withWindowMs(10_000)

        assertEquals(listOf(1_000L), narrowed.markers)
        assertTrue(narrowed.markerFractions().isEmpty())
    }

    @Test
    fun `clearing drops markers along with samples`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(500)

        assertTrue(history.cleared().markers.isEmpty())
    }

    @Test
    fun `marker fractions are positioned across the same window-anchored axis the sparklines use`() {
        // windowMs set to exactly the samples' own span (a fully-packed window), so this is
        // the case where the window-anchored axis and "spans the retained data" coincide.
        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(2_500)
            .recordSuccess(10_000, latencyMs = 20)

        assertEquals(listOf(0.25f), history.markerFractions())
    }

    @Test
    fun `no samples at all means no axis to place a marker on`() {
        assertTrue(ProbeHistory(windowMs = window).recordMarker(0).markerFractions().isEmpty())
    }

    @Test
    fun `a single sample is enough to place a marker on the window-anchored axis`() {
        val history = ProbeHistory(windowMs = window).recordSuccess(0, latencyMs = 10).recordMarker(0)

        assertEquals(listOf(1f), history.markerFractions())
    }

    @Test
    fun `a marker positioned outside the window is excluded from the fractions`() {
        // Since markers are no longer pruned by the window in storage (see the "narrowing"
        // tests above), this is now the primary mechanism keeping an out-of-window marker off
        // the axis, not just a defensive guard for an unreachable case. Constructed directly
        // here (bypassing recordMarker) purely for a simple, explicit setup.
        val history = ProbeHistory(
            windowMs = 1_000,
            samples = listOf(ProbeSample(timestampMs = 0, latencyMs = 10)),
            markers = listOf(-2_000L),
        )

        assertTrue(history.markerFractions().isEmpty())
    }

    @Test
    fun `a marker from early in a short session sits close to the right edge, not spread across the width`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordMarker(500)
            .recordSuccess(1_000, latencyMs = 20)

        assertTrue(history.markerFractions().single() > 0.9f)
    }

    // --- Absolute latency color scale (latencyColorFraction) ------------------------------

    @Test
    fun `at or below the green anchor is fully green`() {
        assertEquals(0f, latencyColorFraction(0), 0.001f)
        assertEquals(0f, latencyColorFraction(30), 0.001f)
        assertEquals(0f, latencyColorFraction(50), 0.001f)
    }

    @Test
    fun `at the yellow anchor is exactly halfway`() {
        assertEquals(0.5f, latencyColorFraction(200), 0.001f)
    }

    @Test
    fun `at or above the red anchor is fully red`() {
        assertEquals(1f, latencyColorFraction(400), 0.001f)
        assertEquals(1f, latencyColorFraction(1_000), 0.001f)
        assertEquals(1f, latencyColorFraction(50_000), 0.001f)
    }

    @Test
    fun `interpolates linearly between the green and yellow anchors`() {
        // Halfway between 50 and 200 (125ms) is halfway between 0 and 0.5.
        assertEquals(0.25f, latencyColorFraction(125), 0.001f)
    }

    @Test
    fun `interpolates linearly between the yellow and red anchors`() {
        // Halfway between 200 and 400 (300ms) is halfway between 0.5 and 1.
        assertEquals(0.75f, latencyColorFraction(300), 0.001f)
        // Three-quarters of the way from 200 to 400 (350ms) is three-quarters of the way
        // from 0.5 to 1.
        assertEquals(0.875f, latencyColorFraction(350), 0.001f)
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
