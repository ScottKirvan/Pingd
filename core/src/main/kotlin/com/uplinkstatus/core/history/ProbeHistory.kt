package com.uplinkstatus.core.history

import kotlin.math.roundToLong

/**
 * One **real** probe attempt — a TCP connect to the target host that actually happened, and
 * either answered or didn't.
 *
 * [latencyMs] is the measured round-trip time when the probe succeeded, and `null` when it
 * failed. `null` is deliberately not "0ms" and not "no sample": a failed attempt is a real
 * data point for the success percentage and a *gap* in the latency line, per the spec's
 * "In-App History Graphs" section. Nothing else may produce one of these — in particular the
 * automatic ("fake") ack of the ping/ping/fake cycle is not a probe attempt and contributes
 * no sample at all, since counting it would inflate the success percentage and invent latency
 * data that was never measured.
 */
data class ProbeSample(
    val timestampMs: Long,
    val latencyMs: Long?,
) {
    val succeeded: Boolean get() = latencyMs != null
}

/**
 * A single point of a rendered sparkline, already reduced to the unit square so the drawing
 * code has no aggregation or scaling decisions left to make: [x] runs 0 (the left edge of the
 * *configured window*, not merely the oldest retained sample -- see
 * [ProbeHistory.windowFraction]) to 1 (the newest retained sample), [y] runs 0 (bottom of the
 * plot) to 1 (top).
 *
 * A `null` [y] is a **gap** — "nothing was measured here" — and must be rendered as a break in
 * the line, never as zero and never by interpolating across it. That's the whole reason this
 * carries a nullable value instead of a plain `Float`.
 */
data class SparklinePoint(
    val x: Float,
    val y: Float?,
)

/**
 * The x-fraction spans (same 0..1 axis as [SparklinePoint.x]) that a sparkline should shade as
 * "no data here" — every run of consecutive gaps in [points] ([SparklinePoint.y] `null`) that
 * sits *between* two measured points, plus a trailing run that reaches all the way to the
 * newest retained point (an outage still in progress as of "now"). A broken line by itself
 * reads as a rendering glitch rather than a deliberate "nothing was measured here" — this is
 * what the shaded region behind it is for.
 *
 * A run at the very start of [points] is deliberately excluded: that's indistinguishable from
 * the window simply not having filled up yet (see [ProbeHistory]'s own "the time axis is never
 * wall-clock now" doc), which already reads correctly as empty space with nothing plotted —
 * shading it would misrepresent ordinary session warm-up as a lost-signal event. For the same
 * reason, a history with *no* measured point at all (every retained attempt failed) produces no
 * spans: there is no boundary anywhere in it to shade from.
 */
fun sparklineGapFractions(points: List<SparklinePoint>): List<ClosedFloatingPointRange<Float>> {
    val spans = mutableListOf<ClosedFloatingPointRange<Float>>()
    var gapStart: Float? = null
    var previousValidX: Float? = null
    points.forEach { point ->
        if (point.y == null) {
            if (gapStart == null) gapStart = previousValidX
        } else {
            gapStart?.let { spans += it..point.x }
            gapStart = null
            previousValidX = point.x
        }
    }
    // A trailing run never reaches the `else` branch above to get its closing point -- close
    // it against the run's own last point instead, which sits at x = 1f by construction (the
    // newest retained point is always the right edge of this axis, gap or not).
    gapStart?.let { start -> spans += start..points.last().x }
    return spans
}

