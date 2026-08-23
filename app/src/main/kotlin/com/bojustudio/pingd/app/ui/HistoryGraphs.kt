package com.bojustudio.pingd.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bojustudio.pingd.app.state.PingdProbeHistory
import com.bojustudio.pingd.core.history.ProbeHistory
import com.bojustudio.pingd.core.history.SparklinePoint
import com.bojustudio.pingd.core.history.latencyColorFraction
import com.bojustudio.pingd.core.history.sparklineGapFractions
import kotlin.math.roundToInt

const val TAG_HISTORY_GRAPHS = "settings_history_graphs"
const val TAG_PING_SUCCESS_CARD = "settings_ping_success_card"
const val TAG_PING_SUCCESS_VALUE = "settings_ping_success_value"
const val TAG_PING_SUCCESS_SPARKLINE = "settings_ping_success_sparkline"
const val TAG_LATENCY_CARD = "settings_latency_card"
const val TAG_LATENCY_VALUE = "settings_latency_value"
const val TAG_LATENCY_SPARKLINE = "settings_latency_sparkline"
const val TAG_HISTORY_RESET_BUTTON = "settings_history_reset_button"

/** What a card's big number shows when there is genuinely nothing to show — an em dash, not a
 * zero, for the same reason [ProbeHistory.successPercent] is null rather than 0 before the
 * first probe. */
private const val NO_VALUE = "—"

private val SPARKLINE_HEIGHT = 48.dp
private val SPARKLINE_STROKE = 2.dp
private val MARKER_STROKE = 1.dp

/** Opacity of the shaded "no data here" region drawn behind a sparkline gap -- subtle enough
 * not to compete with the data line itself, visible enough to read as deliberate shading
 * rather than a rendering artifact. */
private const val GAP_SHADE_ALPHA = 0.15f

/**
 * Endpoints of the latency sparkline's absolute green→yellow→red color scale (see
 * [latencyColorFraction] for the pure threshold math these are laid over). Standard Material
 * green/amber/red 500 -- this app's `MaterialTheme.colorScheme` is Material-You dynamic color
 * derived from the user's wallpaper and has no green/amber slot of its own to borrow, and mixing
 * one dynamic endpoint (e.g. [MaterialTheme.colorScheme.error] for "red") with two fixed ones
 * would read as more inconsistent than three fixed anchors that are internally consistent with
 * each other -- legibility as "good/warning/bad" matters more here than theme-matching, since
 * this is a status color, not a decorative one.
 */
private val LATENCY_COLOR_FAST = Color(0xFF4CAF50)
private val LATENCY_COLOR_MID = Color(0xFFFFC107)
private val LATENCY_COLOR_SLOW = Color(0xFFF44336)

/**
 * Stops for the ping-success sparkline's left-to-right gradient sweep -- see
 * [SparklineColoring.Sweep]'s doc for the sweep itself. Originally drawn from this app's
 * `MaterialTheme.colorScheme.primary`/`secondary`/`tertiary`, on the reasoning that a fixed
 * palette would read as "borrowed from another app." On-device, that backfired: Material-You
 * dynamic color derives all three from the same wallpaper, and on the actual test device they
 * land close enough in hue that the "sweep" rendered as a single flat, barely-tinted line --
 * confirmed visually, not assumed. A visible multi-hue sweep is the entire point of this
 * treatment (see the reference image this was built from), so legibility of *that* now takes
 * priority over theme-matching, the same tradeoff already made for the latency scale above.
 */
private val PING_SUCCESS_SWEEP_START = Color(0xFF3B82F6) // blue
private val PING_SUCCESS_SWEEP_MID = Color(0xFF8B5CF6) // violet
private val PING_SUCCESS_SWEEP_END = Color(0xFFEC4899) // pink

