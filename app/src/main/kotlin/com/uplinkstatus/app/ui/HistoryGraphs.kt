package com.uplinkstatus.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.uplinkstatus.app.state.UplinkProbeHistory
import com.uplinkstatus.core.history.ProbeHistory
import com.uplinkstatus.core.history.SparklinePoint
import com.uplinkstatus.core.history.sparklineGapFractions
import kotlin.math.roundToInt

const val TAG_HISTORY_GRAPHS = "settings_history_graphs"
const val TAG_PING_SUCCESS_CARD = "settings_ping_success_card"
const val TAG_PING_SUCCESS_VALUE = "settings_ping_success_value"
const val TAG_LATENCY_CARD = "settings_latency_card"
const val TAG_LATENCY_VALUE = "settings_latency_value"
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
 * The settings screen's two live history graphs — ping success percentage and latency trend —
 * over the shared, user-configurable window, read straight from [UplinkProbeHistory].
 *
 * Both are built from **real probe attempts only**: the automatic ("fake") ack of the
 * ping/ping/fake cycle never becomes a sample in the first place (see
 * [com.uplinkstatus.app.service.UplinkNotificationController.onEvent]), so nothing here has to
 * filter it back out.
 *
 * Structurally modeled on Starlink's own status display (title, big number, caption, trailing
 * sparkline, in a card) but drawn entirely from this app's `MaterialTheme.colorScheme`, so it
 * reads as part of this settings screen rather than as a transplant from another app. The
 * sparklines are plain `Canvas` drawing — a charting library would be a dependency and a
 * theme of its own for two polylines.
 *
 * The cards are always present, including before any probe has run, rather than appearing only
 * once there is data: this is a fixed part of the screen the user is meant to be able to find,
 * not a notification. What changes is what they *say* — "no probes yet" and an em dash, never
 * a placeholder number.
 */
@Composable
fun HistoryGraphs(modifier: Modifier = Modifier) {
    val history by UplinkProbeHistory.history.collectAsState()
    val caption = historySpanCaption(history)
    // Shared by both cards -- one history, one set of master-toggle transitions, same axis
    // both sparklines already plot against. See ProbeHistory.markerFractions' own doc for why
    // this is never mistaken for a real measured gap.
    val markers = history.markerFractions()

    Column(
        modifier = modifier.fillMaxWidth().testTag(TAG_HISTORY_GRAPHS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryGraphCard(
            title = "Ping success",
            value = history.successPercent?.let { "${it.roundToInt()}%" } ?: NO_VALUE,
            caption = caption,
            points = history.successSparkline(),
            markers = markers,
            lineColor = MaterialTheme.colorScheme.primary,
            gapColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GAP_SHADE_ALPHA),
            cardTag = TAG_PING_SUCCESS_CARD,
            valueTag = TAG_PING_SUCCESS_VALUE,
        )
        HistoryGraphCard(
            title = "Latency",
            value = history.averageLatencyMs?.let { "$it ms" } ?: NO_VALUE,
            caption = caption,
            points = history.latencySparkline(),
            markers = markers,
            lineColor = MaterialTheme.colorScheme.tertiary,
            gapColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GAP_SHADE_ALPHA),
            cardTag = TAG_LATENCY_CARD,
            valueTag = TAG_LATENCY_VALUE,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            // Deliberately not gated on the master toggle the way the preference controls
            // below it are: clearing accumulated samples is an action on this screen's own
            // display, not a setting for the service to apply, and it has to work exactly when
            // the user wants the slate clean -- including while the icon is switched off.
            TextButton(
                onClick = { UplinkProbeHistory.reset() },
                modifier = Modifier.testTag(TAG_HISTORY_RESET_BUTTON),
            ) {
                Text("Reset history")
            }
        }
    }
}

@Composable
private fun HistoryGraphCard(
    title: String,
    value: String,
    caption: String,
    points: List<SparklinePoint>,
    markers: List<Float>,
    lineColor: Color,
    gapColor: Color,
    cardTag: String,
    valueTag: String,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(cardTag)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // No weight here, deliberately: this column should take exactly the width its
            // own content (title/big number/caption) needs, not an even half of the row --
            // the sparkline is what's supposed to dominate the card, the same "trailing
            // sparkline" proportions the Starlink display this is modeled on uses. An equal
            // 1f/1f split left the graph confined to roughly the right half of the card.
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag(valueTag),
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Sparkline(
                points = points,
                markers = markers,
                color = lineColor,
                markerColor = MaterialTheme.colorScheme.outline,
                gapColor = gapColor,
                // Fills whatever width the text column (above) didn't claim -- the only
                // weighted child in this Row, so it gets 100% of the remainder rather than
                // splitting it.
                modifier = Modifier.weight(1f).height(SPARKLINE_HEIGHT),
            )
        }
    }
}

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
 * Purely arithmetic: every value arrives already reduced to the unit square by [ProbeHistory],
 * so there is no scaling or aggregation decision left here to disagree with the numbers above
 * it.
 */
@Composable
private fun Sparkline(
    points: List<SparklinePoint>,
    markers: List<Float>,
    color: Color,
    markerColor: Color,
    gapColor: Color,
    modifier: Modifier = Modifier,
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

        var current = mutableListOf<Offset>()
        val segments = mutableListOf<List<Offset>>()
        points.forEach { point ->
            val y = point.y
            if (y == null) {
                if (current.isNotEmpty()) {
                    segments += current
                    current = mutableListOf()
                }
            } else {
                current += Offset(
                    x = point.x * size.width,
                    y = inset + (1f - y) * usableHeight,
                )
            }
        }
        if (current.isNotEmpty()) segments += current

        segments.forEach { segment ->
            if (segment.size == 1) {
                drawCircle(color = color, radius = stroke, center = segment.first())
            } else {
                val path = Path().apply {
                    moveTo(segment.first().x, segment.first().y)
                    segment.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/**
 * The caption under each big number: the span the numbers and the graph beside them actually
 * cover, which is *not* always the configured window.
 *
 * A card that says "last 7 minutes" thirty seconds into a session would be describing six and
 * a half minutes of data that does not exist — the same class of untruth the status line and
 * the starting notification are both written to avoid. So the window is named only once the
 * retained samples really span it, and until then the caption names what is really there.
 */
internal fun historySpanCaption(history: ProbeHistory): String = when {
    history.attemptCount == 0 -> "no probes yet"
    // One sample covers an instant, not a duration -- there is no span to name yet.
    history.attemptCount < 2 -> "just started"
    history.spanMs >= history.windowMs -> "last ${describeDuration(history.windowMs)}"
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
