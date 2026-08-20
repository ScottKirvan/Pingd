package com.uplinkstatus.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.uplinkstatus.core.history.SparklinePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planSparklineDots] in isolation -- see that function's own doc for why it exists specifically
 * to make [SparklineStyle.Dots] regression-testable without Robolectric or a `Canvas`: it is the
 * pure "what would be drawn" step that sits between the plotted points and the actual
 * `drawCircle` calls in [drawDotsSegment], expressed entirely in plain data (`Offset`/`Color`),
 * so this file needs neither Robolectric nor an Android runtime -- a plain JUnit test is enough.
 *
 * The property under test is the entire reason the raw-success debug card's dots exist. The
 * on-device bug this fixes: 38 real, distinct probe attempts over a healthy connection (all
 * `y = 1f`) rendered as a single flat line, because the card reused the ping-success/latency
 * cards' connected-line rendering unmodified. [planSparklineDots] is what guarantees that can't
 * happen again -- it returns one [SparklineDot] per point, always, by construction (there is no
 * code path in it that can merge two points into one mark or build a connected path), which is
 * exactly what a screenshot-diff would otherwise be needed to confirm. This repo has no
 * screenshot-diffing infrastructure and this is a temporary debug card, so introducing one isn't
 * warranted -- this pure-function boundary is what makes the property checkable without it.
 */
class SparklineDotsPlanTest {

    private fun plotted(x: Float, y: Float) = PlottedPoint(
        offset = Offset(x, y),
        source = SparklinePoint(x = x, y = y),
    )

    @Test
    fun `every point becomes its own dot, even when every neighbor shares the same y`() {
        // The exact shape of the on-device report: many real attempts, all successes, so every
        // point sits at the same y -- a connected-line rendering of this data collapses to one
        // flat, featureless stroke. planSparklineDots must not collapse it: 38 points in must
        // mean 38 SparklineDots out, not fewer.
        val segment = (0 until 38).map { index -> plotted(x = index / 37f, y = 1f) }

        val dots = planSparklineDots(segment) { Color.Green }

        assertEquals(38, dots.size)
    }

    @Test
    fun `each dot keeps its own point's pixel position, in order, never a shared or averaged one`() {
        val segment = listOf(plotted(0f, 10f), plotted(5f, 20f), plotted(9f, 10f))

        val dots = planSparklineDots(segment) { Color.Blue }

        assertEquals(listOf(Offset(0f, 10f), Offset(5f, 20f), Offset(9f, 10f)), dots.map { it.center })
    }

    @Test
    fun `each dot's color comes from its own point, not one color for the whole segment`() {
        val success = plotted(x = 0f, y = 1f)
        val failure = plotted(x = 1f, y = 0f)

        val dots = planSparklineDots(listOf(success, failure)) { point ->
            if (point.y == 1f) Color.Green else Color.Red
        }

        assertEquals(listOf(Color.Green, Color.Red), dots.map { it.color })
    }

    @Test
    fun `an empty segment produces no dots`() {
        assertEquals(emptyList<SparklineDot>(), planSparklineDots(emptyList()) { Color.Green })
    }
}