/** The raw-ping debug line's color where it's drawn overlaid on the ping-success card's own
 * canvas (see [Sparkline]'s `overlayPoints` doc) -- white, so it reads as a distinct layer
 * against the ping-success line's own blue/violet/pink sweep regardless of where on that sweep
 * it crosses, and at a low alpha so the overlay stays visually secondary to the card's own data
 * rather than competing with it. */
private val RAW_PING_OVERLAY_COLOR = Color.White.copy(alpha = 0.3f)

/** Turns a raw latency into a point on the [LATENCY_COLOR_FAST]→[LATENCY_COLOR_MID]→
 * [LATENCY_COLOR_SLOW] scale, via [latencyColorFraction]'s pure threshold math -- the only place
 * an actual `Color` gets involved, since [latencyColorFraction] itself has no notion of one. */
private fun latencyColor(latencyMs: Long): Color {
    val fraction = latencyColorFraction(latencyMs)
    return if (fraction <= 0.5f) {
        lerp(LATENCY_COLOR_FAST, LATENCY_COLOR_MID, fraction / 0.5f)
    } else {
        lerp(LATENCY_COLOR_MID, LATENCY_COLOR_SLOW, (fraction - 0.5f) / 0.5f)
    }
}

/**
 * How [Sparkline] colors its line -- the two graphs use genuinely different strategies, not
 * variations on one:
 * - [Sweep] (ping success): one continuous left-to-right gradient spanning the **whole canvas
 *   width**, independent of data value and of how many disjoint [segments][sparklineGapFractions]
 *   the line breaks into -- purely positional/decorative, so a segment in the middle of the
 *   timeline shows the middle portion of the overall sweep rather than resetting to its own
 *   local start-to-end gradient.
 * - [ByValue] (latency): each point gets its own color from [colorFor], independent of position
 *   -- see [latencyColor]. Drawn as a sequence of small two-color-gradient line pieces, one per
 *   pair of consecutive points, rather than one path with one color.
 */
private sealed interface SparklineColoring {
    data class Sweep(val colors: List<Color>) : SparklineColoring
    data class ByValue(val colorFor: (SparklinePoint) -> Color) : SparklineColoring
}

/**
 * The settings screen's two live history graphs — ping success percentage and latency trend —
 * over the shared, user-configurable window, read straight from [PingdProbeHistory].
 *
 * Both are built from **real probe attempts only**: the automatic ("fake") ack of the
 * ping/ping/fake cycle never becomes a sample in the first place (see
 * [com.bojustudio.pingd.app.service.PingdNotificationController.onEvent]), so nothing here has to
 * filter it back out.
 *
 * Structurally modeled on Starlink's own status display (title, big number, caption, trailing
 * sparkline, in a card). Both lines' colors are fixed rather than theme-derived, and deliberately
 * so: the ping-success line's left-to-right gradient sweep (see [PING_SUCCESS_SWEEP_START] and
 * [SparklineColoring.Sweep] at its call site below) needs a visible multi-hue transition to mean
 * anything at all, which this app's Material-You dynamic `colorScheme` doesn't reliably provide
 * (see that constant's own doc for what went wrong on-device when it tried); the latency line's
 * green/yellow/red is a status color communicating a measurement, not a decorative one, so
 * legibility as "good/warning/bad" takes priority over theme-matching there too (see
 * [latencyColor]'s doc). The sparklines are plain `Canvas` drawing — a charting library would be
 * a dependency and a theme of its own for two polylines.
 *
 * The cards are always present, including before any probe has run, rather than appearing only
 * once there is data: this is a fixed part of the screen the user is meant to be able to find,
 * not a notification. What changes is what they *say* — "no probes yet" and an em dash, never
 * a placeholder number.
 *
 * ### Raw-ping overlay (temporary)
 * The ping-success card's own canvas also carries a second, translucent line -- see
 * [Sparkline]'s `overlayPoints` doc and [ProbeHistory.rawPingSparkline]'s doc for what it plots
 * and why. It exists to make the ping-success line's own nature visible: that line is a rolling
 * *average*, and without something to contrast it against, a glance can misread a partial dip as
 * "some fraction of pings are getting through" rather than "the recent success rate dipped." The
 * raw per-attempt overlay -- sharp jumps between two fixed heights, one per real attempt --
 * makes the smoothing visible by contrast. This is a debugging/clarity aid, not a permanent
 * user-facing feature -- safe to remove once it's no longer needed.
 */
