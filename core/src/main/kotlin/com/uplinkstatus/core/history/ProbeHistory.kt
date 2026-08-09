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
 * The rolling sample history behind the settings screen's two graphs (ping success % and
 * latency), as an immutable value.
 *
 * Immutable on purpose: the samples are produced on the probe worker thread and read by
 * Compose on the main thread, so the `:app` side holds one of these in a `MutableStateFlow`
 * (see `UplinkProbeHistory`) exactly the way `UplinkIconDisplay` holds a drawable id. Every
 * "mutation" here returns a new instance instead, which makes that publication safe without a
 * lock and makes every rule below testable as plain function output.
 *
 * ### The time axis is never wall-clock "now"
 * [windowMs] is a *maximum* retention span: recording a sample drops anything older than
 * [windowMs] before it. The percentage and the average are computed over whatever is actually
 * retained, whose span is at most [windowMs] and, early in a session, much less. Both
 * sparklines' `x` axis is different on purpose: it's scaled to the full configured [windowMs],
 * anchored to the newest retained sample, not stretched to fill from whatever is currently
 * retained -- see [windowFraction]'s own doc for why that distinction matters.
 *
 * This class therefore never reads a clock. That's what keeps it a pure, deterministic
 * function of the samples it was given, but it also has a real consequence worth stating: if
 * nothing records a sample for a while, nothing prunes, and the history stays as it was at the
 * last attempt rather than draining to empty. That is deliberate — it's the same freeze-in-place
 * honesty the tracer itself uses for a failed probe, it keeps the graphs readable across a
 * transition instead of blanking them, and the caller labels what is shown with the span it
 * actually covers rather than claiming the full window. The first sample after the gap prunes
 * everything the window has outlived, so it is self-correcting. In practice this only happens
 * while the whole app is switched off (the master toggle) — `UplinkStatusService` keeps a
 * throttled probe running even while the visible tracer is paused for being out of network
 * scope, specifically so an out-of-scope period doesn't go blind here — and [recordMarker] gives
 * the genuinely-off case its own visible marker rather than leaving it indistinguishable from an
 * ordinary gap in the data.
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
     * simply don't know" from a real measured outage. Pruned by the same [windowMs] as
     * [samples], and dropped by [cleared] right along with them -- the user's explicit reset
     * means a clean slate, and a stale marker from before it would misdescribe what's on screen
     * afterward just as much as a stale sample would. */
    val markers: List<Long> = emptyList(),
) {

    init {
        require(windowMs > 0) { "windowMs must be positive, was $windowMs" }
    }

    /** Every real probe attempt retained, successes and failures alike. */
    val attemptCount: Int get() = samples.size

    val successCount: Int get() = samples.count { it.succeeded }

    /**
     * Percentage (0..100) of retained real probe attempts that succeeded, or `null` when
     * nothing has been measured yet — `null`, not `0`, because "no probes yet" and "every
     * probe failed" are completely different states and only one of them is bad news.
     */
    val successPercent: Float?
        get() = if (samples.isEmpty()) null else successCount * 100f / samples.size

    /**
     * Mean round-trip time of the retained *successful* probes, rounded to whole milliseconds,
     * or `null` if none succeeded. Failed attempts are excluded rather than counted as zero —
     * a timeout is an absence of a measurement, not a fast one.
     */
    val averageLatencyMs: Long?
        get() {
            val latencies = samples.mapNotNull { it.latencyMs }
            if (latencies.isEmpty()) return null
            return (latencies.sum().toDouble() / latencies.size).roundToLong()
        }

    /** The most recent successful probe's latency, or `null` if none succeeded. */
    val latestLatencyMs: Long? get() = samples.lastOrNull { it.succeeded }?.latencyMs

    /** How much time the retained samples actually cover: at most [windowMs], `0` while there
     * are fewer than two of them (a single sample spans no time at all). */
    val spanMs: Long
        get() = if (samples.size < 2) 0L else samples.last().timestampMs - samples.first().timestampMs

    /** Records a real probe that answered in [latencyMs], pruning anything the window has
     * outlived. [timestampMs] is expected to be no earlier than the newest existing sample —
     * the cycle feeds these in the order they happen. */
    fun recordSuccess(timestampMs: Long, latencyMs: Long): ProbeHistory {
        require(latencyMs >= 0) { "latencyMs must be non-negative, was $latencyMs" }
        return appended(ProbeSample(timestampMs, latencyMs))
    }

    /** Records a real probe attempt that failed. Called for *every* failed attempt, including
     * the back-to-back immediate retries of a sustained outage — they are exactly the attempts
     * the success percentage exists to reflect. */
    fun recordFailure(timestampMs: Long): ProbeHistory = appended(ProbeSample(timestampMs, latencyMs = null))

    /** Records a master-toggle transition (the whole app switched off, or back on) — see
     * [markers]. [timestampMs] is expected to be no earlier than the newest existing sample or
     * marker, same ordering contract as [recordSuccess]/[recordFailure]. */
    fun recordMarker(timestampMs: Long): ProbeHistory {
        val newest = maxOf(timestampMs, samples.lastOrNull()?.timestampMs ?: timestampMs, markers.lastOrNull() ?: timestampMs)
        val cutoff = newest - windowMs
        return copy(
            samples = prunedSamples(samples, cutoff),
            markers = prunedMarkers(markers + timestampMs, cutoff),
        )
    }

    /** Same samples and markers under a new retention window, immediately pruned to it — so
     * shortening the window takes effect at once rather than at the next probe. */
    fun withWindowMs(windowMs: Long): ProbeHistory {
        val newest = maxOf(samples.lastOrNull()?.timestampMs ?: Long.MIN_VALUE, markers.lastOrNull() ?: Long.MIN_VALUE)
        if (newest == Long.MIN_VALUE) return copy(windowMs = windowMs)
        val cutoff = newest - windowMs
        return ProbeHistory(
            windowMs = windowMs,
            samples = prunedSamples(samples, cutoff),
            markers = prunedMarkers(markers, cutoff),
        )
    }

    /** Drops every sample and marker, keeping the window — the user's explicit "reset history"
     * action. */
    fun cleared(): ProbeHistory = copy(samples = emptyList(), markers = emptyList())

    /**
     * Where a timestamp falls on the axis both sparklines plot against: 0 is the left edge of
     * the *configured window* — [windowMs] before [newest] — and 1 is [newest] itself, anchored
     * to the right edge regardless of how much of the window actually has data in it yet.
     *
     * This is the whole reason the axis is scaled by [windowMs] and not by [spanMs]: scaling to
     * the retained span would stretch however little data exists so far to fill the entire
     * width, which reads as the graph having just reset every time it's sparse (right after a
     * reset, early in a session, or just after a gap prunes old data) even though nothing was
     * actually cleared. Scaling to the window instead means a handful of recent samples sit
     * clustered near the right edge with real empty space to their left, and the graph fills in
     * and starts scrolling only once the window is genuinely full — the same behavior a strip
     * chart or oscilloscope trace has, and the only one that doesn't misrepresent "not much time
     * has passed" as "everything just started over."
     */
    private fun windowFraction(timestampMs: Long, newest: Long): Float =
        1f - (newest - timestampMs).toFloat() / windowMs

    /**
     * Where each retained [markers] timestamp falls on the same window-anchored axis
     * [latencySparkline] and [successSparkline] plot against (see [windowFraction]), so the UI
     * can draw a vertical break at exactly the right point with no scaling decision of its own
     * left to make.
     *
     * A marker outside the window relative to the newest retained sample (effectively
     * unreachable in practice, since both are pruned by the same window) contributes nothing:
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
     * The latency trend, one point per retained sample: [SparklinePoint.y] is the sample's
     * latency scaled against the retained min/max, and `null` for a failed probe. [SparklinePoint.x]
     * is [windowFraction] — see its doc for why the axis is anchored to the configured window
     * rather than stretched to fill from whatever span is currently retained.
     *
     * One point per *sample*, never aggregated, precisely so a failure stays a visible gap
     * exactly where it happened. When every retained latency is identical (or only one
     * succeeded) there is no range to scale against, so those points sit on the middle line
     * rather than being pinned to an arbitrary edge.
     */
    fun latencySparkline(): List<SparklinePoint> {
        if (samples.isEmpty()) return emptyList()
        val latencies = samples.mapNotNull { it.latencyMs }
        val min = latencies.minOrNull()
        val max = latencies.maxOrNull()
        val newest = samples.last().timestampMs
        return samples.map { sample ->
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
     * point of this graph. The resolution grows with the data instead of being fixed:
     * [maxBuckets] is an upper bound, and the actual count is whatever keeps at least
     * [MIN_SAMPLES_PER_BUCKET] attempts behind each plotted point, so an early session shows a
     * few honest points instead of a long dotted line of one-sample buckets.
     */
    fun successSparkline(maxBuckets: Int = DEFAULT_MAX_BUCKETS): List<SparklinePoint> {
        require(maxBuckets > 0) { "maxBuckets must be positive, was $maxBuckets" }
        if (samples.isEmpty()) return emptyList()

        val buckets = (samples.size / MIN_SAMPLES_PER_BUCKET).coerceIn(1, maxBuckets)
        val attempts = IntArray(buckets)
        val successes = IntArray(buckets)
        val newest = samples.last().timestampMs

        samples.forEach { sample ->
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

    private fun appended(sample: ProbeSample): ProbeHistory {
        val cutoff = sample.timestampMs - windowMs
        return copy(
            samples = prunedSamples(samples + sample, cutoff),
            markers = prunedMarkers(markers, cutoff),
        )
    }

    companion object {
        /** Drops everything older than [cutoff], then anything still over [MAX_SAMPLES]. Relies
         * on [all] being in timestamp order, which is how the cycle produces them. */
        private fun prunedSamples(all: List<ProbeSample>, cutoff: Long): List<ProbeSample> {
            if (all.isEmpty()) return all
            var firstKept = all.indexOfFirst { it.timestampMs >= cutoff }
            if (firstKept < 0) firstKept = all.size
            val overflow = (all.size - firstKept) - MAX_SAMPLES
            if (overflow > 0) firstKept += overflow
            return if (firstKept == 0) all else all.subList(firstKept, all.size).toList()
        }

        /** Same rule as [prunedSamples], minus the [MAX_SAMPLES] cap -- markers are rare,
         * user-driven events (a master-toggle flip), not one-per-probe, so there's nothing here
         * that could grow unbounded the way free-wheeling pacing can for samples. */
        private fun prunedMarkers(all: List<Long>, cutoff: Long): List<Long> {
            if (all.isEmpty()) return all
            var firstKept = all.indexOfFirst { it >= cutoff }
            if (firstKept < 0) firstKept = all.size
            return if (firstKept == 0) all else all.subList(firstKept, all.size).toList()
        }

        /** Default retention window, per spec (and matching the Starlink status display this
         * is modeled on). */
        const val DEFAULT_WINDOW_MS: Long = 7 * 60 * 1000L

        /**
         * Hard cap on retained samples, independent of [windowMs].
         *
         * Sized for the *fastest realistic production rate*, not the default steady-state one.
         * A successful probe is naturally paced by the step-delay setting, but a **failed**
         * probe retries immediately with no back-off at all, per spec -- and a DNS-resolution
         * failure specifically (the exact condition a device sees for a moment while
         * reconnecting after a total outage, before its resolver is reachable again) can return
         * in low single-digit milliseconds, nowhere near the full connect timeout a generic
         * failure waits out. A burst of those during precisely that reconnect window can produce
         * thousands of samples in a couple of real seconds -- confirmed on-device: `attemptCount`
         * was observed pinned at the *previous* cap (4096) after well under ten minutes of mixed
         * normal use and reconnect testing, far faster than steady-state pacing alone explains.
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

        /** Minimum attempts behind each plotted success-rate point (see [successSparkline]). */
        const val MIN_SAMPLES_PER_BUCKET: Int = 4

        /** Where a latency point sits when there is no range to scale it against. */
        const val FLAT_LINE_Y: Float = 0.5f
    }
}
