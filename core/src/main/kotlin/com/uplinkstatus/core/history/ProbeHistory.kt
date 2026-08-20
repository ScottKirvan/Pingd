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
 * carries a nullable value instead of a plain `Float`. [ProbeHistory.latencySparkline] is the
 * only one of the two sparklines that still produces this: a failed probe there really is an
 * absence of measurement. [ProbeHistory.successSparkline] never emits a `null` [y] any more — a
 * bucket with no real attempts collapses straight into a `0f` miss (see its own doc) — so a
 * `null` point simply doesn't occur in that sparkline's output at all.
 */
data class SparklinePoint(
    val x: Float,
    val y: Float?,
    /** The raw measured latency (ms) behind this point's [y], carried through separately even
     * though [ProbeHistory.latencySparkline] now derives [y] itself from this same value via
     * [latencyColorFraction] — it's what lets the latency graph's line coloring
     * ([latencyColorFraction]) stay pure ms→fraction arithmetic with no notion of a plotted
     * position at all. Always `null` on [ProbeHistory.successSparkline] points, which have no
     * latency of their own; `null` here exactly when [y] is `null` on the latency sparkline (a
     * failed probe has neither a position nor a color to give). This is the only field on this
     * class that isn't itself display-ready — the ms→`Color` mapping is an `:app`-side,
     * Compose-specific concern this pure-Kotlin module has no dependency on; see
     * [latencyColorFraction] for the pure, unit-tested part of that mapping.
     */
    val latencyMs: Long? = null,
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

/** Anchor latencies (ms) for [latencyColorFraction]'s green→yellow→red scale — Starlink-style
 * "good/warning/bad" bands, not tied to any particular network technology. */
private const val LATENCY_COLOR_GREEN_MS = 50L
private const val LATENCY_COLOR_YELLOW_MS = 200L
private const val LATENCY_COLOR_RED_MS = 400L

/**
 * How far [latencyMs] sits toward "slow" on the latency graph's **absolute** green→yellow→red
 * color scale. This is also the fixed scale [ProbeHistory.latencySparkline] plots
 * [SparklinePoint.y] against (as `1f - latencyColorFraction(latencyMs)`), so a point's vertical
 * position and its line color always agree — see that function's own doc. Returns `0f` at or
 * below [LATENCY_COLOR_GREEN_MS] (fully green), `0.5f` at [LATENCY_COLOR_YELLOW_MS] (fully
 * yellow), `1f` at or above [LATENCY_COLOR_RED_MS] (fully red), clamped beyond either end and
 * linearly interpolated between adjacent anchors otherwise.
 *
 * Pure arithmetic, with no notion of `Color` at all — turning this fraction into an actual
 * on-screen color (picking the green/yellow/red endpoints and interpolating between them) is an
 * `:app`-side, Compose-specific concern this pure-Kotlin module has no dependency on. Keeping the
 * threshold math here, separate from that, is what makes it unit-testable without Robolectric or
 * a Canvas.
 */
fun latencyColorFraction(latencyMs: Long): Float = when {
    latencyMs <= LATENCY_COLOR_GREEN_MS -> 0f
    latencyMs >= LATENCY_COLOR_RED_MS -> 1f
    latencyMs <= LATENCY_COLOR_YELLOW_MS ->
        0.5f * (latencyMs - LATENCY_COLOR_GREEN_MS).toFloat() /
            (LATENCY_COLOR_YELLOW_MS - LATENCY_COLOR_GREEN_MS)
    else ->
        0.5f + 0.5f * (latencyMs - LATENCY_COLOR_YELLOW_MS).toFloat() /
            (LATENCY_COLOR_RED_MS - LATENCY_COLOR_YELLOW_MS)
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
     * are fewer than two of them (a single sample spans no time at all).
     *
     * This can be, and for real discretely-sampled data almost always is, strictly *less* than
     * [windowMs] even once the window is genuinely, thoroughly full — see [isWindowFull]'s doc
     * for why that gap is expected rather than a sign the window "isn't full yet." Comparing this
     * property against [windowMs] is *not* a reliable way to detect fullness; [isWindowFull] is
     * the signal for that. */
    val spanMs: Long
        get() {
            val windowed = windowedSamples
            return if (windowed.size < 2) 0L else windowed.last().timestampMs - windowed.first().timestampMs
        }

    /**
     * Whether the configured [windowMs] is genuinely, thoroughly full of real data: `true` only
     * when real retained history ([samples]) actually extends *past* what [windowedSamples] is
     * currently showing — i.e. the display filter is excluding older real attempts specifically
     * because they've aged out of [windowMs], not merely because no older data exists yet. That's
     * a factual, discrete yes/no fact about the data (did the filter actually cut something off?)
     * rather than a numeric coincidence, computed as `samples.size > windowedSamples.size`: any
     * exclusion at all means real retained history reaches back further than what's displayed,
     * which is exactly what "the window is full" means.
     *
     * This exists because the tempting alternative — comparing [spanMs] to [windowMs] for
     * near-equality — is fundamentally unsound for real, discretely-sampled data. [spanMs] is *at
     * most* [windowMs] by construction (it's derived from the same window-filtered slice), and
     * reaches it *exactly* only when a real sample happens to land precisely on the display
     * cutoff instant. Probes arrive at discrete intervals (paced by the step-delay setting,
     * jittered by real network timing), so landing exactly on that instant essentially never
     * happens in practice — a `spanMs >= windowMs` check stayed false forever even once the
     * window had been thoroughly full for a long time. That was confirmed as the root cause of an
     * on-device report where `historySpanCaption` (`:app`) never credited the configured window
     * duration at all: a 1-minute window reported "last 59 seconds," a 4-minute window reported
     * "last 3 mins," in both cases falling back to the true-but-truncated [spanMs] forever,
     * because the nearest surviving sample above the cutoff almost never sits *exactly* on it.
     * Whether the filter actually excluded something has no such coincidence dependency, which is
     * what makes it the correct signal to use instead.
     */
    val isWindowFull: Boolean get() = samples.size > windowedSamples.size

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
     * [SparklinePoint.y] is the sample's latency plotted on a **fixed, absolute** scale —
     * **fast plots high, slow plots low** — and `null` for a failed probe. [SparklinePoint.x] is
     * [windowFraction] — see its doc for why the axis is anchored to the configured window
     * rather than stretched to fill from whatever span is currently displayed.
     *
     * The scale is deliberately *not* relative to whatever latencies happened to occur this
     * session: the same latency value always plots at the same vertical position, session to
     * session and history to history, so the height of a point is itself meaningful rather than
     * only meaningful relative to the rest of the currently displayed data. Concretely, `y` is
     * derived straight from [latencyColorFraction] — the same fixed green→yellow→red anchors
     * ([LATENCY_COLOR_GREEN_MS]/[LATENCY_COLOR_YELLOW_MS]/[LATENCY_COLOR_RED_MS]) already used to
     * *color* this graph's line — as `1f - latencyColorFraction(latencyMs)`, so a point's
     * position and its color always agree: the reddest point is always the lowest one. That
     * reuses [latencyColorFraction]'s own clamping too, so a latency at or beyond
     * [LATENCY_COLOR_RED_MS] plots pinned at the bottom (`y = 0f`) rather than off-canvas or
     * needing a special case here, exactly the way one at or below [LATENCY_COLOR_GREEN_MS]
     * plots pinned at the top (`y = 1f`).
     *
     * One point per *sample*, never aggregated, precisely so a failure stays a visible gap
     * exactly where it happened.
     */
    fun latencySparkline(): List<SparklinePoint> {
        val windowed = windowedSamples
        if (windowed.isEmpty()) return emptyList()
        val newest = windowed.last().timestampMs
        return windowed.map { sample ->
            val x = windowFraction(sample.timestampMs, newest)
            val y = sample.latencyMs?.let { latency -> 1f - latencyColorFraction(latency) }
            SparklinePoint(x = x, y = y, latencyMs = sample.latencyMs)
        }
    }

    /**
     * The success-rate trend: the *configured window* — not just the currently retained span,
     * see [windowFraction] — cut into fixed-width, absolute-time buckets, each carrying the
     * fraction of that bucket's attempts that succeeded (0..1 — an absolute scale, not
     * autoscaled, since a percentage means something on its own).
     *
     * Bucketed rather than one point per sample because a per-sample success line can only
     * ever be 0 or 1 — a square wave that says nothing about the *rate*, which is the whole
     * point of this graph.
     *
     * ### Buckets are fixed, absolute slots of wall-clock time — not fractions of "now"
     * A sample's bucket is a plain integer slot number in absolute time, permanently fixed for
     * that sample's timestamp (see [bucketSlot]). This replaced an earlier version that instead
     * measured each sample's position as a *fraction of distance from `newest`* ([windowFraction])
     * and multiplied that fraction by the bucket count to get an index. That scheme's boundaries
     * were, in effect, anchored to `newest` — which advances on every single new sample — so a
     * sample sitting near a boundary could flip from one bucket to its neighbor on almost any
     * tick purely because "now" moved forward slightly, with nothing about the connection
     * actually changing. Since each bucket's displayed value is a discrete average over a
     * handful of real samples, one sample migrating in or out could swing that bucket's
     * percentage sharply and instantly — on-device this showed up as the whole line "reshaping"
     * on every tick instead of scrolling, worse at narrow windows and slow ping-pacing settings
     * (fewer samples per bucket, so migration hits harder) and confirmed by porting the exact
     * old algorithm into a standalone simulation: single-tick swings of tens of percentage
     * points in a bucket that had nothing real change about it, up to 100 points at
     * narrow-window/slow-pacing settings. Binning by an absolute slot number instead means a
     * bucket's membership is a pure function of *which real samples exist*, never of when the
     * line happens to be redrawn — a closed bucket's value is provably frozen once no more
     * samples can land in it (also confirmed by simulation: a swept range of window sizes,
     * pacing intervals, and non-round window/bucket-count combinations produced zero value
     * changes in any interior, already-closed bucket).
     *
     * ### Bucket width is a true constant — never a function of the configured window
     * [bucketWidthMs] defaults to [BUCKET_WIDTH_MS], a fixed constant anchored at
     * [DEFAULT_WINDOW_MS] / [DEFAULT_MAX_BUCKETS] — **not** `windowMs / someBucketCount`, which
     * an earlier version of this method computed instead. That formula meant every edit to the
     * "History window" slider changed the bucket grid's own width, which reassigns *every*
     * retained sample to a different bucket — a full rebin, not a rescale, undoing the exact
     * value-stability the fixed-slot design above exists to guarantee, just triggered by a
     * settings change instead of the passage of time. (The latency graph never had this problem:
     * [latencySparkline] plots one point per sample with a continuously recomputed `x` and does
     * no binning at all, so there is nothing for it to rebin when the window changes — this is
     * what it means for the two graphs to "share" this particular behavior.) With width now a
     * true constant, a sample's bucket depends only on its own timestamp, [bucketWidthMs], and
     * the session's own start (see [bucketSlot]) — never on [windowMs] — so changing the window
     * slider changes only how many already-assigned buckets are currently in view, never which
     * bucket any sample belongs to. The default is anchored at the *default* window specifically
     * so the default on-screen experience (a fresh install, before the window slider is ever
     * touched) renders bucket-for-bucket identically to the fixed-[DEFAULT_MAX_BUCKETS]-bucket
     * grid this replaced.
     *
     * One consequence: bucket *count* is no longer pinned at [DEFAULT_MAX_BUCKETS] — with width
     * fixed, however many of those fixed-width slots fit in the configured [windowMs] is what
     * gets displayed (`ceil(windowMs / bucketWidthMs)`, occasionally a handful more during
     * session warm-up — see below): as few as ~7 at the narrowest configurable 1-minute window,
     * a few hundred at the widest configurable 30-minute one. None of that is a performance
     * concern — this is small-array arithmetic on the main/UI thread from Compose state, not a
     * hot path at any of these sizes. [DEFAULT_MAX_BUCKETS] no longer means "the bucket count,"
     * only the anchor used to derive [BUCKET_WIDTH_MS] (see its own doc). This is a *different*
     * kind of bucket-count variation than the one already forbidden elsewhere in this class's own
     * history: count still never depends on attempt count, or on when the line happens to be
     * redrawn — only on the *configured window*, exactly as stable a basis as
     * [DEFAULT_MAX_BUCKETS] itself used to be (see the "resolution grows with elapsed time"
     * test in the suite, which is about attempt-count independence and is unaffected by this).
     *
     * ### Session warm-up: resolution starts fine and coarsens into the fixed grid
     * A session's very first fixed-width slot can span the *entire* width of [bucketWidthMs] —
     * several seconds at the default width — before a second ordinary slot ever opens up, which
     * without more would show one flatlined point pinned at the right edge for that whole
     * stretch. [bucketSlot] instead subdivides just that first slot into [WARMUP_LEVELS]
     * progressively wider sub-buckets, anchored to the timestamp of the very first real sample
     * ever recorded — see its own doc, and [warmupLevel]'s, for the exact ladder and, especially,
     * why each sample's slot (warm-up or ordinary) is decided once and never revisited later, the
     * same permanence guarantee the fixed-slot design above already depends on.
     *
     * This method's own part in that: the slot-count arithmetic below (`newestSlot - bucketCount
     * + 1`) implicitly assumes every slot is exactly [bucketWidthMs] wide, which is only true
     * once warm-up has ended. Left unchanged, that arithmetic would under-cover real elapsed
     * time whenever the displayed range dips into the narrower warm-up slots — silently dropping
     * real, in-window data recorded during warm-up from the line entirely, even though
     * [windowedSamples] still counts it in [attemptCount]/[successPercent]. The fix is not a
     * check for "are we near warm-up" (an earlier version compared raw slot *numbers* across the
     * warm-up/ordinary boundary for that, which is unreliable exactly at the boundary itself and
     * repeatedly under-counted on a real running session, not just in a rare corner case) — it's
     * the actual invariant this display must satisfy regardless of warm-up: every sample in
     * [windowed] has to land in some displayed bucket. Because [bucketSlot] is monotonic in
     * timestamp, [windowed]'s own earliest retained sample always has the smallest slot number of
     * anything in [windowed], so the left edge is simply clamped to never sit later (a larger
     * slot number) than that sample's own slot — see the `minOf` below. Everywhere outside
     * warm-up that clamp is a no-op (the naive slot-count edge is already at or before it, the
     * steady-state case this class's own bucket-stability tests cover), so nothing about ordinary
     * ticks changes.
     *
     * The *displayed* range is these buckets ending at the slot containing [windowed]'s newest
     * sample — the same "anchored to newest, not stretched to fill the displayed span" axis
     * convention [windowFraction] already documents, just applied to fixed slot numbers. Only
     * the **live** slot (still receiving new samples every tick) and the trailing slot (as it
     * loses individual samples aging past [windowMs], or drops out of the displayed range
     * entirely once a newer slot pushes it out) legitimately change from one call to the next —
     * that is ordinary scrolling, not the bug this replaced.
     *
     * A fixed grid can't always land exactly on an arbitrary, continuously-advancing `newest`, so
     * the naive `newestSlot - bucketCount + 1` left edge alone can't be trusted to always reach
     * back far enough to cover every windowed sample (see [successBucketSlot]'s doc for the
     * boundary-rounding reasons why, and the "session warm-up" section above for the other,
     * larger reason: slot width itself isn't uniform during a session's own warm-up ladder).
     * `minOf` against [windowed]'s own earliest retained sample's slot is what corrects both:
     * see the code's own comment for why that's an unconditional fix rather than a heuristic
     * guess at whether a correction is needed.
     *
     * A bucket with zero real attempts in it is treated exactly like a bucket whose attempts
     * all failed: `y = 0f`, plotted as a dip and connected normally to its neighbors. There is
     * deliberately no shaded/broken "gap" concept on this graph — a period with no real attempts
     * is, from the user's point of view, indistinguishable from a period that was actively
     * failing, and this graph says so plainly instead of drawing a visual distinction between
     * the two. ([sparklineGapFractions] still legitimately shades gaps on the separate latency
     * graph, which is unaffected by any of this and still produces real per-sample `null` points
     * for a failed probe.)
     *
     * The one exception is the **leading** run of buckets before the very first real sample
     * recorded anywhere in the displayed window — those are omitted from the returned list
     * entirely (blank canvas), not plotted as a 0% dip. That first-sample boundary is the same
     * one [successPercent] already draws between "no probes yet" (`null`) and "every probe
     * failed" (`0`) — see its own doc — and collapsing ordinary session warm-up into a
     * flatlined "0% loss" would misrepresent "hasn't started checking yet" as "actively
     * failing," a worse, more misleading result than treating a real mid-session silence as a
     * miss.
     */
    fun successSparkline(bucketWidthMs: Long = BUCKET_WIDTH_MS): List<SparklinePoint> {
        require(bucketWidthMs > 0) { "bucketWidthMs must be positive, was $bucketWidthMs" }
        val windowed = windowedSamples
        if (windowed.isEmpty()) return emptyList()

        // The very first real sample ever recorded in this session -- from the full retained
        // [samples], not [windowed], since windowed's own first entry moves as windowMs or time
        // changes and warm-up anchoring must not (see bucketSlot's own doc).
        val anchorMs = samples.first().timestampMs
        val newestSlot = bucketSlot(windowed.last().timestampMs, bucketWidthMs, anchorMs)

        // Ceiling, not floor: guarantees bucketCount * bucketWidthMs is never shorter than
        // windowMs -- see successBucketSlot's doc for why a little slop either way is
        // unavoidable regardless of rounding direction, and why ceiling is the safer bias. This
        // naive left edge assumes every slot between it and newestSlot is exactly bucketWidthMs
        // wide, which is only true outside the session's own warm-up ladder (see bucketSlot's
        // doc) -- taken alone it can land *later* (a larger slot number) than the earliest
        // sample warm-up has to offer, silently excluding real, already-counted-in-windowed
        // data. minOf below is what corrects that; see its own comment.
        val bucketCount = ((windowMs + bucketWidthMs - 1) / bucketWidthMs).coerceAtLeast(1L)
        val naiveLeftmostSlot = newestSlot - bucketCount + 1

        // The display must include every sample in [windowed] -- full stop, not "unless it's
        // near warm-up." Because bucketSlot is monotonic in timestamp (see its own doc),
        // windowed's own earliest retained sample always has the smallest slot number of
        // anything in windowed, so clamping the left edge to never sit later than that sample's
        // own slot guarantees the invariant unconditionally, with no separate warm-up case
        // analysis needed. (An earlier version instead compared naiveLeftmostSlot against a
        // computed "warm-up slot base" to decide whether to make this correction at all -- a
        // strict `<` comparison of raw slot *numbers* across a boundary where slot width isn't
        // uniform, which is unreliable exactly at the boundary itself: on a real session, that
        // false-negative repeatedly dropped every real warm-up-era sample from the display for a
        // whole stretch of ticks, not just a rare corner case. minOf replaces that guess with the
        // actual requirement.)
        val leftmostSlot = minOf(naiveLeftmostSlot, bucketSlot(windowed.first().timestampMs, bucketWidthMs, anchorMs))
        val displayedCount = (newestSlot - leftmostSlot + 1).toInt()

        val attempts = IntArray(displayedCount)
        val successes = IntArray(displayedCount)
        windowed.forEach { sample ->
            val index = (bucketSlot(sample.timestampMs, bucketWidthMs, anchorMs) - leftmostSlot).toInt()
            if (index in 0 until displayedCount) {
                attempts[index]++
                if (sample.succeeded) successes[index]++
            }
        }

        // Samples are appended in timestamp order, so the first one is the earliest real
        // attempt anywhere in the displayed window -- everything in an earlier slot is session
        // warm-up, not a miss. Clamped into range for the same reason the per-sample index above
        // is dropped when out of range: a first sample can, in the fixed-grid boundary case
        // successBucketSlot's doc describes, resolve to a slot just left of the displayed range.
        val firstSampleIndex = (bucketSlot(windowed.first().timestampMs, bucketWidthMs, anchorMs) - leftmostSlot)
            .toInt().coerceIn(0, displayedCount - 1)

        return (0 until displayedCount).mapNotNull { index ->
            val x = if (displayedCount == 1) 1f else index.toFloat() / (displayedCount - 1)
            when {
                attempts[index] > 0 -> SparklinePoint(x = x, y = successes[index].toFloat() / attempts[index])
                index < firstSampleIndex -> null // before the first real sample -- warm-up, leave blank.
                else -> SparklinePoint(x = x, y = 0f) // no attempts here: treated exactly like a miss.
            }
        }
    }

    /**
     * Which fixed slot [successSparkline] assigns [timestampMs] to, given a bucket width of
     * [bucketWidthMs] and the session's warm-up anchor [anchorMs] — the timestamp of the very
     * first real sample ever recorded (see [successSparkline]'s own doc for why it must be that
     * and not [windowedSamples]'s first entry). Dispatches between two permanent,
     * non-overlapping schemes, chosen purely by how [timestampMs] compares to `anchorMs +
     * bucketWidthMs`:
     *
     * - At or past that point, this is exactly [successBucketSlot] — the ordinary fixed grid,
     *   completely unaffected by warm-up, forever.
     * - Before it (real elapsed time since [anchorMs] less than [bucketWidthMs]), this subdivides
     *   that one span into [WARMUP_LEVELS] sub-buckets that double in width at each level — see
     *   [warmupLevel] for the exact ladder — numbered as the [WARMUP_LEVELS] slots immediately
     *   below [successBucketSlot] of `anchorMs + bucketWidthMs` (the smallest ordinary slot any
     *   sample could ever reach once warm-up ends). That numbering is what makes the two schemes
     *   compose into one consistent, ordered slot space: every warm-up slot always sorts before
     *   every ordinary slot any sample in *this* session could receive, and the warm-up slots
     *   sort among themselves in the same order as the levels they represent — true regardless of
     *   how large or small the timestamps involved happen to be, so it holds equally for
     *   production epoch milliseconds and the small values this class's own tests use.
     *
     * A given `(timestampMs, bucketWidthMs, anchorMs)` triple always produces the same slot,
     * forever — the entire point, exactly as for [successBucketSlot] alone. What makes this safe
     * to call again and again as more samples arrive, unlike e.g. deriving a bucket from "how
     * many samples currently exist": every input here is itself permanent for a given sample —
     * its own timestamp never changes once recorded, [bucketWidthMs] is a caller-supplied
     * constant, and [anchorMs] is the *first-ever* recorded sample's own timestamp, which cannot
     * change as later samples are appended (only eviction past [MAX_SAMPLES] could move it, and
     * warm-up is over — by definition — within one [bucketWidthMs] of real elapsed time, many
     * orders of magnitude sooner than [MAX_SAMPLES] could ever be reached at any realistic
     * pacing).
     */
    private fun bucketSlot(timestampMs: Long, bucketWidthMs: Long, anchorMs: Long): Long {
        val warmupEndMs = anchorMs + bucketWidthMs
        if (timestampMs >= warmupEndMs) return successBucketSlot(timestampMs, bucketWidthMs)
        val warmupSlotBase = successBucketSlot(warmupEndMs, bucketWidthMs)
        return warmupSlotBase - WARMUP_LEVELS + warmupLevel(timestampMs - anchorMs, bucketWidthMs)
    }

    /**
     * Which of [WARMUP_LEVELS] progressively wider sub-buckets [elapsedMs] (time since the
     * session's warm-up anchor, always in `[0, bucketWidthMs)` by [bucketSlot]'s own contract)
     * falls into. Levels double in width at each step — level 0 covers `[0, u)`, level 1 covers
     * `[u, 3u)`, level 2 covers `[3u, 7u)`, and so on — for a unit `u` chosen so the doubling
     * ladder's boundaries land, up to integer-rounding slop absorbed entirely by the last,
     * open-ended level, at exactly [bucketWidthMs]: the instant [bucketSlot] hands off to the
     * ordinary, un-subdivided fixed grid.
     *
     * This is what gives the graph its "resolution starts fine and coarsens" appearance during
     * warm-up: the first real samples of a session, typically well under a second apart, each
     * land in their own narrow early-level bucket instead of being flattened into one
     * multi-second slot, and later ones progressively share wider and wider buckets as real
     * elapsed time grows. It is purely a function of *when* a sample was recorded relative to the
     * session's own start — never of how many samples exist or have been recorded since — so
     * nothing already assigned a level here is ever revisited by a later call with more samples
     * in hand (see [bucketSlot]'s own doc for the full permanence argument).
     */
    private fun warmupLevel(elapsedMs: Long, bucketWidthMs: Long): Int {
        val unit = (bucketWidthMs / ((1L shl WARMUP_LEVELS) - 1)).coerceAtLeast(1L)
        for (level in 0 until WARMUP_LEVELS - 1) {
            val boundary = ((1L shl (level + 1)) - 1) * unit
            if (elapsedMs < boundary) return level
        }
        return WARMUP_LEVELS - 1
    }

    /**
     * Which fixed, absolute-time bucket the *ordinary* (post-warm-up) grid assigns [timestampMs]
     * to, given a bucket width of [bucketWidthMs]: an integer slot number for the
     * half-open-on-the-left, closed-on-the-right interval `(slot * bucketWidthMs, (slot + 1) *
     * bucketWidthMs]`. [successSparkline] never calls this directly any more — it goes through
     * [bucketSlot], which dispatches here for every sample once the session's brief warm-up era
     * has passed (or unconditionally, when no warm-up window is in view at all). A given
     * `(timestampMs, bucketWidthMs)` pair always produces the same slot, forever — that
     * stability is the entire point (see [successSparkline]'s doc for why).
     *
     * The interval is closed on the *right*, not the left, specifically so [bucketSlot] and
     * [successSparkline] can run `newest` itself through this same function to find which slot
     * anchors the displayed range, with no separate case for it: if `newest` ever lands exactly
     * on a multiple of [bucketWidthMs], it must resolve to the slot that already covers the
     * instant before it (the slot the rest of "right now" belongs to), not a fresh slot of its
     * own that no earlier sample could ever have landed in. Getting this backwards (closed on the
     * left) was tried first and failed a constructed test where `newest` fell exactly on a bucket
     * boundary: the newest slot ended up one further right than intended, which pushed the
     * displayed range's left edge past the start of an otherwise fully in-window real data
     * cluster and dropped it from the line entirely, even though [windowedSamples] still counted
     * it in [attemptCount]/[successPercent].
     *
     * That fix alone still leaves a gap, because [windowMs] need not be a multiple of
     * [bucketWidthMs] and `newest` can fall anywhere within its own slot's interval rather than
     * always at a convenient edge: the naive `newestSlot - bucketCount + 1` arithmetic
     * [successSparkline] derives from this function can, on its own, land *later* than the slot
     * [windowed]'s own earliest retained sample actually needs — which, left uncorrected, would
     * silently drop real, already-in-window data from the `index in 0 until displayedCount` guard
     * in [successSparkline] even though [windowedSamples] still counts it in
     * [attemptCount]/[successPercent]. An earlier version tried to catch only the specific case
     * where this gap was large (the session's own warm-up ladder, whose slots are narrower than
     * [bucketWidthMs]) by comparing raw slot numbers with a strict `<` — which is a false negative
     * exactly at the boundary where the two compared numbers are equal, and on a real running
     * session repeatedly dropped real warm-up-era data for a substantial stretch of ticks, not a
     * rare corner case (found by an independent simulation sweeping window sizes and pacing
     * intervals). [successSparkline] now closes this unconditionally instead: it takes the `minOf`
     * of the naive left edge and [bucketSlot] of [windowed]'s own earliest sample, guaranteeing
     * every windowed sample lands in the displayed range regardless of whether the gap came from
     * ordinary boundary rounding or from warm-up's narrower slots — no case analysis, no
     * heuristic, and no remaining boundary artifact of any size, small or large.
     */
    private fun successBucketSlot(timestampMs: Long, bucketWidthMs: Long): Long =
        Math.floorDiv(timestampMs - 1, bucketWidthMs)

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

        /** Anchor for [BUCKET_WIDTH_MS]: the bucket count [successSparkline] used, at the
         * default window, before bucket width became a fixed constant independent of [windowMs]
         * (see [BUCKET_WIDTH_MS]'s own doc) — enough resolution for a card-sized sparkline
         * without plotting points finer than the eye can separate. No longer itself the
         * displayed bucket count for every window, which now varies with [windowMs] — this is
         * only the count at [DEFAULT_WINDOW_MS] specifically, chosen so a fresh install's
         * default view is unchanged by that redesign. */
        const val DEFAULT_MAX_BUCKETS: Int = 48

        /** The fixed, [windowMs]-independent bucket width [successSparkline] bins by unless a
         * caller overrides it directly (a test-only escape hatch — `:app` always uses this
         * default). Anchored at [DEFAULT_WINDOW_MS] / [DEFAULT_MAX_BUCKETS] (an exact division,
         * 8_750ms) specifically so the default on-screen experience — a fresh install, before
         * the "History window" slider is ever touched — renders the exact same resolution it
         * always has. See [successSparkline]'s own doc for why this must be a true constant and
         * never a function of the currently configured [windowMs]: an earlier version computed
         * it as `windowMs / bucketCount`, so every slider edit silently rebinned every retained
         * sample into a new bucket. */
        const val BUCKET_WIDTH_MS: Long = DEFAULT_WINDOW_MS / DEFAULT_MAX_BUCKETS

        /** How many progressively wider sub-buckets [warmupLevel] divides a session's very
         * first [BUCKET_WIDTH_MS]-wide slot into — see [successSparkline]'s "session warm-up"
         * doc section for why, and [warmupLevel]'s own doc for the exact doubling ladder this
         * produces. Six levels at the default bucket width means an initial resolution near
         * 139ms, doubling five times up to the full ~8.75s bucket width — fine enough to show
         * distinct points for typical probe pacing (0-1000ms, see
         * `UplinkPreferences.STEP_DELAY_RANGE_MS`) without an unbounded number of levels. */
        private const val WARMUP_LEVELS: Int = 6
    }
}