@Composable
fun HistoryGraphs(modifier: Modifier = Modifier) {
    val history by PingdProbeHistory.history.collectAsState()
    val caption = historySpanCaption(history)
    // Shared by both cards -- one history, one set of master-toggle transitions, same axis
    // both sparklines already plot against. See ProbeHistory.markerFractions' own doc for why
    // this is never mistaken for a real measured gap.
    val markers = history.markerFractions()

    val pingSuccessValue = history.successPercent?.let { "${it.roundToInt()}%" } ?: NO_VALUE
    val latencyValue = history.averageLatencyMs?.let { "$it ms" } ?: NO_VALUE

    // Both cards' sparklines need the exact same width, so the same moment in time lands at
    // the same physical position on both graphs, not just the same x fraction. A hardcoded
    // dp guess can't provide that reliably -- it's calibrated against one language's strings at
    // one font scale, and silently stops fitting the moment either changes (a longer
    // translation, a larger accessibility text size). SubcomposeLayout measures the real text
    // column content once, unconstrained, at whatever language/font-scale is actually active,
    // and uses the widest result -- so the shared width is always derived from what the device
    // is actually showing, never guessed.
    SubcomposeLayout(modifier = modifier.fillMaxWidth().testTag(TAG_HISTORY_GRAPHS)) { incoming ->
        // Pass 1 (measurement only, never placed): stack both cards' text columns inside one
        // Column with no width constraint of its own. A Column's width is the max of its
        // children's, and each child here is itself a Column sized to its own widest line, so
        // the outer Column's resolved width is exactly "the widest single line, across every
        // title/value/caption on every card" -- the one number this whole layout needs.
        val textColumnWidth = subcompose(HistoryGraphsSlot.Measurement) {
            // Never placed, but SubcomposeLayout still keeps an unplaced slot's nodes in the
            // tree -- clearAndSetSemantics keeps this measurement-only copy of every card's
            // title/value/caption from also showing up in the semantics tree (and, via it,
            // screen readers and this file's own onNodeWithText/onNodeWithTag lookups) as a
            // second, invisible node with the exact same text as the real one below.
            Column(modifier = Modifier.clearAndSetSemantics {}) {
                HistoryCardTextColumn(title = "Ping success", value = pingSuccessValue, caption = caption)
                HistoryCardTextColumn(title = "Latency", value = latencyValue, caption = caption)
            }
        }.first().measure(Constraints()).width.toDp()

        // Pass 2: the real content, every card's text column pinned to that shared measured
        // width -- laid out under the layout's actual incoming constraints, so weight(1f) on
        // each card's Sparkline (unsupported during intrinsic/measurement-only passes) works
        // normally here.
        val content = subcompose(HistoryGraphsSlot.Content) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryGraphCard(
                    title = "Ping success",
                    value = pingSuccessValue,
                    caption = caption,
                    points = history.successSparkline(),
                    markers = markers,
                    // A left-to-right gradient sweep across the whole graph width, purely
                    // positional -- not a data encoding. Fixed stops, not this app's theme --
                    // see PING_SUCCESS_SWEEP_START's doc for why the theme-derived version
                    // didn't work.
                    coloring = SparklineColoring.Sweep(
                        colors = listOf(
                            PING_SUCCESS_SWEEP_START,
                            PING_SUCCESS_SWEEP_MID,
                            PING_SUCCESS_SWEEP_END,
                        ),
                    ),
                    gapColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GAP_SHADE_ALPHA),
                    cardTag = TAG_PING_SUCCESS_CARD,
                    valueTag = TAG_PING_SUCCESS_VALUE,
                    sparklineTag = TAG_PING_SUCCESS_SPARKLINE,
                    textColumnWidth = textColumnWidth,
                    // Temporary: the raw-ping debug line, overlaid directly on this card's own
                    // canvas -- see HistoryGraphs' and Sparkline's own docs.
                    overlayPoints = history.rawPingSparkline(),
                )
                HistoryGraphCard(
                    title = "Latency",
                    value = latencyValue,
                    caption = caption,
                    points = history.latencySparkline(),
                    markers = markers,
                    // Colored by each point's own *absolute* latency (green fast, red slow) --
                    // a different scale than the line's y-position, which stays session-
                    // relative. See SparklinePoint.latencyMs and latencyColorFraction's docs.
                    coloring = SparklineColoring.ByValue { point ->
                        // latencyMs is null only when y is null (a gap), which never reaches
                        // this lambda -- segments are built from non-null points only. The
                        // fallback exists purely so this stays total.
                        point.latencyMs?.let(::latencyColor) ?: LATENCY_COLOR_MID
                    },
                    gapColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GAP_SHADE_ALPHA),
                    cardTag = TAG_LATENCY_CARD,
                    valueTag = TAG_LATENCY_VALUE,
                    sparklineTag = TAG_LATENCY_SPARKLINE,
                    textColumnWidth = textColumnWidth,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Deliberately not gated on the master toggle the way the preference
                    // controls below it are: clearing accumulated samples is an action on this
                    // screen's own display, not a setting for the service to apply, and it has
                    // to work exactly when the user wants the slate clean -- including while
                    // the icon is switched off.
                    TextButton(
                        onClick = { PingdProbeHistory.reset() },
                        modifier = Modifier.testTag(TAG_HISTORY_RESET_BUTTON),
                    ) {
                        Text("Reset history")
                    }
                }
            }
        }.first().measure(incoming)

        layout(content.width, content.height) {
            content.placeRelative(0, 0)
        }
    }
}