/**
 * The rolling sample history behind the settings screen's two graphs (ping success % and
 * latency), as an immutable value.
 *
 * Immutable on purpose: the samples are produced on the probe worker thread and read by
 * Compose on the main thread, so the `:app` side holds one of these in a `MutableStateFlow`
 * (see `UplinkProbeHistory`) exactly the way `UplinkIconDisplay` holds a drawable id. Every
 * "mutation" here returns a new instance instead, which makes that publication safe without a
 * lock and makes every rule below testable as plain function output.
 *
 * ### Retention is decoupled from the display window
 * [windowMs] is *not* a retention cutoff -- it never decides what gets thrown away. Storage is
 * bounded only by [MAX_SAMPLES] (see its own doc comment for why that cap has headroom for the
 * full 30-minute window at worst-case pacing). [windowMs] is purely a *display* filter, applied
 * at read time by every computed property and sparkline below: the slice of retained [samples]
 * within [windowMs] of the newest one. Narrowing the window therefore only changes what's
 * currently *shown* -- the rest stays retained underneath, so widening it back afterward
 * reveals the same older data again rather than having genuinely lost it (see [withWindowMs]).
 * The whole history is still discarded wholesale by an explicit action -- today only the user's
 * manual reset ([cleared]) and the [MAX_SAMPLES] cap itself -- but a window-slider edit alone
 * is deliberately not one of those actions any more.
 *
 * ### The time axis is never wall-clock "now"
 * The percentage and the average are computed over whatever the display filter above selects,
 * whose span is at most [windowMs] and, early in a session, much less. Both sparklines' `x`
 * axis is different on purpose: it's scaled to the full configured [windowMs], anchored to the
 * newest retained sample, not stretched to fill from whatever is currently displayed -- see
 * [windowFraction]'s own doc for why that distinction matters.
 *
 * This class therefore never reads a clock. That's what keeps it a pure, deterministic
 * function of the samples it was given, but it also has a real consequence worth stating: if
 * nothing records a sample for a while, the display filter's anchor (the newest retained
 * sample) doesn't move either, so the history stays as it was at the last attempt rather than
 * draining to empty. That is deliberate — it's the same freeze-in-place honesty the tracer
 * itself uses for a failed probe, it keeps the graphs readable across a transition instead of
 * blanking them, and the caller labels what is shown with the span it actually covers rather
 * than claiming the full window. Being computed at read time rather than pruned on write, this
 * is self-correcting the moment a new sample arrives, with no special-casing needed for "the
 * first sample after a gap." In practice extended gaps only happen while the whole app is
 * switched off (the master toggle) — `UplinkStatusService` keeps a throttled probe running even
 * while the visible tracer is paused for being out of network scope, specifically so an
 * out-of-scope period doesn't go blind here — and [recordMarker] gives the genuinely-off case
 * its own visible marker rather than leaving it indistinguishable from an ordinary gap in the
 * data.
 *
 * ### Session lifetime
 * There is no save/restore path here, by design and for the same reason
 * [com.uplinkstatus.core.tracer.AckTracer] has none: per spec the sample history is
 * session-only, so the only honest way to guarantee that is to have nothing capable of
 * persisting it.
 */
