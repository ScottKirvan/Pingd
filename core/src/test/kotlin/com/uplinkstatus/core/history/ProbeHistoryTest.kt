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
            .recordSuccess(2_000, latencyMs = 20)

        val points = history.latencySparkline()

        // Three points for three attempts: the failure keeps its place on the timeline (so
        // the line breaks exactly where the outage was) and carries no value.
        assertEquals(3, points.size)
        assertNotNull(points[0].y)
        assertNull(points[1].y)
        assertNotNull(points[2].y)
        // ...and it is genuinely absent rather than plotted at the bottom of the scale.
        // Fast plots high, slow plots low: the 10ms sample (fastest) sits at the top of the
        // scale, the 20ms sample (slowest) at the bottom -- "up" means "better."
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
            .recordSuccess(0, latencyMs = 10) // fastest
            .recordSuccess(1_000, latencyMs = 55) // middle
            .recordSuccess(2_000, latencyMs = 100) // slowest

        val points = history.latencySparkline()

        assertEquals(1f, points[0].y!!, 0.001f) // fastest -> top
        assertEquals(0.5f, points[1].y!!, 0.001f) // exactly midway -> middle
        assertEquals(0f, points[2].y!!, 0.001f) // slowest -> bottom
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
    fun `gap shading also applies to the bucketed success sparkline, not just the per-sample latency one`() {
        // Real attempts clustered at both ends of the window with a genuine silent stretch in
        // between -- enough real samples flanking it that the empty middle buckets reflect an
        // actual absence of attempts, not the bucket resolution simply outrunning a sparse
        // sample count (see bucketCount's own doc for why that distinction now matters).
        var history = ProbeHistory(windowMs = 100_000L)
        repeat(5) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }
        repeat(5) { index -> history = history.recordSuccess(95_000L + index * 1_000L, latencyMs = 20) }

        val spans = sparklineGapFractions(history.successSparkline(maxBuckets = 10))

        assertTrue(spans.isNotEmpty())
    }

    // --- Success sparkline: bucketing -----------------------------------------------------

    @Test
    fun `the success line plots a rate per time bucket, not a zero-or-one square wave`() {
        // 8 attempts over a fully-packed 7-second window, 6 of them successful, split evenly
        // across two buckets: bucket 1 has 3/4, bucket 2 has 3/4.
        var history = ProbeHistory(windowMs = 7_000)
        listOf(true, true, true, false, true, true, true, false).forEachIndexed { index, ok ->
            val at = index * 1_000L
            history = if (ok) history.recordSuccess(at, latencyMs = 10) else history.recordFailure(at)
        }

        val points = history.successSparkline(maxBuckets = 2)

        assertEquals(2, points.size)
        assertEquals(0.75f, points[0].y!!, 0.001f)
        assertEquals(0.75f, points[1].y!!, 0.001f)
    }

    @Test
    fun `success buckets use an absolute zero-to-one scale, not an autoscaled one`() {
        var history = ProbeHistory(windowMs = 7_000)
        repeat(8) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        // All-success buckets sit at the top of the scale, and would still sit at the top if
        // the rate were, say, uniformly 50% -- an autoscaled percentage graph would be
        // unreadable, since "always 50%" and "always 100%" would look identical. maxBuckets is
        // capped at 2 so every bucket actually has attempts behind it -- at the default
        // resolution the window is small enough that most of 48 buckets would be empty gaps,
        // which is a different (already covered) rule, not what this test is about.
        assertTrue(history.successSparkline(maxBuckets = 2).all { it.y == 1f })
    }

    @Test
    fun `resolution grows with elapsed time instead of dotting out one sample per bucket`() {
        var history = ProbeHistory(windowMs = window)
        repeat(3) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }
        // Barely any of the window has elapsed yet: a single honest point, not three.
        assertEquals(1, history.successSparkline().size)

        // Advance to the window's own span -- fully covered now, so resolution is maxed out.
        // Enough further samples spread across the rest of the window that resolution isn't
        // *also* limited by the sample-density cap (covered separately below) -- this test is
        // specifically about the elapsed-time half of bucketCount.
        listOf(10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 55_000L, 58_000L, window).forEach { at ->
            history = history.recordSuccess(at, latencyMs = 10)
        }
        val points = history.successSparkline(maxBuckets = 10)
        assertEquals(10, points.size)
    }

    /**
     * Regression test for the on-device report that the ping-success graph stayed a single dot
     * for several seconds, then had its left edge "bounce" and already-plotted dips readjust
     * with every new sample. Root cause: bucket count used to be `samples.size / 4`, so a burst
     * of attempts arriving without much real time passing (pacing changes, retry bursts, or
     * simply more probes landing) changed the bucket count -- and therefore every bucket's
     * boundaries -- on almost every recorded sample.
     *
     * [bucketCount] now also caps resolution by real sample count (see its own doc, and the
     * dedicated `steady evenly-spaced real samples...` regression test below for *that* fix) --
     * so bucket count is no longer a pure function of elapsed time alone. What must still hold,
     * and what this test now checks, is the specific thing that used to bounce: once real sample
     * density already *exceeds* what elapsed time alone would resolve to, an additional burst of
     * attempts landing in that same span must not push the resolution any higher still.
     */
    @Test
    fun `a burst of attempts beyond what real density already supports does not raise the bucket count further`() {
        var steady = ProbeHistory(windowMs = 60_000)
        // ~1s pacing -- already enough samples that the elapsed-time term, not sample density,
        // is what determines resolution.
        repeat(30) { index -> steady = steady.recordSuccess(index * 1_000L, latencyMs = 10) }

        var burst = ProbeHistory(windowMs = 60_000)
        // The same ~29-second span as `steady`, but a burst of 200 attempts packed into it --
        // exactly the shape of a rapid failure-retry burst landing mid-window.
        repeat(200) { index -> burst = burst.recordSuccess(index * 145L, latencyMs = 10) }

        assertEquals(steady.successSparkline().size, burst.successSparkline().size)
    }

    @Test
    fun `an additional attempt within an already-covered span does not change the bucket count`() {
        var history = ProbeHistory(windowMs = 60_000)
        repeat(30) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }
        val before = history.successSparkline(maxBuckets = 10).size

        // A burst of extra attempts landing inside the same already-covered span, not extending
        // it -- must not reshuffle the resolution that was already settled on.
        repeat(500) { index -> history = history.recordSuccess(29_000L + index, latencyMs = 10) }
        val after = history.successSparkline(maxBuckets = 10).size

        assertEquals(before, after)
    }

    @Test
    fun `a time bucket with no attempts in it is a gap, same rule as the latency line`() {
        // A long silence in the middle (nothing probed between t=1s and t=59s) must read as
        // "no data here," not as an interpolated rate nobody measured. windowMs set to exactly
        // the data's own span (a fully-packed window) so the two clusters land cleanly in
        // separate buckets.
        var history = ProbeHistory(windowMs = 60_750)
        repeat(4) { index -> history = history.recordSuccess(index * 250L, latencyMs = 10) }
        repeat(4) { index -> history = history.recordFailure(60_000L + index * 250L) }

        val points = history.successSparkline(maxBuckets = 8)

        // The window is exactly fully covered (span == windowMs), so resolution is maxed out
        // at all 8 requested buckets -- the two four-sample clusters land in the first and last
        // of them, with every bucket in between left empty.
        assertEquals(8, points.size)
        assertEquals(1f, points.first().y!!, 0.001f)
        assertEquals(0f, points.last().y!!, 0.001f)

        // With more buckets than the two clusters can fill, the middle really is empty.
        var sparse = ProbeHistory(windowMs = 61_900)
        repeat(20) { index -> sparse = sparse.recordSuccess(index * 100L, latencyMs = 10) }
        repeat(20) { index -> sparse = sparse.recordFailure(60_000L + index * 100L) }
        val sparsePoints = sparse.successSparkline(maxBuckets = 10)
        assertTrue(sparsePoints.any { it.y == null })
    }

    /**
     * Regression test for the on-device "the ping-success graph shows a shaded 'no data' gap
     * that changes size and flickers on a perfectly steady connection" report. Root cause:
     * [ProbeHistory.successSparkline]'s bucket count used to depend only on how much of the
     * *window* elapsed time had covered, with no relationship to how many real samples actually
     * exist to fill that many buckets. Once the window is fully covered, bucket count pinned at
     * `maxBuckets`, giving a fixed per-bucket time-width -- and if the user narrows the window
     * and/or raises the ping-pacing step delay, the real per-probe interval can end up *larger*
     * than that fixed bucket width. Individual buckets then legitimately contain zero real
     * attempts by pigeonhole, not because of an outage but purely because display resolution has
     * outrun the actual sampling rate -- and because bucket boundaries are recomputed fresh on
     * every new sample, which specific buckets come up empty shifts from one sample to the next,
     * which is the flicker.
     *
     * This constructs exactly that shape: a 60-second window with real, perfectly steady 2000ms
     * probes spanning the *entire* window (31 evenly-spaced samples, 0..60000ms) -- a real
     * interval well larger than `60000 / 48 = 1250ms`, the fixed per-bucket width the old,
     * uncapped 48-bucket resolution would have used once the window reads as fully covered. On a
     * genuinely uninterrupted connection like this, no interior bucket may read as empty.
     */
    @Test
    fun `steady evenly-spaced real samples across the full window never produce a spurious empty bucket`() {
        var history = ProbeHistory(windowMs = 60_000)
        // 31 samples, exactly 2000ms apart, 0..60000ms -- spans the configured window exactly,
        // at a real interval far coarser than 60000/48 = 1250ms (the old fixed-resolution
        // bucket width once the window reads as fully covered).
        (0..30).forEach { index -> history = history.recordSuccess(index * 2_000L, latencyMs = 10) }

        val points = history.successSparkline() // default maxBuckets (48)

        assertTrue(
            "expected no interior gap on a steady, uninterrupted connection, but got: " +
                points.map { it.y },
            points.none { it.y == null },
        )
    }

    @Test
    fun `success buckets span the full axis from oldest to newest once the window is genuinely full`() {
        var history = ProbeHistory(windowMs = 39_000)
        repeat(40) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        val points = history.successSparkline(maxBuckets = 5)

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

    @Test
    fun `a short session collapses to one honest point instead of fabricating empty buckets`() {
        var history = ProbeHistory(windowMs = 60_000)
        repeat(8) { index -> history = history.recordSuccess(index * 100L, latencyMs = 10) }

        val points = history.successSparkline(maxBuckets = 6)

        // 700ms of real data inside a 60-second window: barely any of the window has actually
        // elapsed, so resolution stays at a single honest point rather than being split into
        // six buckets where five would just be gaps for time that hasn't happened yet.
        assertEquals(1, points.size)
        assertNotNull(points.single().y)
    }

    @Test
    fun `a real gap after an early sample still shows as an empty bucket once enough time has passed`() {
        // Unlike the short-session case above, there is a genuine elapsed gap here: one sample
        // right at session start, then nothing until a cluster arrives much later, still within
        // the window. Enough real time has passed for multiple buckets, and the empty stretch
        // between the two is a real absence of measurement, not fabricated resolution.
        var history = ProbeHistory(windowMs = 60_000).recordSuccess(0, latencyMs = 10)
        repeat(8) { index -> history = history.recordSuccess(40_000L + index * 100L, latencyMs = 10) }

        val points = history.successSparkline(maxBuckets = 6)

        assertTrue(points.size > 1)
        assertTrue(points.any { it.y == null })
        assertTrue(points.filter { it.y != null }.all { it.y == 1f })
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