/** Distinguishes [SubcomposeLayout]'s two subcomposition passes in [HistoryGraphs] -- see that
 * function's own doc for what each measures and why. */
private enum class HistoryGraphsSlot { Measurement, Content }

/** The title/big-number/caption stack every [HistoryGraphCard] shows to the left of its
 * sparkline. Factored out so [HistoryGraphs]' measurement-only pass can render the exact same
 * content (same strings, same styles) it's about to display for real, rather than a second,
 * separately-maintained approximation that could silently drift from what actually gets shown.
 * [valueTag] is `null` in that measurement pass -- the content there is never placed, so tagging
 * it for tests would be meaningless (and would collide with the real card's own tag). */
@Composable
private fun HistoryCardTextColumn(
    title: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueTag: String? = null,
) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryGraphCard(
    title: String,
    value: String,
    caption: String,
    points: List<SparklinePoint>,
    markers: List<Float>,
    coloring: SparklineColoring,
    gapColor: Color,
    cardTag: String,
    valueTag: String,
    sparklineTag: String,
    textColumnWidth: Dp,
    // Empty for every card except ping-success, whose canvas the raw-ping debug line is drawn
    // onto -- see Sparkline's own doc for why this stays a translucent overlay on the existing
    // canvas rather than a second Canvas stacked on top.
    overlayPoints: List<SparklinePoint> = emptyList(),
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(cardTag)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // textColumnWidth comes from HistoryGraphs' own measurement pass -- see its doc.
            // The sparkline is still what's supposed to dominate the card, the same "trailing
            // sparkline" proportions the Starlink display this is modeled on uses -- an equal
            // 1f/1f weight split left the graph confined to roughly the right half of the card,
            // which the measured width is comfortably clear of for this screen's actual content.
            HistoryCardTextColumn(
                title = title,
                value = value,
                caption = caption,
                valueTag = valueTag,
                modifier = Modifier.width(textColumnWidth),
            )
            Sparkline(
                points = points,
                markers = markers,
                coloring = coloring,
                markerColor = MaterialTheme.colorScheme.outline,
                gapColor = gapColor,
                overlayPoints = overlayPoints,
                // Fills whatever width the text column (above) didn't claim -- the only
                // weighted child in this Row, so it gets 100% of the remainder rather than
                // splitting it. That remainder is now identical across every card, since the
                // text column's own width is the same measured value on every card.
                modifier = Modifier.weight(1f).height(SPARKLINE_HEIGHT).testTag(sparklineTag),
            )
        }
    }
}