data class ProbeHistory(
    val windowMs: Long = DEFAULT_WINDOW_MS,
    val samples: List<ProbeSample> = emptyList(),
    /** Timestamps of master-toggle transitions (the whole app switched off, or back on) that
     * happened while retained -- see [recordMarker]. These are not probe attempts and never
     * affect [successPercent]/[averageLatencyMs]/either sparkline's data; they exist purely so
     * the UI can draw a vertical break where one happened, distinguishing "the app was off, we
     * simply don't know" from a real measured outage. Not pruned by [windowMs] -- like
     * [samples], retention is decoupled from the display window (see the class doc); a marker
     * outside the currently configured window is simply filtered out of [markerFractions] at
     * read time instead. Dropped by [cleared] right along with the samples -- the user's
     * explicit reset means a clean slate, and a stale marker from before it would misdescribe
     * what's on screen afterward just as much as a stale sample would. */
    val markers: List<Long> = emptyList(),
) {

    init {
        require(windowMs > 0) { "windowMs must be positive, was $windowMs" }
    }

    /**
     * The slice of [samples] currently *displayed*: everything within [windowMs] of the newest
     * retained sample. This is the read-time display filter the class doc describes -- every
     * computed property and sparkline below reads this instead of [samples] directly, so
     * narrowing/widening [windowMs] only ever changes what this slice selects, never what
     * [samples] itself holds. Mirrors the edge-inclusive cutoff the old write-time pruning used
     * (a sample exactly [windowMs] old is kept, one millisecond older is not), just computed
     * fresh each read instead of baked into storage.
     */
    private val windowedSamples: List<ProbeSample>
        get() {
            if (samples.isEmpty()) return samples
            val cutoff = samples.last().timestampMs - windowMs
            val firstShown = samples.indexOfFirst { it.timestampMs >= cutoff }
            return if (firstShown <= 0) samples else samples.subList(firstShown, samples.size)
        }

    /** Real probe attempts within the currently displayed window, successes and failures alike. */
    val attemptCount: Int get() = windowedSamples.size

    val successCount: Int get() = windowedSamples.count { it.succeeded }

    /**
     * Percentage (0..100) of displayed real probe attempts that succeeded, or `null` when
     * nothing has been measured yet — `null`, not `0`, because "no probes yet" and "every
     * probe failed" are completely different states and only one of them is bad news.
     */
    val successPercent: Float?
        get() {
            val windowed = windowedSamples
            return if (windowed.isEmpty()) null else windowed.count { it.succeeded } * 100f / windowed.size
        }

    /**
     * Mean round-trip time of the displayed *successful* probes, rounded to whole milliseconds,
     * or `null` if none succeeded. Failed attempts are excluded rather than counted as zero —
     * a timeout is an absence of a measurement, not a fast one.
     */
    val averageLatencyMs: Long?
        get() {
            val latencies = windowedSamples.mapNotNull { it.latencyMs }
            if (latencies.isEmpty()) return null
            return (latencies.sum().toDouble() / latencies.size).roundToLong()
        }

    /** The most recent successful probe's latency within the displayed window, or `null` if
     * none succeeded. */
    val latestLatencyMs: Long? get() = windowedSamples.lastOrNull { it.succeeded }?.latencyMs

    /** How much time the displayed samples actually cover: at most [windowMs], `0` while there
     * are fewer than two of them (a single sample spans no time at all). */
    val spanMs: Long
        get() {
            val windowed = windowedSamples
            return if (windowed.size < 2) 0L else windowed.last().timestampMs - windowed.first().timestampMs
        }

    /** Records a real probe that answered in [latencyMs]. [timestampMs] is expected to be no
     * earlier than the newest existing sample — the cycle feeds these in the order they happen.
     * Storage is capped at [MAX_SAMPLES] (oldest evicted first); [windowMs] plays no part in
     * what gets kept, only in what [windowedSamples] later shows. */
    fun recordSuccess(timestampMs: Long, latencyMs: Long): ProbeHistory {
        require(latencyMs >= 0) { "latencyMs must be non-negative, was $latencyMs" }
        return appended(ProbeSample(timestampMs, latencyMs))
    }

    /** Records a real probe attempt that failed. Called for *every* failed attempt, including
     * every retry of a sustained outage — they are exactly the attempts the success percentage
     * exists to reflect. */
    fun recordFailure(timestampMs: Long): ProbeHistory = appended(ProbeSample(timestampMs, latencyMs = null))

    /** Records a master-toggle transition (the whole app switched off, or back on) — see
     * [markers]. [timestampMs] is expected to be no earlier than the newest existing sample or
     * marker, same ordering contract as [recordSuccess]/[recordFailure]. */
    fun recordMarker(timestampMs: Long): ProbeHistory = copy(markers = markers + timestampMs)

    /** Same samples and markers under a new display window — a cheap metadata update, since
     * [windowMs] no longer governs what's retained (see the class doc). Narrowing changes only
     * what [windowedSamples] currently selects to show; nothing stored is discarded, so widening
     * back afterward reveals the same older data again rather than it having been thrown away. */
    fun withWindowMs(windowMs: Long): ProbeHistory = copy(windowMs = windowMs)

    /** Drops every sample and marker, keeping the window — the user's explicit "reset history"
     * action. */
    fun cleared(): ProbeHistory = copy(samples = emptyList(), markers = emptyList())

    /**
     * Where a timestamp falls on the axis both sparklines plot against: 0 is the left edge of
     * the *configured window* — [windowMs] before [newest] — and 1 is [newest] itself, anchored
     * to the right edge regardless of how much of the window actually has data in it yet.
     *
     * This is the whole reason the axis is scaled by [windowMs] and not by [spanMs]: scaling to
     * the displayed span would stretch however little data exists so far to fill the entire
     * width, which reads as the graph having just reset every time it's sparse (right after a
     * reset, early in a session, or just after narrowing the window) even though nothing was
     * actually cleared. Scaling to the window instead means a handful of recent samples sit
     * clustered near the right edge with real empty space to their left, and the graph fills in
     * and starts scrolling only once the window is genuinely full — the same behavior a strip
     * chart or oscilloscope trace has, and the only one that doesn't misrepresent "not much time
     * has passed" as "everything just started over."
     */
    private fun windowFraction(timestampMs: Long, newest: Long): Float =
        1f - (newest - timestampMs).toFloat() / windowMs

    /**
     * Where each [markers] timestamp falls on the same window-anchored axis [latencySparkline]
     * and [successSparkline] plot against (see [windowFraction]), so the UI can draw a vertical
     * break at exactly the right point with no scaling decision of its own left to make.
     *
     * [markers] is not pruned by [windowMs] any more than [samples] is (see the class doc), so
     * this filter is the thing that actually keeps an out-of-window marker off the axis now,
     * rather than a defensive check on data that was already pruned before it got here: a
     * marker outside the window relative to the newest retained sample contributes nothing —
     * there is no meaningful position for it on an axis that doesn't reach that far.
     */
    fun markerFractions(): List<Float> {
        if (markers.isEmpty() || samples.isEmpty()) return emptyList()
        val newest = samples.last().timestampMs
        return markers.mapNotNull { marker ->
            windowFraction(marker, newest).takeIf { it in 0f..1f }
        }
    }

    /**
     * The latency trend, one point per *displayed* sample (see [windowedSamples]):
     * [SparklinePoint.y] is the sample's latency scaled against the displayed min/max, and
     * `null` for a failed probe. [SparklinePoint.x] is [windowFraction] — see its doc for why
     * the axis is anchored to the configured window rather than stretched to fill from whatever
     * span is currently displayed.
     *
     * One point per *sample*, never aggregated, precisely so a failure stays a visible gap
     * exactly where it happened. When every displayed latency is identical (or only one
     * succeeded) there is no range to scale against, so those points sit on the middle line
     * rather than being pinned to an arbitrary edge.
     */
    fun latencySparkline(): List<SparklinePoint> {
        val windowed = windowedSamples
        if (windowed.isEmpty()) return emptyList()
        val latencies = windowed.mapNotNull { it.latencyMs }
        val min = latencies.minOrNull()
        val max = latencies.maxOrNull()
        val newest = windowed.last().timestampMs
        return windowed.map { sample ->
            val x = windowFraction(sample.timestampMs, newest)
            val y = sample.latencyMs?.let { latency ->
                if (min == null || max == null || max == min) {
                    FLAT_LINE_Y
                } else {
                    (latency - min).toFloat() / (max - min)
                }
            }
            SparklinePoint(x = x, y = y)
        }
    }

    /**
     * The success-rate trend: the *configured window* — not just the currently retained span,
     * see [windowFraction] — cut into equal time buckets, each carrying the fraction of that
     * bucket's attempts that succeeded (0..1 — an absolute scale, not autoscaled, since a
     * percentage means something on its own). A bucket with no attempts in it is a gap, same
     * rule as the latency line — which is exactly what every bucket earlier than the data
     * actually reaches renders as, early in a session or just after a reset.
     *
     * Bucketed rather than one point per sample because a per-sample success line can only
     * ever be 0 or 1 — a square wave that says nothing about the *rate*, which is the whole
     * point of this graph. The resolution grows with real elapsed time instead of being fixed
     * or tied to how many attempts have arrived — see [bucketCount]'s own doc for why that
     * distinction matters.
     */
    fun successSparkline(maxBuckets: Int = DEFAULT_MAX_BUCKETS): List<SparklinePoint> {
        require(maxBuckets > 0) { "maxBuckets must be positive, was $maxBuckets" }
        val windowed = windowedSamples
        if (windowed.isEmpty()) return emptyList()

        val buckets = bucketCount(maxBuckets)
        val attempts = IntArray(buckets)
        val successes = IntArray(buckets)
        val newest = windowed.last().timestampMs

        windowed.forEach { sample ->
            // coerceIn also covers buckets == 1: every fraction lands in the only bucket,
            // index 0. windowFraction never divides by zero (windowMs is always positive),
            // unlike the old span-based version this replaced.
            val fraction = windowFraction(sample.timestampMs, newest)
            val index = (fraction * buckets).toInt().coerceIn(0, buckets - 1)
            attempts[index]++
            if (sample.succeeded) successes[index]++
        }

        return (0 until buckets).map { index ->
            val x = if (buckets == 1) 1f else index.toFloat() / (buckets - 1)
            val y = if (attempts[index] == 0) null else successes[index].toFloat() / attempts[index]
            SparklinePoint(x = x, y = y)
        }
    }

    /**
     * How many time buckets [successSparkline] divides the window into: grows with how much of
     * the window real elapsed time has actually covered ([spanMs] relative to [windowMs]), not
     * with how many attempts have been recorded.
     *
     * The earlier version of this scaled with `samples.size` instead — tying it to attempt
     * *count* rather than *elapsed time* meant the bucket count (and therefore every bucket's
     * boundaries) changed on essentially every single recorded sample, since retained count
     * fluctuates with pacing and failure-retry bursts even when almost no real time has passed.
     * A bucket already drawn on screen would silently get recomputed from a different slice of
     * samples a moment later — visible on-device as the newest end of the line "bouncing" and
     * already-plotted dips readjusting with each new sample. Elapsed time only moves forward
     * (and, once the window is genuinely full, stops changing this at all — see [spanMs]), so
     * boundaries here are stable between any two samples taken close together, exactly the cases
     * that used to reshuffle.
     */
    private fun bucketCount(maxBuckets: Int): Int {
        val coveredFraction = (spanMs.toFloat() / windowMs).coerceIn(0f, 1f)
        return (coveredFraction * maxBuckets).toInt().coerceIn(1, maxBuckets)
    }

    private fun appended(sample: ProbeSample): ProbeHistory = copy(samples = cappedSamples(samples + sample))

    companion object {
        /** Drops the oldest samples once [all] exceeds [MAX_SAMPLES] -- the only thing that
         * bounds storage now that retention no longer follows [windowMs] (see the class doc).
         * Relies on [all] being in timestamp order, which is how the cycle produces them. */
        private fun cappedSamples(all: List<ProbeSample>): List<ProbeSample> {
            val overflow = all.size - MAX_SAMPLES
            return if (overflow > 0) all.subList(overflow, all.size).toList() else all
        }

        /** Default retention window, per spec (and matching the Starlink status display this
         * is modeled on). */
        const val DEFAULT_WINDOW_MS: Long = 7 * 60 * 1000L

        /**
         * Hard cap on retained samples -- the sole bound on storage, now that [windowMs] is a
         * display-only filter and no longer prunes anything (see the class doc).
         *
         * Sized for the *fastest realistic production rate*, not the default steady-state one.
         * A successful probe is naturally paced by the step-delay setting, and a **failed**
         * probe is now floored at a small fixed retry delay too (250ms, added after this cap
         * was first sized -- see `ProbeCycleRunner.FAILURE_RETRY_DELAY_MS`'s doc, specifically
         * to reduce this same burst's rate and battery cost) -- but a DNS-resolution failure
         * specifically (the exact condition a device sees for a moment while reconnecting
         * after a total outage, before its resolver is reachable again) can still return in
         * low single-digit milliseconds, faster than the floor governs the *steady* case. A
         * burst of those during precisely that reconnect window can still produce far more
         * samples per second than ordinary pacing -- confirmed on-device, before the floor
         * existed: `attemptCount` was observed pinned at the *previous* cap (4096) after well
         * under ten minutes of mixed normal use and reconnect testing, far faster than
         * steady-state pacing alone explains.
         * Against a cap sized only for steady state, a burst like that doesn't just shorten the
         * graph (the honest, intended behavior when the cap bites during ordinary free-wheeling
         * use) -- it can evict an entire window's worth of prior good data in a couple of
         * seconds, which reads exactly like the history being cleared even though nothing ever
         * called [cleared].
         *
         * This value is chosen so that reproducing the same mixed-use rate observed above for
         * the *entire* widest configurable window (30 minutes) still would not reach it, leaving
         * comfortable headroom beyond that for a genuine failure burst without wiping out
         * everything before it. Each retained sample is two `Long`s plus small object overhead,
         * so this costs a few hundred KB at worst, not a memory concern on a phone.
         *
         * When the cap does still bite (a burst sustained far longer than a reconnect blip), the
         * oldest samples go first, so the graphs cover a shorter span than the window asks for;
         * the caption reports the span actually covered rather than the window, so this shortens
         * what is shown without misdescribing it.
         */
        const val MAX_SAMPLES: Int = 20_000

        /** Upper bound on [successSparkline]'s bucket count — enough resolution for a card-sized
         * sparkline without plotting points finer than the eye can separate. */
        const val DEFAULT_MAX_BUCKETS: Int = 48

        /** Where a latency point sits when there is no range to scale it against. */
        const val FLAT_LINE_Y: Float = 0.5f
    }
}
