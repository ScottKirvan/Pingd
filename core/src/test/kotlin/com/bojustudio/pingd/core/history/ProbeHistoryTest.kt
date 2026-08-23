package com.bojustudio.pingd.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * is covered by `PingdNotificationControllerTest`; this file covers what happens to samples
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

    // --- isWindowFull: the "genuinely full" signal (regression for the caption bug) -------

    @Test
    fun `an empty history is not a full window`() {
        assertFalse(ProbeHistory(windowMs = window).isWindowFull)
    }

    @Test
    fun `a single sample does not make a full window`() {
        assertFalse(ProbeHistory(windowMs = window).recordSuccess(0, 10).isWindowFull)
    }

    @Test
    fun `a window is not full while every retained sample is still displayed -- nothing has aged out yet`() {
        // Two samples spanning exactly windowMs -- the old, broken `spanMs >= windowMs` check
        // would call this "full" by numeric coincidence, but no real data has actually aged out
        // of the display: every retained sample is still shown. Per the fixed definition, this
        // is *not* genuinely full -- there is nothing beyond it to prove the window has really
        // been full for a while.
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(60_000, latencyMs = 10)

        assertFalse(history.isWindowFull)
        assertEquals(60_000L, history.spanMs) // spanMs == windowMs here, but that's not "full."
    }

    @Test
    fun `a window is genuinely full once real retained data has aged out of what is displayed`() {
        val history = ProbeHistory(windowMs = 10_000)
            .recordSuccess(0, latencyMs = 10) // will age out
            .recordSuccess(5_000, latencyMs = 10)
            .recordSuccess(12_000, latencyMs = 10) // newest; cutoff = 2_000, excludes t=0

        assertTrue(history.isWindowFull)
        // 2 real samples still shown (t=5000, t=12000); the t=0 sample is retained but excluded.
        assertEquals(2, history.attemptCount)
        assertEquals(3, history.samples.size)
    }

    /**
     * Regression test for the exact on-device report: a 1-minute (60_000ms) configured window
     * reported "last 59 seconds" forever, never crediting the full configured duration even once
     * the window was genuinely, thoroughly full of real data. Root cause: `historySpanCaption`
     * compared `spanMs >= windowMs`, which only fires when a real sample happens to land exactly
     * on the display cutoff -- essentially never true for discretely-paced real probes.
     *
     * Constructed with 700ms spacing specifically because 700 does not evenly divide 60_000: with
     * an evenly-dividing spacing (e.g. 1000ms), the cutoff always lands exactly on a sample by
     * construction, which would accidentally make the old buggy `spanMs >= windowMs` check pass
     * too and mask the bug. This spacing guarantees the cutoff falls *between* two samples, the
     * way real jittered network timing does, reproducing the true failure mode.
     */
    @Test
    fun `isWindowFull reports true even when spanMs falls just short of windowMs -- the reported 59-second bug`() {
        var history = ProbeHistory(windowMs = 60_000)
        // 200 samples, 700ms apart: t = 0, 700, 1400, ..., 139_300. Comfortably exceeds the
        // window, so real data has genuinely aged out of what's displayed.
        repeat(200) { index -> history = history.recordSuccess(index * 700L, latencyMs = 10) }

        // The nearest real sample above the display cutoff does not land exactly on it, so the
        // displayed span is short of the full 60 seconds -- this is the actual, expected shape of
        // real discretely-sampled data, not a sign the window isn't full.
        assertTrue("expected spanMs short of windowMs, was ${history.spanMs}", history.spanMs < 60_000L)
        // ...yet the window genuinely is full: real retained data (everything before the cutoff)
        // has aged out of what's displayed.
        assertTrue(history.isWindowFull)
    }

    /**
     * The other required half of the same regression: a non-round window (per the on-device
     * report's second example, a 4-minute window reporting "last 3 mins") must also be detected
     * as full once it genuinely is, not just round 1-minute windows.
     */
    @Test
    fun `isWindowFull also fires for a longer, non-trivially-aligned window -- the reported 4-minute bug`() {
        val fourMinutes = 4 * 60_000L
        var history = ProbeHistory(windowMs = fourMinutes)
        // 900ms spacing: 900 does not evenly divide 240_000 (240_000 / 900 = 266.67), so the
        // cutoff can't land exactly on a sample boundary here either.
        repeat(400) { index -> history = history.recordSuccess(index * 900L, latencyMs = 10) }

        assertTrue(history.spanMs < fourMinutes)
        assertTrue(history.isWindowFull)
    }

    @Test
    fun `narrowing the window can turn a not-yet-full history into a genuinely full one`() {
        val history = ProbeHistory(windowMs = 60_000)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(30_000, latencyMs = 10)
        assertFalse(history.isWindowFull) // nothing has aged out of the 60s window yet.

        val narrowed = history.withWindowMs(10_000)
        // Narrowed to 10s: the t=0 sample (30s behind newest) is now excluded -- genuinely full.
        assertTrue(narrowed.isWindowFull)
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

    // --- Raw ping sparkline (debug aid) -----------------------------------------------------

    @Test
    fun `an empty history draws no raw ping line at all`() {
        assertTrue(ProbeHistory(windowMs = window).rawPingSparkline().isEmpty())
    }

    @Test
    fun `unlike the real latency line, a failure is a plotted point here, never a gap`() {
        var history = ProbeHistory(windowMs = window)
        repeat(5) { index -> history = history.recordFailure(index * 1_000L) }

        val points = history.rawPingSparkline()

        assertEquals(5, points.size)
        assertTrue(points.all { it.y != null })
    }

    @Test
    fun `a success plots at the fixed height a real 60ms sample would`() {
        val history = ProbeHistory(windowMs = window).recordSuccess(0, latencyMs = 999)

        val point = history.rawPingSparkline().single()

        assertEquals(1f - latencyColorFraction(60), point.y!!, 0.001f)
        assertEquals(60L, point.latencyMs)
    }

    @Test
    fun `a failure plots at the fixed height a real 900ms sample would`() {
        val history = ProbeHistory(windowMs = window).recordFailure(0)

        val point = history.rawPingSparkline().single()

        assertEquals(1f - latencyColorFraction(900), point.y!!, 0.001f)
        assertEquals(900L, point.latencyMs)
    }

    @Test
    fun `success and failure plot at clearly distinct heights, not close enough to blur together`() {
        val history = ProbeHistory(windowMs = window)
            .recordSuccess(0, latencyMs = 10)
            .recordFailure(1_000)

        val (successY, failureY) = history.rawPingSparkline().map { it.y!! }

        assertTrue((successY - failureY) > 0.5f)
    }

    @Test
    fun `x positions match the real latency sparkline's exactly, for the same samples`() {
        // The whole point of reusing latencySparkline's own mechanics rather than a second,
        // independently-written positioning scheme: the same real attempt lands at the same x
        // on both graphs, so they read as the same timeline.
        var history = ProbeHistory(windowMs = window)
        listOf(true, false, true, true, false).forEachIndexed { index, ok ->
            val at = index * 1_000L
            history = if (ok) history.recordSuccess(at, latencyMs = 30) else history.recordFailure(at)
        }

        val realXs = history.latencySparkline().map { it.x }
        val rawPingXs = history.rawPingSparkline().map { it.x }

        assertEquals(realXs, rawPingXs)
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
        // span): the t=0 attempt lands exactly on the window's own left edge, a boundary that
        // rounds it into its own bucket a naive constant-width count wouldn't otherwise reach --
        // the display is widened by one bucket rather than silently dropping it (see
        // successSparkline's own doc), so t=0 gets bucket 0 to itself, and the remaining two
        // clusters (3 and 4 real attempts respectively) fill buckets 1 and 2.
        var history = steadyStateHistory(windowMs = 7_000, bucketWidthMs = 3_500)
        listOf(true, true, true, false, true, true, true, false).forEachIndexed { index, ok ->
            val at = index * 1_000L
            history = if (ok) history.recordSuccess(at, latencyMs = 10) else history.recordFailure(at)
        }

        val points = history.successSparkline(bucketWidthMs = 3_500)

        assertEquals(3, points.size)
        assertEquals(1f, points[0].y!!, 0.001f) // t=0 alone -> 1 success / 1 attempt.
        assertEquals(2f / 3f, points[1].y!!, 0.001f) // t=1000,2000,3000 -> 2 successes / 3 attempts.
        assertEquals(0.75f, points[2].y!!, 0.001f) // t=4000,5000,6000,7000 -> 3 successes / 4 attempts.
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
        // this is purely ceil(windowMs / bucketWidthMs) steady-state arithmetic, plus exactly one
        // extra bucket in each case: windowedSamples' own earliest sample rounds to a slot the
        // naive ceil() count alone wouldn't reach, and the display is widened by one bucket
        // rather than silently dropping it (see successSparkline's own doc).
        val bucketWidthMs = 1_000L
        var history = steadyStateHistory(windowMs = 30 * 60_000L, bucketWidthMs = bucketWidthMs)
        repeat(2_000) { index -> history = history.recordSuccess(index * 1_000L, latencyMs = 10) }

        assertEquals(61, history.withWindowMs(60_000L).successSparkline(bucketWidthMs = bucketWidthMs).size)
        assertEquals(421, history.withWindowMs(7 * 60_000L).successSparkline(bucketWidthMs = bucketWidthMs).size)
        assertEquals(1_801, history.withWindowMs(30 * 60_000L).successSparkline(bucketWidthMs = bucketWidthMs).size)
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
     *
     * The one legitimate exception, excluded from the comparison below: the narrower window's own
     * *leftmost* bucket can carry a genuinely different value than the wider window's -- not a
     * rebinning artifact, but [windowedSamples]' own hard real-time cutoff (not aligned to bucket
     * boundaries) excluding some of that bucket's older real attempts from the narrower window's
     * count while the wider window still includes them. Every other (interior) bucket is
     * unaffected by that and must match exactly.
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
        // Both windows share the same newest sample, so the narrow window's *interior* buckets
        // (everything but its own leftmost -- see this test's own doc) are exactly the wide
        // window's own trailing buckets -- not a freshly rebinned grid of a different width.
        assertEquals(wide.takeLast(narrow.size - 1).map { it.y }, narrow.drop(1).map { it.y })

        // Widening back afterward must reveal exactly the same values as before narrowing, per
        // sample -- not a second, independently rebinned grid. Both sides share the same windowMs
        // here (420_000L), so there is no leftmost-bucket exception to carve out -- this must
        // match exactly, in full.
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
     * see [ProbeHistory.successSparkline]'s own doc): this checks that property directly.
     *
     * Both histories are built via [steadyStateHistory], which pushes the session's warm-up
     * anchor safely into the past before either one's own `t >= 0` samples are recorded --
     * exactly the same isolation technique the bucket-width group above uses, and for the same
     * reason: an empty warm-up sub-bucket is omitted rather than shown as a 0% miss (see the
     * dedicated "session warm-up: no false dips" test group), and *which* warm-up levels a given
     * pacing happens to land in is itself pacing-dependent -- a genuinely different, already-
     * legitimate source of size variation this test must not conflate with the
     * attempt-count-inflates-bucket-count regression it actually exists to catch. Isolating both
     * histories from warm-up entirely removes that confound regardless of what the two pacings
     * happen to be, rather than relying on picking pacing values finer than every warm-up level
     * (fragile: warm-up's finest level width is itself a function of the production bucket width,
     * which this fix's own change to [ProbeHistory.BUCKET_WIDTH_MS] already demonstrated once).
     */
    @Test
    fun `visible resolution depends on elapsed time, not on how many attempts arrived in it`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        var normal = steadyStateHistory(windowMs = 60_000, bucketWidthMs = bucketWidthMs)
        repeat(201) { index -> normal = normal.recordSuccess(index * 100L, latencyMs = 10) } // t=0..20_000.

        var doubled = steadyStateHistory(windowMs = 60_000, bucketWidthMs = bucketWidthMs)
        // Twice the attempts, packed into the exact same 0..20_000 span.
        repeat(401) { index -> doubled = doubled.recordSuccess(index * 50L, latencyMs = 10) }

        assertEquals(normal.successSparkline().size, doubled.successSparkline().size)
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

        // The window is exactly fully covered (span == windowMs), so resolution is maxed out at
        // every bucket that fits: 8 from ceil(60_750 / 7_594), plus one more -- windowedSamples'
        // own earliest sample, t=0, lands exactly on a bucket boundary and rounds into a slot the
        // naive ceil() count alone wouldn't reach; the display is widened by one bucket to
        // include it rather than silently drop it (see successSparkline's own doc). That gives
        // t=0 a narrow bucket of its own, separate from the rest of the leading cluster
        // (t=250,500,750, which share the next bucket over) -- both still real successes, at the
        // very start of the axis, with every bucket after them and before the trailing failure
        // cluster having zero real attempts.
        assertEquals(9, points.size)
        assertEquals(1f, points[0].y!!, 0.001f) // t=0 alone.
        assertEquals(1f, points[1].y!!, 0.001f) // t=250, 500, 750.
        assertEquals(0f, points.last().y!!, 0.001f)
        // Every bucket between the two clusters has no real attempts -- each collapses straight
        // into a 0% miss (not null, not omitted), the same as if every attempt in it had
        // failed, and connects normally to its neighbors rather than breaking the line.
        points.subList(2, points.size - 1).forEach { assertEquals(0f, it.y!!, 0.001f) }
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
     * fixed slot (`windowMs` = `bucketCount` * `bucketWidthMs`), so every slot ends up with
     * exactly one real attempt -- no averaging needed to reason about expected values --
     * following a known, deterministic pass/fail pattern (fails every 5th attempt). Once the
     * window is fully packed, a specific real sample's point is identified purely by its distance
     * from the *right* edge (the newest point): since exactly one new sample lands and exactly
     * one new fixed slot opens on every tick, that distance increases by exactly one index per
     * tick as long as nothing is migrating between buckets. Under steady, unchanging conditions
     * (no real outage, no setting change), that tracked point must report the pattern's own known
     * outcome on every single later tick -- and therefore must never change from one tick to the
     * next, checked directly too -- for as long as it is neither the live point nor within one
     * slot-width of aging off the window's trailing edge.
     *
     * Swept across bucket widths spanning fast pacing (200ms) through the production width
     * ([ProbeHistory.BUCKET_WIDTH_MS], 3_000ms -- anchored at the narrowest configurable
     * 1-minute window, see that constant's own doc) up to a 60s width paired with a 30-minute
     * window, the widest the settings screen's slider allows (see
     * `PingdPreferences.HISTORY_WINDOW_RANGE_MS`). This property predates this branch (from the
     * earlier "fixed absolute time slots" PR) but is exercised again by this branch's own
     * bucket-width/warm-up changes, so its coverage is widened here alongside the other two
     * properties in this file.
     */
    @Test
    fun `an interior bucket's value never changes on a later tick under steady, unchanging conditions`() {
        assertInteriorBucketStable(bucketWidthMs = 200L, bucketCount = 48) // fast pacing.
        assertInteriorBucketStable(bucketWidthMs = 1_000L, bucketCount = 48) // the original test's own config.
        assertInteriorBucketStable(bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS, bucketCount = 48) // production width (narrowest-window-anchored).
        assertInteriorBucketStable(bucketWidthMs = 60_000L, bucketCount = 30) // 30-minute window, the widest configurable.
    }

    /**
     * One parameterization of the test above: builds a fully-packed, [bucketCount]-bucket-wide
     * history at [bucketWidthMs] (one real sample per fixed slot, deterministic fail-every-5th
     * pattern), then advances 30 further ticks checking that an interior, already-closed bucket's
     * value never changes and always reports its own known outcome. See the `@Test` above for the
     * full reasoning; this only parameterizes the width/count so it can be swept.
     */
    private fun assertInteriorBucketStable(bucketWidthMs: Long, bucketCount: Int) {
        val stableWindowMs = bucketWidthMs * bucketCount
        fun succeededAt(i: Int) = i % 5 != 0 // deterministic, steady failure rate -- no real outage.

        // bucketCount + 1 samples, one full extra tick past a bare minimum warm-up, so the
        // retained span is exactly windowMs (not one pacing interval short of it) regardless of
        // how a given implementation decides "how full is full." steadyStateHistory here matters:
        // without it, the very first sample recorded (i=1) *is* this history's own warm-up
        // anchor, so its own elapsed-since-anchor is trivially 0 forever -- it never "ages out"
        // of warm-up no matter how much later real time passes elsewhere in the session, and
        // stays in the display (and forces extra warm-up-width buckets into view) for as long as
        // the configured window is wide enough to still include it. That's not what this test is
        // about; steadyStateHistory keeps it purely a steady-state check.
        var history = steadyStateHistory(windowMs = stableWindowMs, bucketWidthMs = bucketWidthMs)
        for (i in 1..(bucketCount + 1)) {
            val t = i * bucketWidthMs
            history = if (succeededAt(i)) history.recordSuccess(t, latencyMs = 10) else history.recordFailure(t)
        }
        // The window is now fully packed: one real sample per fixed slot, plus one extra bucket
        // throughout -- windowedSamples' own earliest sample always lands exactly on the
        // window's cutoff boundary here, which (per the closed-on-right slot convention) rounds
        // it into the slot just *before* the naive bucketCount-wide range, and the display is
        // widened by one bucket to include it rather than silently dropping it. That extra
        // bucket is a constant, uniform offset every tick (not a source of instability) -- the
        // tracked-index math below only cares about position *from the right edge*, which this
        // doesn't change.
        val label = "bucketWidthMs=$bucketWidthMs bucketCount=$bucketCount"
        assertEquals(label, bucketCount + 1, history.successSparkline(bucketWidthMs).size)

        // Track the sample recorded at i = trackedI. At k ticks later it must sit exactly k
        // positions in from the right edge, still carrying its own known outcome.
        val trackedI = bucketCount / 2
        val trackedExpected = if (succeededAt(trackedI)) 1f else 0f
        var previousValue: Float? = null

        for (i in (bucketCount + 2)..(bucketCount + 31)) {
            val t = i * bucketWidthMs
            history = if (succeededAt(i)) history.recordSuccess(t, latencyMs = 10) else history.recordFailure(t)

            val k = i - trackedI // ticks since the tracked sample was recorded
            // Not live (k > 0) and not within one slot-width of the trailing edge (k <= bucketCount - 2).
            if (k <= 0 || k > bucketCount - 2) continue

            val points = history.successSparkline(bucketWidthMs)
            val index = points.size - 1 - k
            if (index !in points.indices) continue

            val value = points[index].y!!
            assertEquals(
                "$label: tracked sample (i=$trackedI) must still report its own known outcome at tick i=$i",
                trackedExpected,
                value,
                0.001f,
            )
            previousValue?.let { previous ->
                assertEquals(
                    "$label: an interior, already-closed bucket's value must not change from one tick to the next",
                    previous,
                    value,
                    0.001f,
                )
            }
            previousValue = value
        }

        // The stability check above must have actually exercised multiple ticks, not vacuously
        // passed because the loop's range never satisfied its own guards.
        assertNotNull(label, previousValue)
    }

    // --- Full-coverage invariant: every windowed sample lands in exactly one displayed bucket ---
    //
    // Regression test for a bug an independent Python port of this exact bucketing logic found
    // by stress-testing across window sizes and pacing intervals: the original left-edge
    // detection compared raw slot *numbers* (`naiveLeftmostSlot < warmupSlotBase`) to decide
    // whether to apply a real-time-based correction at all -- a strict `<` comparison that is a
    // false negative exactly *at* the boundary itself (when the two are equal). That silently
    // excluded real, already-counted-in-attemptCount samples from the display for a substantial,
    // repeated fraction of ticks in ordinary usage -- not the tiny, already-accepted "up to one
    // bucket-width" boundary artifact successBucketSlot's own doc describes, which this is a
    // categorically different, much larger problem from. The fix (see successSparkline's own
    // doc) replaced the heuristic with the actual invariant this test verifies directly and
    // unconditionally: the display must always reach back at least as far as windowedSamples'
    // own earliest retained sample.
    //
    // [SparklinePoint] doesn't expose raw per-bucket attempt counts, so this test verifies the
    // invariant through a closed-form identity instead of inspecting bucket values directly:
    // successSparkline's returned list omits only a *leading* run of buckets (the ones strictly
    // before the earliest windowed sample's own slot -- see its own doc), so its length must
    // always equal `newestSlot - earliestWindowedSampleSlot + 1`, with no clamping shortening it.
    // Under the bug, [firstSampleIndex]'s `coerceIn(0, ...)` clamp masked what would otherwise
    // have been a negative index -- silently truncating the returned list below that identity's
    // value instead of surfacing the problem. bucketSlot/warmupLevel/successBucketSlot are
    // mirrored here test-locally (the same technique used to find this bug in the first place,
    // and the established pattern elsewhere in this suite for algorithm-level verification) to
    // compute the identity's two slot numbers from the outside, using only [ProbeHistory]'s
    // public [samples] and [BUCKET_WIDTH_MS].
    //
    // Pacing here is deliberately fast enough (50ms/200ms) that no warm-up sub-bucket is ever
    // empty, keeping this test isolated from the *separate*, legitimate "empty warm-up sub-bucket
    // is omitted, not a miss" behavior (see the dedicated "no false dips" test group below) --
    // that rule removes only entries this identity never counted as coverage in the first place
    // (empty buckets, which by definition hold no real sample), so it cannot itself cause data to
    // go missing, but *would* change this identity's expected value at slower pacing, for reasons
    // unrelated to the coverage bug this test exists to catch. That combination is covered
    // instead by the full-coverage assertion inside the "no false dips" test below.

    private fun testSuccessBucketSlot(timestampMs: Long, bucketWidthMs: Long): Long =
        Math.floorDiv(timestampMs - 1, bucketWidthMs)

    private fun testWarmupLevel(elapsedMs: Long, bucketWidthMs: Long): Int {
        val warmupLevels = 6 // must match ProbeHistory's own private WARMUP_LEVELS.
        val unit = (bucketWidthMs / ((1L shl warmupLevels) - 1)).coerceAtLeast(1L)
        for (level in 0 until warmupLevels - 1) {
            val boundary = ((1L shl (level + 1)) - 1) * unit
            if (elapsedMs < boundary) return level
        }
        return warmupLevels - 1
    }

    private fun testBucketSlot(timestampMs: Long, bucketWidthMs: Long, anchorMs: Long): Long {
        val warmupLevels = 6
        val warmupEndMs = anchorMs + bucketWidthMs
        // Strictly greater than, not >=: mirrors ProbeHistory.bucketSlot's own fix -- the ordinary
        // slot containing warmupEndMs itself is otherwise unreachable by any other real timestamp.
        if (timestampMs > warmupEndMs) return testSuccessBucketSlot(timestampMs, bucketWidthMs)
        val warmupSlotBase = testSuccessBucketSlot(warmupEndMs + 1, bucketWidthMs)
        return warmupSlotBase - warmupLevels + testWarmupLevel(timestampMs - anchorMs, bucketWidthMs)
    }

    /**
     * The number of buckets [ProbeHistory.successSparkline] *should* display for [history] at
     * [windowMs]/[bucketWidthMs], computed independently from the outside via the same slot
     * arithmetic mirrored above -- the shared ground truth both the full-coverage test and the
     * no-false-dips test below check their actual output against. Accounts for both reasons a
     * slot can legitimately be missing from the displayed range: it sits entirely before the
     * earliest windowed sample (ordinary leading-blank / narrower-window truncation), or it's an
     * empty warm-up sub-bucket (see [ProbeHistory.successSparkline]'s own doc for why that's a
     * second, distinct omission). A slot is expected to be displayed iff it holds a real sample
     * or it's at/after both the earliest windowed sample's slot and the first slot warm-up could
     * ever hand off to the ordinary grid.
     */
    private fun expectedDisplayedBucketCount(history: ProbeHistory, windowMs: Long, bucketWidthMs: Long): Int =
        expectedDisplayedSlots(history, windowMs, bucketWidthMs).size

    /**
     * The ordered slot numbers [ProbeHistory.successSparkline] *should* display for [history] at
     * [windowMs]/[bucketWidthMs], mirroring the same logic [expectedDisplayedBucketCount] already
     * verified against the real implementation (this only returns the slot list behind that count,
     * rather than just its size) -- ascending, the same order [successSparkline]'s own returned
     * [SparklinePoint] list is in, so `expectedDisplayedSlots(...).zip(points)` pairs each real
     * displayed point with the slot it represents. Used by the alignment tests below to locate
     * which returned point a given real sample's own timestamp landed in, without assuming a
     * point's position in the list equals `slot - leftmostSlot` -- that identity only holds when
     * nothing was omitted, which the leading-blank and empty-warm-up-sub-bucket rules routinely
     * violate (see [ProbeHistory.successSparkline]'s own doc).
     */
    /**
     * The `(leftmostSlot, newestSlot)` pair [ProbeHistory.successSparkline] itself computes
     * internally -- mirrored here the same way [testBucketSlot] et al. mirror the production
     * bucketing functions -- *before* any blank/empty-warm-up-sub-bucket entries are filtered out
     * of the returned list. This is the pre-filter slot range the old, reverted index-based x
     * formula (`index / (displayedCount - 1)`) was computed against: `index` there was always a
     * position in *this* range, never a position in the shorter, already-filtered output list --
     * see [expectedDisplayedSlots]'s own doc for why that distinction matters.
     */
    private fun nominalLeftmostAndNewestSlot(history: ProbeHistory, windowMs: Long, bucketWidthMs: Long): Pair<Long, Long> {
        val anchorMs = history.samples.first().timestampMs
        val newest = history.samples.last().timestampMs
        val cutoff = newest - windowMs // mirrors windowedSamples' own documented cutoff rule.
        val earliestWindowedSlot = testBucketSlot(
            history.samples.first { it.timestampMs >= cutoff }.timestampMs,
            bucketWidthMs,
            anchorMs,
        )
        val newestSlot = testBucketSlot(newest, bucketWidthMs, anchorMs)
        val naiveBucketCount = ((windowMs + bucketWidthMs - 1) / bucketWidthMs).coerceAtLeast(1L)
        val leftmostSlot = minOf(newestSlot - naiveBucketCount + 1, earliestWindowedSlot)
        return leftmostSlot to newestSlot
    }

    /**
     * The ordered slot numbers [ProbeHistory.successSparkline] *should* display for [history] at
     * [windowMs]/[bucketWidthMs] -- ascending, the same order [successSparkline]'s own returned
     * [SparklinePoint] list is in, so `expectedDisplayedSlots(...).zip(points)` pairs each real
     * displayed point with the slot it represents. Used by the alignment tests below to locate
     * which returned point a given real sample's own timestamp landed in, without assuming a
     * point's position in the *returned* list equals `slot - leftmostSlot` -- that identity only
     * holds when nothing was omitted, which the leading-blank and empty-warm-up-sub-bucket rules
     * routinely violate (see [ProbeHistory.successSparkline]'s own doc): the returned list can be
     * -- and, especially during warm-up, usually is -- shorter than the full
     * `newestSlot - leftmostSlot + 1` range [nominalLeftmostAndNewestSlot] describes.
     */
    private fun expectedDisplayedSlots(history: ProbeHistory, windowMs: Long, bucketWidthMs: Long): List<Long> {
        val anchorMs = history.samples.first().timestampMs
        val newest = history.samples.last().timestampMs
        val cutoff = newest - windowMs
        val windowed = history.samples.filter { it.timestampMs >= cutoff }
        val earliestWindowedSlot = testBucketSlot(windowed.first().timestampMs, bucketWidthMs, anchorMs)
        val (leftmostSlot, newestSlot) = nominalLeftmostAndNewestSlot(history, windowMs, bucketWidthMs)
        val warmupSlotBase = testSuccessBucketSlot(anchorMs + bucketWidthMs + 1, bucketWidthMs)
        val occupiedSlots = windowed.map { testBucketSlot(it.timestampMs, bucketWidthMs, anchorMs) }.toSet()
        return (leftmostSlot..newestSlot).filter { slot ->
            slot in occupiedSlots || (slot >= earliestWindowedSlot && slot >= warmupSlotBase)
        }
    }

    @Test
    fun `every windowed sample lands in exactly one displayed bucket, at every tick of a running session`() {
        // A sweep of window sizes (from the narrowest up through 30 minutes -- the widest the
        // settings screen's slider actually allows, PingdPreferences.HISTORY_WINDOW_RANGE_MS)
        // and pacing intervals from a fast 50ms up through a slow 1s, well past the app's own
        // realistic pacing ceiling (PingdPreferences.STEP_DELAY_RANGE_MS is 0-1000ms). This
        // covers the same dimensions the independent Python port swept to find this bug,
        // including two of the exact intervals it reported triggering on (60_000/200,
        // 60_000/50), broadened with the reviewer's own wider ad hoc sweep (window sizes up to
        // 30 minutes) in mind. [expectedDisplayedBucketCount] accounts for empty-warm-up-bucket
        // omission too, so slower pacing (where a warm-up sub-bucket can legitimately be empty)
        // no longer needs its own separate test group to stay correct here.
        val windowSizesMs = listOf(60_000L, 120_000L, 420_000L, 900_000L, 30 * 60_000L)
        val intervalsMs = listOf(50L, 200L, 1_000L)

        windowSizesMs.forEach { windowMs ->
            intervalsMs.forEach { intervalMs ->
                var history = ProbeHistory(windowMs = windowMs)
                val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS

                // Many consecutive ticks -- covering session warm-up, the transition into a
                // fully-packed window, and long-running steady state, for every config.
                repeat(250) { i ->
                    val t = i * intervalMs
                    history = if (i % 5 != 0) {
                        history.recordSuccess(t, latencyMs = 10)
                    } else {
                        history.recordFailure(t)
                    }

                    val expectedSize = expectedDisplayedBucketCount(history, windowMs, bucketWidthMs)
                    val actualSize = history.successSparkline().size
                    assertEquals(
                        "windowMs=$windowMs intervalMs=$intervalMs tick=$i: successSparkline() returned " +
                            "$actualSize points but $expectedSize are needed for full coverage -- real data " +
                            "would be silently dropped from the line",
                        expectedSize,
                        actualSize,
                    )
                }
            }
        }
    }

    // --- Cross-graph alignment: success buckets sit at the same x as the latency graph would put ---
    // --- that same moment in time (the "do the two graphs visually track together" property) ------
    //
    // Regression tests for a structural bug an independent reviewer found (not on-device):
    // successSparkline() positioned each displayed bucket via `index / (displayedCount - 1)` -- a
    // linear array-index scale with no direct relationship to real elapsed time -- while
    // latencySparkline() (and markerFractions()) position every point via windowFraction, a
    // continuous real-time scale anchored to the configured window. The two scales only coincided
    // when displayedCount happened to equal the nominal ceil(windowMs / bucketWidthMs) bucket count
    // almost exactly, which is not guaranteed: the warm-up ladder's narrower sub-buckets, and the
    // minOf-based full-coverage correction (both covered by their own dedicated test groups above),
    // both legitimately push displayedCount above the nominal count without the index-to-x mapping
    // compensating. Confirmed independently before the fix (see this branch's own investigation) by
    // porting the exact bucketSlot/successSparkline logic into a standalone simulation and comparing
    // the old index-based x against windowFraction for the same real timestamp: up to 23-25% of the
    // graph's total width apart during warm-up, and a persistent ~4% offset even in ordinary
    // steady state at the default window -- both large enough that a moment placed near the middle
    // of the latency graph could land a quarter of the screen off on the success graph, which is
    // exactly why the two graphs did not read as "the same timeline."
    //
    // The fix (see ProbeHistory.successSparkline's own "Bucket position is time-based" doc section)
    // derives each bucket's x from windowFraction applied to that bucket's own real timestamp (its
    // right edge -- see ProbeHistory.bucketRightEdgeMs's own doc for why that boundary specifically)
    // instead of its array index. Bucket *membership* -- which real samples land in which bucket --
    // is completely untouched; only where an already-computed bucket is drawn changes.

    /**
     * The tolerance used by every test in this group: [ProbeHistory.BUCKET_WIDTH_MS] / `windowMs`
     * -- the worst-case discretization error any displayed bucket can carry on the window-anchored
     * axis. A bucket's x represents its own *right* edge (see [ProbeHistory.bucketRightEdgeMs]'s
     * doc), so a real sample sitting anywhere earlier inside that same bucket is at most one
     * bucket-width away from that edge in real time -- and session warm-up's sub-buckets are always
     * *narrower* than [ProbeHistory.BUCKET_WIDTH_MS] by construction (see
     * [ProbeHistory.warmupLevel]'s own doc, referenced via this file's own mirrored
     * `testWarmupLevel`), so this bound conservatively covers warm-up buckets too, not just the
     * ordinary grid's. This is deliberately not "exact" -- bucket aggregation inherently discretizes
     * real time, an unavoidable property of any bucketed graph, not a bug -- but it is small enough,
     * relative to the configured window, that "the two graphs visually track together" is genuinely
     * true rather than merely closer than the pre-fix ~23-25%/~4% gaps above: at the production
     * bucket width, this tolerance ranges from 5% of the graph's width at the narrowest configurable
     * window down to well under 1% at the default and wider windows.
     */
    private fun alignmentToleranceFor(windowMs: Long, bucketWidthMs: Long): Float = bucketWidthMs.toFloat() / windowMs

    /**
     * Direct proof the misalignment was real, using the exact pre-fix formula, so this test would
     * fail (for the right reason) if the fix in [ProbeHistory.successSparkline] were ever reverted:
     * temporarily reverting the fix and re-running this file confirmed this specific assertion is
     * what catches it (see this branch's own commit history for that red/green evidence). Builds a
     * scenario known from the investigation above to produce a large, easily-checked gap (the
     * narrowest configurable window, well into warm-up) and asserts the *old* formula's x would have
     * missed the *new* one's by well more than this group's own tolerance -- i.e. that the fix
     * changed something real, not just refactored the same numbers into a different shape.
     */
    @Test
    fun `the old index-based x formula would have missed windowFraction by far more than the fix's own tolerance`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        val windowMs = ProbeHistory.NARROWEST_WINDOW_MS
        var history = ProbeHistory(windowMs = windowMs)
        listOf(0L, 100L, 200L, 400L, 1_000L, 2_000L, 5_000L, 9_000L).forEach { t ->
            history = history.recordSuccess(t, latencyMs = 10)
        }

        val points = history.successSparkline(bucketWidthMs)
        val slots = expectedDisplayedSlots(history, windowMs, bucketWidthMs)
        assertEquals(slots.size, points.size) // sanity: this test's own setup must be full-coverage.

        // The old formula's `index` was always a position in the *pre-filter* leftmostSlot..
        // newestSlot range (nominalDisplayedCount below) -- never a position in the shorter,
        // already-filtered list `points`/`slots` are (see expectedDisplayedSlots's own doc). This
        // is exactly what my own first attempt at this test got wrong (mirroring `points.size`
        // instead), so it's captured explicitly here rather than left implicit.
        val (leftmostSlot, newestSlot) = nominalLeftmostAndNewestSlot(history, windowMs, bucketWidthMs)
        val nominalDisplayedCount = (newestSlot - leftmostSlot + 1).toInt()
        fun oldFormulaX(slot: Long): Float {
            val originalIndex = (slot - leftmostSlot).toInt()
            return if (nominalDisplayedCount == 1) 1f else originalIndex.toFloat() / (nominalDisplayedCount - 1)
        }

        val tolerance = alignmentToleranceFor(windowMs, bucketWidthMs)

        // The worst-offending point, whichever index that turns out to be, rather than a single
        // hand-picked one -- both formulas necessarily agree at the newest point (both pin it at
        // x = 1 by construction), so the real divergence has to be found among the rest. In this
        // scenario it is the earliest real point (t=0, session's very first sample, deep in
        // warm-up): the old formula placed it at a position derived from the *nominal* (pre-blank-
        // filtering) bucket count, far from where 8 real, unevenly-spaced-in-time samples actually
        // sit on the real elapsed-time axis.
        val (worstIndex, worstDiff) = points.indices
            .map { i -> i to kotlin.math.abs(oldFormulaX(slots[i]) - points[i].x) }
            .maxBy { it.second }
        assertTrue(
            "expected the old index-based formula to diverge from the fixed windowFraction-based x " +
                "by more than the tolerance ($tolerance) somewhere in this history -- worst was at " +
                "index=$worstIndex old=${oldFormulaX(slots[worstIndex])} new=${points[worstIndex].x} " +
                "diff=$worstDiff -- otherwise this scenario doesn't actually demonstrate the bug " +
                "this fix addresses",
            worstDiff > tolerance,
        )
    }

    /**
     * The property this task exists to establish: for a real sample's own timestamp, the
     * success-bucket it lands in must sit at (approximately) the same x windowFraction would place
     * that same timestamp at on the latency graph -- i.e. the two graphs place the same moment in
     * time at the same horizontal position, so they read as two views of one synchronized timeline.
     * Swept across the full configurable window range and a range of pacing intervals from fast
     * (50ms) through slow (2_000ms, past the app's own realistic pacing ceiling), checked at
     * multiple points across each run -- early ticks still inside session warm-up specifically, not
     * only after the display has settled into steady state, since the two mechanisms this fix
     * corrects for (warm-up's narrower sub-buckets, and the minOf full-coverage correction) both
     * bite hardest during and shortly after warm-up.
     */
    @Test
    fun `a real sample's success-bucket x tracks windowFraction for that same timestamp, within one bucket-width`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        val windowSizesMs = listOf(ProbeHistory.NARROWEST_WINDOW_MS, 120_000L, 420_000L, 900_000L, 30 * 60_000L)
        val intervalsMs = listOf(50L, 200L, 500L, 1_000L, 2_000L)
        // Checkpoints span deep into warm-up (i=1..20, well within the ~3s warm-up era at every
        // pacing tested), the transition out of it, and long-running steady state.
        val checkpoints = setOf(1, 2, 5, 10, 20, 50, 100, 199)

        windowSizesMs.forEach { windowMs ->
            intervalsMs.forEach { intervalMs ->
                var history = ProbeHistory(windowMs = windowMs)
                val tolerance = alignmentToleranceFor(windowMs, bucketWidthMs)
                var t = 0L

                repeat(200) { i ->
                    if (i > 0) t += intervalMs
                    history = if (i % 5 != 0) {
                        history.recordSuccess(t, latencyMs = 10)
                    } else {
                        history.recordFailure(t)
                    }

                    if (i !in checkpoints) return@repeat

                    val points = history.successSparkline(bucketWidthMs)
                    val slots = expectedDisplayedSlots(history, windowMs, bucketWidthMs)
                    assertEquals(
                        "windowMs=$windowMs intervalMs=$intervalMs tick=$i: slot count must match " +
                            "point count for this alignment check to be meaningful",
                        slots.size,
                        points.size,
                    )
                    val xBySlot = slots.zip(points.map { it.x }).toMap()

                    val anchorMs = history.samples.first().timestampMs
                    val newest = history.samples.last().timestampMs
                    val cutoff = newest - windowMs
                    history.samples.filter { it.timestampMs >= cutoff }.forEach { sample ->
                        val slot = testBucketSlot(sample.timestampMs, bucketWidthMs, anchorMs)
                        val actualX = xBySlot[slot]
                            ?: error(
                                "windowMs=$windowMs intervalMs=$intervalMs tick=$i: no displayed " +
                                    "bucket for real sample at t=${sample.timestampMs} (slot=$slot) " +
                                    "-- a full-coverage violation, not an alignment one",
                            )
                        val expectedX = 1f - (newest - sample.timestampMs).toFloat() / windowMs
                        val diff = kotlin.math.abs(actualX - expectedX)
                        assertTrue(
                            "windowMs=$windowMs intervalMs=$intervalMs tick=$i sampleT=" +
                                "${sample.timestampMs}: bucket x=$actualX vs windowFraction=" +
                                "$expectedX, diff=$diff exceeds tolerance $tolerance",
                            diff <= tolerance,
                        )
                    }
                }
            }
        }
    }

    // --- Session warm-up: no false dips from over-fine granularity ---
    //
    // Regression test for a second, separate bug an independent reviewer found while re-checking
    // the warm-up feature's actual point *values* (the point-count/permanence tests below only
    // ever checked count and stability, not whether an empty bucket is itself a false signal):
    // the warm-up ladder's finest levels are narrower than any realistic real-world probe pacing
    // -- ~139/276/552ms at the default bucket width, against an actual inter-probe spacing of
    // roughly 400ms-4s (the app's configurable step delay, doubled per the tracer's own cycle
    // structure -- see `PingdPreferences.STEP_DELAY_RANGE_MS`). So it is *routine*, not rare,
    // for two genuinely consecutive real attempts to skip over one or more of those finest
    // levels, leaving them with zero real attempts even on a perfectly healthy, unbroken
    // connection. Since an empty bucket used to always be a 0% miss with no exception, that
    // showed as 2-3 fabricated failure dips scattered through the first several seconds of an
    // all-success session at any pacing slower than the very fastest setting -- confirmed by an
    // independent simulation with realistic jitter around each configurable pacing interval,
    // consistently across every jitter seed tried, not a rare coincidence.
    //
    // The fix (see successSparkline's own doc, the no-gap rule's "two exceptions" list) is *not*
    // an adaptive, per-gap "does this silence look real" judgment call -- that design was already
    // tried and explicitly rejected for the ordinary grid, and reintroducing it here would be the
    // same mistake in a new spot. It's a fixed, permanent, unconditional fact about which slots
    // even *exist* during warm-up: because every real attempt the tracer makes is recorded at a
    // bounded pace even during a genuine outage (see [ProbeHistory.recordFailure]'s own doc), an
    // empty warm-up sub-bucket can only ever mean "no attempt has landed in this narrow a slice
    // yet" -- never a real, silent outage the way an empty bucket in the ordinary grid's much
    // wider buckets legitimately could.

    @Test
    fun `an all-success session produces no false failure dips at any realistic pacing`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        // The app's real configurable step-delay range is 0-1000ms (STEP_DELAY_RANGE_MS); actual
        // inter-probe spacing runs roughly double that per the tracer's own ping/ping/fake cycle
        // structure, so this covers the app's full realistic pacing range, plus a fast interval
        // fine enough that no false dip should appear at all (a sanity check on the simulation
        // itself, matching the independent reviewer's own "200ms: no false dips" finding). The
        // exact interval set mirrors the reviewer's own broader ad hoc sweep (200/400/500/750/
        // 1000/1500/2000ms x 6 jitter seeds = 42 configs) that stress-tested this property before
        // approving the fix, now captured here as permanent, repo-tracked coverage.
        val nominalIntervalsMs = listOf(200L, 400L, 500L, 750L, 1_000L, 1_500L, 2_000L)
        val jitterFraction = 0.15
        val seeds = 0..5
        val windowMs = 60_000L

        nominalIntervalsMs.forEach { intervalMs ->
            seeds.forEach { seed ->
                val random = kotlin.random.Random(seed)
                var history = ProbeHistory(windowMs = windowMs)
                var t = 0L

                repeat(60) { i ->
                    if (i > 0) {
                        // +/-15% jitter around the nominal interval -- real pacing is never
                        // perfectly metronomic.
                        val jitter = (intervalMs * jitterFraction * (random.nextDouble() * 2 - 1)).toLong()
                        t += (intervalMs + jitter).coerceAtLeast(1L)
                    }
                    history = history.recordSuccess(t, latencyMs = 10)

                    val points = history.successSparkline(bucketWidthMs)
                    assertTrue(
                        "intervalMs=$intervalMs seed=$seed tick=$i (t=$t): an all-success session must " +
                            "never show a 0% dip -- found one at index ${points.indexOfFirst { it.y == 0f }}",
                        points.none { it.y == 0f },
                    )

                    // The fix for the false dips must not reopen the full-coverage guarantee from
                    // the previous test group -- every windowed sample still has to land in
                    // exactly one displayed bucket. Unlike that test group's own naive count, an
                    // empty warm-up sub-bucket is now *also* legitimately omitted (not just the
                    // leading run), which [expectedDisplayedBucketCount] accounts for directly.
                    val expectedSize = expectedDisplayedBucketCount(history, windowMs, bucketWidthMs)

                    assertEquals(
                        "intervalMs=$intervalMs seed=$seed tick=$i (t=$t): full coverage must hold even " +
                            "with empty warm-up sub-buckets omitted",
                        expectedSize,
                        points.size,
                    )
                }
            }
        }
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
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS // production width, 3_000ms.
        var history = ProbeHistory(windowMs = 60_000)
        var previousSize = 0
        var sawIncrease = false
        // These six elapsed times land in six *different* warm-up levels (unit = 47ms at this
        // bucket width, levels doubling from there -- see warmupLevel's own doc: [0,47), [47,141),
        // [141,329), [329,705), [705,1_457), [1_457,3_000]). Under the design this replaces, every
        // one of these but the very first (t=0) would instead collapse into the same single,
        // un-subdivided 3_000ms bucket, since none of them reach bucketWidthMs. windowMs (60s)
        // stays far wider than the elapsed real time (2s) throughout, so the display's left
        // edge stays pinned at the session's own start the whole time -- what makes the point
        // count provably non-decreasing here.
        listOf(0L, 100L, 200L, 400L, 1_000L, 2_000L).forEach { t ->
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
     * "an interior bucket's value never changes on a later tick" test one level down. `t = 70`
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
            .recordSuccess(100, latencyMs = 10) // level 1
            .recordFailure(200) // level 2 -- the tracked bucket, a known failure.
        val afterSeed = history.successSparkline(bucketWidthMs)
        // Confirms the setup really did land these three in three separate, consecutive buckets
        // (not the ordinary un-subdivided grid's own single bucket) before tracking begins.
        assertEquals(3, afterSeed.size)
        val trackedValue = afterSeed[2].y
        assertEquals(0f, trackedValue)

        // t=400..2_000 are later warm-up levels (3, 4, 5); t=3_000 still warm-up but folds into
        // level 5's own wide catch-all bucket (the boundary instant itself -- see bucketSlot's
        // own doc); t=3_001 onward are ordinary, post-warm-up, steady-state buckets. None of them
        // ever falls back into level 2's own narrow span.
        val laterTimestamps = listOf(400L, 1_000L, 2_000L, 3_000L, 3_001L, 30_000L, 55_000L)
        laterTimestamps.forEach { t ->
            history = history.recordSuccess(t, latencyMs = 10)
            val points = history.successSparkline(bucketWidthMs)
            assertEquals(
                "the warm-up-assigned t=200 bucket's value changed after recording t=$t -- a permanence violation",
                trackedValue,
                points[2].y,
            )
        }
    }

    // --- Narrow-window responsiveness (on-device report: at the 1-minute window, the line raced
    // to full width in ~9-15 real seconds instead of the actual 60, and thereafter only scrolled
    // once every ~8.75s -- a visibly chunkier cadence than the latency line's continuous
    // per-sample motion right next to it) ------------------------------------------------------
    //
    // Root cause: [ProbeHistory.BUCKET_WIDTH_MS] used to be anchored at [DEFAULT_WINDOW_MS] (7
    // minutes) / 48, ~8_750ms. That's fine resolution *at* the default window, but at the
    // *narrowest* configurable 1-minute window it left only ~7 total buckets, all of which
    // (warm-up included) resolve within about 9-15 real seconds -- so the display reached its
    // full, final shape in roughly a quarter of the window it was supposedly covering, and
    // thereafter only scrolled once every ~8.75 seconds. The fix re-anchors [BUCKET_WIDTH_MS] at
    // [NARROWEST_WINDOW_MS] instead (see that constant's own doc, and [ANCHOR_BUCKET_COUNT]'s /
    // [BUCKET_WIDTH_MS]'s doc for the specific 3_000ms width chosen and the false-dip floor that
    // bounds it from below). These tests check the two user-facing properties the on-device
    // report was actually about -- neither was covered by the bucket-count/stability/no-false-
    // dips tests above, which are about correctness invariants, not perceived responsiveness.

    /**
     * Property 1 from the on-device report: at the narrowest configurable window, the display
     * should take a *reasonable fraction* of the window's own real duration to reach full
     * resolution, not race to full width in a handful of seconds. Concrete thresholds: at 25% of
     * the window's elapsed real time (15 real seconds into a 60-second window -- deliberately the
     * exact figure the on-device report described, "a few seconds" rounding up to the top of the
     * "9-15 seconds" estimate), the display must show well under half of its eventual full
     * resolution ([ANCHOR_BUCKET_COUNT] / 2 = 10 points); by the time 95% of the window has
     * elapsed (57 of 60 seconds), it must be close to fully resolved (within 2 points of
     * [ANCHOR_BUCKET_COUNT]). Both thresholds hold with real headroom at the chosen production
     * width (3_000ms: 15s of elapsed time covers only 5 ordinary buckets past the first real
     * sample's own boundary-widened one, 6 total, well under the 10-point ceiling; 57s covers 19
     * of the 20 anchor buckets plus the same one-bucket boundary widening, comfortably within 2 of
     * full). Dense (250ms), unjittered pacing -- far finer than the 3_000ms production bucket
     * width -- keeps this purely about the *count* of populated ordinary buckets over elapsed
     * time, with no empty-bucket omissions of any kind to complicate the arithmetic.
     */
    @Test
    fun `at the narrowest configurable window, resolution builds up over roughly the real window duration`() {
        val windowMs = ProbeHistory.NARROWEST_WINDOW_MS // 60_000ms -- the on-device report's own setting.
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        var history = steadyStateHistory(windowMs = windowMs, bucketWidthMs = bucketWidthMs)

        var sizeAt15s = -1
        var sizeAt57s = -1
        var t = 0L
        while (t <= windowMs) {
            history = history.recordSuccess(t, latencyMs = 10)
            if (t >= 15_000L && sizeAt15s == -1) sizeAt15s = history.successSparkline(bucketWidthMs).size
            if (t >= 57_000L && sizeAt57s == -1) sizeAt57s = history.successSparkline(bucketWidthMs).size
            t += 250L
        }

        assertTrue(
            "at ~15s into a 60s window, resolution should still be well under half-built (was $sizeAt15s of " +
                "${ProbeHistory.ANCHOR_BUCKET_COUNT}) -- racing to near-full this early is the exact bug this " +
                "fix addresses",
            sizeAt15s in 1..(ProbeHistory.ANCHOR_BUCKET_COUNT / 2),
        )
        assertTrue(
            "at ~57s into a 60s window, resolution should be nearly fully built (was $sizeAt57s of " +
                "${ProbeHistory.ANCHOR_BUCKET_COUNT})",
            sizeAt57s >= ProbeHistory.ANCHOR_BUCKET_COUNT - 2,
        )
        assertTrue("resolution must have genuinely grown between the two checkpoints", sizeAt57s > sizeAt15s)
    }

    /**
     * Property 2 from the on-device report: the scroll cadence (how often a new bucket opens,
     * shifting the line) must be reasonably fine relative to realistic probe pacing, not an
     * "8+ second jump." [ProbeHistory.BUCKET_WIDTH_MS] *is* that cadence -- a new ordinary bucket
     * opens exactly every [ProbeHistory.BUCKET_WIDTH_MS] of real elapsed time, by construction --
     * so this is a direct, permanent guard on that one number rather than a simulation, pinning
     * both edges of the range this fix's own investigation established:
     *
     * - A **floor** of 2_000ms: the app's own worst-case realistic gap between two real probe
     *   attempts, 2 x `PingdPreferences.STEP_DELAY_RANGE_MS`'s upper bound (1_000ms) -- see
     *   [ProbeCycleRunner]'s "ping, ping, fake" cycle doc. A width at or below this figure can
     *   fabricate a false 0% dip in the *ordinary* grid on a perfectly healthy, all-success
     *   session (see the dedicated reproduction test below) -- this fix's own most aggressive
     *   candidate width (1_250ms, anchored at [ANCHOR_BUCKET_COUNT] = 48) hit exactly this failure
     *   mode in the "no false dips" sweep above before being replaced by the current, safer width.
     * - A **ceiling** of 4_000ms: comfortably under half the pre-fix default-anchored width
     *   (~8_750ms) that produced the on-device "8+ second jump" report in the first place, so a
     *   regression back toward that old cadence would fail this test long before reaching it.
     */
    @Test
    fun `the production bucket width -- the scroll cadence -- clears the false-dip floor with margin and stays well under the old sluggish width`() {
        assertTrue(
            "bucket width (${ProbeHistory.BUCKET_WIDTH_MS}ms) must clear the app's worst-case realistic " +
                "single probe gap (2_000ms) with real margin, or the ordinary grid can fabricate false dips",
            ProbeHistory.BUCKET_WIDTH_MS >= 2_500L,
        )
        assertTrue(
            "bucket width (${ProbeHistory.BUCKET_WIDTH_MS}ms) must stay well under the old default-anchored " +
                "width (~8_750ms) that produced the on-device \"8+ second jump\" report",
            ProbeHistory.BUCKET_WIDTH_MS <= 4_000L,
        )
    }

    /**
     * Direct, deterministic reproduction of the failure mode [BUCKET_WIDTH_MS]'s own doc and the
     * test above reference: once bucket width drops to or below the worst-case realistic gap
     * between two real probes, the *ordinary* (post-warm-up, no-exceptions-allowed) grid can show
     * a fabricated 0% miss on a stream where every single probe actually succeeded. This uses an
     * explicit narrow [bucketWidthMs] override (2_000ms, exactly the documented worst-case gap) to
     * demonstrate the mechanism in isolation, independent of whatever the current production width
     * happens to be -- proof the floor in [BUCKET_WIDTH_MS]'s own doc is a real constraint and not
     * just an assumption.
     */
    @Test
    fun `a bucket width at the worst-case realistic gap can fabricate a false miss in the ordinary grid`() {
        val bucketWidthMs = 2_000L // exactly the documented worst-case single-probe gap.
        var history = steadyStateHistory(windowMs = 60_000, bucketWidthMs = bucketWidthMs)
            .recordSuccess(0, latencyMs = 10)
            .recordSuccess(2_001, latencyMs = 10) // one ms past the worst-case gap -- straddles a whole bucket.

        val points = history.successSparkline(bucketWidthMs)

        assertTrue(
            "expected a fabricated 0% miss between the two real (all-success) attempts -- points: " +
                points.map { it.y },
            points.any { it.y == 0f },
        )
    }

    /**
     * The production width's own answer to the reproduction above: spaced at exactly the app's
     * documented worst-case realistic gap (2_000ms, deterministic -- no jitter needed, since
     * production width already clears 2_000ms without relying on jitter margin), repeated across
     * a fully-packed narrowest-configurable window, an all-success stream must show no false miss
     * at the production [ProbeHistory.BUCKET_WIDTH_MS]. Complements the broader jittered sweep in
     * "an all-success session produces no false failure dips at any realistic pacing" above with a
     * focused, non-jittered check pinned exactly at the documented worst case.
     */
    @Test
    fun `the production bucket width shows no false miss at exactly the documented worst-case realistic gap`() {
        val bucketWidthMs = ProbeHistory.BUCKET_WIDTH_MS
        var history = steadyStateHistory(windowMs = ProbeHistory.NARROWEST_WINDOW_MS, bucketWidthMs = bucketWidthMs)
        var t = 0L
        while (t <= ProbeHistory.NARROWEST_WINDOW_MS) {
            history = history.recordSuccess(t, latencyMs = 10)
            t += 2_000L // the documented worst-case gap, back-to-back, deterministically.
        }

        val points = history.successSparkline(bucketWidthMs)

        assertTrue("an all-success stream must show no 0% miss: ${points.map { it.y }}", points.none { it.y == 0f })
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
        // The first real point's x is windowFraction of its own bucket's right edge (t=30_000,
        // exactly where the first real slot ends -- see ProbeHistory.bucketRightEdgeMs's doc),
        // which is exactly the window's own midpoint: 1 - (60_000 - 30_000) / 60_000 = 0.5. Bucket
        // *position* is real-time-based now, not an index fraction, so this is an exact value, not
        // a loose margin -- see successSparkline's "Bucket position is time-based" doc section.
        assertEquals(0.5f, points.first().x, 0.001f)
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