/** A plotted [SparklinePoint], carrying both its pixel [offset] and the source point it came
 * from -- [SparklineColoring.ByValue] needs the latter (for [SparklinePoint.latencyMs]) even
 * though [SparklineColoring.Sweep] never looks at it. */
private data class PlottedPoint(val offset: Offset, val source: SparklinePoint)

/**
 * Draws [points] as a polyline, **breaking the line at every gap** ([SparklinePoint.y] of
 * `null`) instead of joining across it. That break is the whole point: a failed probe measured
 * nothing, and drawing a segment through it would invent a latency, while dropping the point
 * entirely would quietly shorten the timeline as if the attempt never happened.
 *
 * A run of exactly one point (a lone measurement between two failures, or the very first
 * sample of a session) is drawn as a dot — a one-point path draws nothing at all, which would
 * make a genuinely measured value invisible.
 *
 * [markers] (from [ProbeHistory.markerFractions]) draw as thin vertical lines behind the data,
 * one per master-toggle-off transition retained in the window -- a visually distinct "we
 * weren't measuring here" mark, never a gap in [points] itself, since a marker is not a probe
 * attempt and must not be mistaken for one.
 *
 * Every gap also gets a [gapColor]-shaded rectangle behind the break, from
 * [sparklineGapFractions] — a plain break in the line, with nothing else marking it, reads as a
 * rendering glitch rather than "nothing was measured here"; the shading is what makes the
 * absence read as deliberate. Drawn before the markers and the line itself so both stay visible
 * on top of it.
 *
 * [coloring] picks how the line gets colored -- see [SparklineColoring]'s own doc for the two
 * genuinely different strategies. [SparklineColoring.Sweep]'s gradient brush is built once, from
 * `x = 0` to `x = size.width`, and reused across every disjoint segment's `drawPath`/`drawCircle`
 * call -- anchoring it to the *whole canvas* rather than letting each call default to its own
 * local bounds, which is what would otherwise happen: `Brush.horizontalGradient` only spans the
 * exact start/end it is given, and a segment sitting in the middle of the timeline would
 * otherwise reset to its own local start-to-end sweep instead of showing the middle portion of
 * the overall one.
 *
 * Purely arithmetic aside from the coloring itself: every position value arrives already reduced
 * to the unit square by [ProbeHistory], so there is no scaling or aggregation decision left here
 * to disagree with the numbers above it.
 *
 * [overlayPoints], when non-empty, draws a second line on this same `Canvas`, on top of
 * everything above -- a translucent, fixed-color ([RAW_PING_OVERLAY_COLOR]) connected
 * line, positioned by the exact same `x`/`y` -> pixel mapping [points] itself uses, since both
 * already share the same 0..1 unit-square convention [ProbeHistory] produces (see that class's
 * own doc). No separate scaling step, and no second `Canvas`: a genuinely separate `Canvas`
 * stacked in a `Box` would need its own size to exactly match this one's for the two lines to
 * align, a second thing that could drift out of sync; drawing into the same `Canvas` makes that
 * alignment automatic rather than something to maintain. Ignores [coloring]/[markers]/[gapColor]
 * entirely -- this is a temporary visual comparison aid, not a second full data series with its
 * own gap/marker treatment.
 */
@Composable
private fun Sparkline(
    points: List<SparklinePoint>,
    markers: List<Float>,
    coloring: SparklineColoring,
    markerColor: Color,
    gapColor: Color,
    modifier: Modifier = Modifier,
    overlayPoints: List<SparklinePoint> = emptyList(),
) {
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val stroke = SPARKLINE_STROKE.toPx()
        // Inset by half the stroke so a point at y = 0 or y = 1 draws fully inside the canvas
        // instead of being clipped in half at the edge.
        val inset = stroke / 2f
        val usableHeight = (size.height - stroke).coerceAtLeast(0f)

        // Drawn first (bottom-most), so a "no data here" region reads as background rather
        // than as something drawn over the data.
        sparklineGapFractions(points).forEach { gap ->
            drawRect(
                color = gapColor,
                topLeft = Offset(gap.start * size.width, 0f),
                size = Size(width = (gap.endInclusive - gap.start) * size.width, height = size.height),
            )
        }

        // Drawn next, so the data line above stays the visually dominant element.
        markers.forEach { fraction ->
            val x = fraction * size.width
            drawLine(
                color = markerColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = MARKER_STROKE.toPx(),
            )
        }

        val segments = plottedSegments(points, size, inset, usableHeight)

        // Anchored to the whole canvas width, once, outside the per-segment loop below -- see
        // this function's own doc for why that (and not each drawPath call's own local bounds)
        // is what keeps the sweep consistent across a line broken into several segments.
        val sweepBrush = (coloring as? SparklineColoring.Sweep)?.let {
            Brush.horizontalGradient(colors = it.colors, startX = 0f, endX = size.width)
        }

        segments.forEach { segment ->
            when (coloring) {
                is SparklineColoring.Sweep -> drawSweepSegment(segment, sweepBrush!!, stroke)
                is SparklineColoring.ByValue -> drawByValueSegment(segment, coloring.colorFor, stroke)
            }
        }

        // Drawn last, on top of everything above -- see this function's own doc.
        if (overlayPoints.isNotEmpty()) {
            plottedSegments(overlayPoints, size, inset, usableHeight).forEach { segment ->
                drawFlatColorSegment(segment, RAW_PING_OVERLAY_COLOR, stroke)
            }
        }
    }
}

/** Converts [points] into pixel-space [PlottedPoint] runs, broken at every gap ([SparklinePoint.y]
 * of `null`) -- the same conversion [Sparkline]'s own main line and its [overlayPoints] both need,
 * factored out so the two can't silently diverge in how a point becomes a pixel. */
private fun plottedSegments(
    points: List<SparklinePoint>,
    size: Size,
    inset: Float,
    usableHeight: Float,
): List<List<PlottedPoint>> {
    var current = mutableListOf<PlottedPoint>()
    val segments = mutableListOf<List<PlottedPoint>>()
    points.forEach { point ->
        val y = point.y
        if (y == null) {
            if (current.isNotEmpty()) {
                segments += current
                current = mutableListOf()
            }
        } else {
            current += PlottedPoint(
                offset = Offset(x = point.x * size.width, y = inset + (1f - y) * usableHeight),
                source = point,
            )
        }
    }
    if (current.isNotEmpty()) segments += current
    return segments
}

/** [SparklineColoring.Sweep]: one path (or dot), one shared [brush] -- the whole segment moves
 * together through whichever slice of the canvas-wide gradient it sits over. */
private fun DrawScope.drawSweepSegment(
    segment: List<PlottedPoint>,
    brush: Brush,
    stroke: Float,
) {
    if (segment.size == 1) {
        drawCircle(brush = brush, radius = stroke, center = segment.first().offset)
    } else {
        val path = Path().apply {
            moveTo(segment.first().offset.x, segment.first().offset.y)
            segment.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
        }
        drawPath(
            path = path,
            brush = brush,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** [Sparkline]'s `overlayPoints`: one flat, already-translucent [color] for the whole segment --
 * simpler than [SparklineColoring.Sweep]/[SparklineColoring.ByValue], which this overlay doesn't
 * use at all (see [Sparkline]'s own doc for why). */
private fun DrawScope.drawFlatColorSegment(
    segment: List<PlottedPoint>,
    color: Color,
    stroke: Float,
) {
    if (segment.size == 1) {
        drawCircle(color = color, radius = stroke, center = segment.first().offset)
    } else {
        val path = Path().apply {
            moveTo(segment.first().offset.x, segment.first().offset.y)
            segment.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** [SparklineColoring.ByValue]: every point can be a different color, so a single uniform-color
 * `Path` won't do -- instead, each piece between two consecutive points is its own small line
 * with a two-color gradient from that piece's start point's color to its end point's color,
 * which is what makes a run of points read as a smooth green→yellow→red transition rather than a
 * sequence of flat-colored segments. */
private fun DrawScope.drawByValueSegment(
    segment: List<PlottedPoint>,
    colorFor: (SparklinePoint) -> Color,
    stroke: Float,
) {
    if (segment.size == 1) {
        drawCircle(color = colorFor(segment.first().source), radius = stroke, center = segment.first().offset)
    } else {
        segment.zipWithNext().forEach { (start, end) ->
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(colorFor(start.source), colorFor(end.source)),
                    start = start.offset,
                    end = end.offset,
                ),
                start = start.offset,
                end = end.offset,
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The caption under each big number: the span the numbers and the graph beside them actually
 * cover, which is *not* always the configured window.
 *
 * A card that says "last 7 minutes" thirty seconds into a session would be describing six and
 * a half minutes of data that does not exist — the same class of untruth the status line and
 * the starting notification are both written to avoid. So the window is named only once it is
 * genuinely full ([ProbeHistory.isWindowFull]), and until then the caption names the true span
 * actually covered ([ProbeHistory.spanMs]) instead.
 *
 * This used to compare `history.spanMs >= history.windowMs` directly, on the reasoning that once
 * the covered span caught up to the configured window, the window was "full." That numeric
 * near-equality check turned out to essentially never fire for real, discretely-sampled data —
 * see [ProbeHistory.isWindowFull]'s doc for the full explanation — which was confirmed as the
 * root cause of an on-device report where this caption never credited the configured window
 * duration at all (a 1-minute window stuck at "last 59 seconds," a 4-minute window stuck at
 * "last 3 mins", indefinitely, even long after the window had genuinely filled). [isWindowFull]
 * answers the actual question — has real retained data aged out of the display window yet — as a
 * discrete fact rather than a coincidence of sample timing.
 */
internal fun historySpanCaption(history: ProbeHistory): String = when {
    history.attemptCount == 0 -> "no probes yet"
    // One sample covers an instant, not a duration -- there is no span to name yet.
    history.attemptCount < 2 -> "just started"
    history.isWindowFull -> "last ${describeDuration(history.windowMs)}"
    else -> "last ${describeDuration(history.spanMs)}"
}

/**
 * Whole seconds below a minute, whole minutes above it — a graph caption is a glance, not a
 * stopwatch.
 *
 * Truncates rather than rounds, so a partial unit is never rounded *up* into a claim of more
 * coverage than the samples actually have: 100 seconds of data reads "1 minute," not "2
 * minutes." The window itself is always a whole number of minutes, so naming the window is
 * exact either way; it is the warming-up spans this protects.
 */
internal fun describeDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(1L)
    if (seconds < 60L) {
        return if (seconds == 1L) "1 second" else "$seconds seconds"
    }
    val minutes = seconds / 60L
    return if (minutes == 1L) "1 minute" else "$minutes minutes"
}
