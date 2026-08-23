package com.bojustudio.pingd.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bojustudio.pingd.app.state.PingdProbeHistory
import com.bojustudio.pingd.core.history.ProbeHistory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HistoryGraphs] in isolation, driving [PingdProbeHistory] directly rather than through a
 * real [com.bojustudio.pingd.app.service.PingdNotificationController] -- that class's own tests
 * already cover which events become samples (and, crucially, which don't); this file only has
 * to prove the cards render what the history holds, live, and say nothing they can't back up.
 *
 * Same split, and the same reasoning, as [ScannerPreviewTest] versus the controller's tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryGraphsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun reset() = PingdProbeHistory.resetForTest()

    @After
    fun tearDown() = PingdProbeHistory.resetForTest()

    private fun setContent() {
        composeTestRule.setContent {
            MaterialTheme {
                HistoryGraphs()
            }
        }
    }

    /** Fills the history with [count] attempts one second apart, the first [failures] of which
     * failed, starting far enough back that nothing is pruned by the default window. */
    private fun seed(count: Int, failures: Int = 0, latencyMs: Long = 20) {
        repeat(count) { index ->
            val at = index * 1_000L
            if (index < failures) {
                PingdProbeHistory.recordFailure(timestampMs = at)
            } else {
                PingdProbeHistory.recordSuccess(latencyMs = latencyMs, timestampMs = at)
            }
        }
    }

    // --- Nothing measured yet --------------------------------------------------------------

    @Test
    fun `both cards are present from the start, so the feature is findable before any data`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_HISTORY_GRAPHS).assertExists()
        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_CARD).assertExists()
        composeTestRule.onNodeWithTag(TAG_LATENCY_CARD).assertExists()
        composeTestRule.onNodeWithText("Ping success").assertExists()
        composeTestRule.onNodeWithText("Latency").assertExists()
    }

    @Test
    fun `with no samples the numbers are blank, never a zero nobody measured`() {
        setContent()

        // "0%" here would report a total outage; "0 ms" would report an impossibly fast
        // connection. Neither has happened -- nothing has.
        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("—")
        composeTestRule.onNodeWithTag(TAG_LATENCY_VALUE).assertTextEquals("—")
        composeTestRule.onAllNodesWithText("no probes yet").assertCountEquals(2)
    }

    // --- Live values -------------------------------------------------------------------------

    @Test
    fun `the success percentage is the share of real attempts that succeeded`() {
        setContent()

        seed(count = 10, failures = 2)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("80%")
    }

    @Test
    fun `the latency number is the windowed average of the successful probes`() {
        setContent()

        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 0)
        PingdProbeHistory.recordSuccess(latencyMs = 30, timestampMs = 1_000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_LATENCY_VALUE).assertTextEquals("20 ms")
    }

    @Test
    fun `a failed probe leaves the latency average alone rather than dragging it to zero`() {
        setContent()

        PingdProbeHistory.recordSuccess(latencyMs = 40, timestampMs = 0)
        PingdProbeHistory.recordFailure(timestampMs = 1_000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_LATENCY_VALUE).assertTextEquals("40 ms")
        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("50%")
    }

    @Test
    fun `an all-failure history reads zero percent and no latency at all`() {
        setContent()

        seed(count = 4, failures = 4)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("0%")
        composeTestRule.onNodeWithTag(TAG_LATENCY_VALUE).assertTextEquals("—")
    }

    @Test
    fun `the cards keep tracking the singleton after they have composed`() {
        setContent()

        seed(count = 2)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("100%")

        PingdProbeHistory.recordFailure(timestampMs = 5_000)
        PingdProbeHistory.recordFailure(timestampMs = 6_000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("50%")
    }

    // --- Raw-ping overlay on the ping-success card (temporary) -------------------------------
    //
    // The overlay exists to make the ping-success line's own averaging visible by contrast --
    // see HistoryGraphs' own doc. It's drawn from ProbeHistory.rawPingSparkline(), reusing the
    // latency card's rendering pipeline unmodified.

    @Test
    fun `the raw-ping overlay on the ping-success card renders without crashing`() {
        // No dedicated assertion beyond "it composed" -- Canvas-drawn content isn't inspectable
        // through Compose's semantics tree the way text is. The overlay is drawn into the
        // ping-success card's Canvas from rawPingSparkline()'s points, so it needs the same
        // range of shapes exercised against it -- empty, sparse, dense, and alongside a marker
        // -- since a Canvas draw call crashing on an edge case (e.g. a single-point segment)
        // wouldn't be caught by a semantics-tree assertion.
        setContent()
        composeTestRule.waitForIdle() // empty history

        seed(count = 3)
        composeTestRule.waitForIdle() // sparse, short session -- exercises the single-point path

        var index = 3
        repeat(200) {
            PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = (index++) * 150L)
        }
        composeTestRule.waitForIdle() // dense burst, many overlay points

        PingdProbeHistory.recordFailure(timestampMs = (index++) * 150L)
        composeTestRule.waitForIdle() // a failure -- the overlay's 900ms fixed anchor point

        PingdProbeHistory.recordMasterToggleTransition(timestampMs = (index++) * 150L)
        composeTestRule.waitForIdle() // alongside a marker too

        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_CARD).assertExists()
    }

    @Test
    // Robolectric's default (unqualified) virtual screen is far wider than a real phone -- wide
    // enough that even a content-sized text column still leaves every card's sparkline a
    // comfortable, near-equal share, so it can't reproduce the inconsistency this test exists to
    // catch. Pinned to the actual device width (a Pixel 6 Pro, ~411dp) the regression was found
    // on instead.
    @Config(sdk = [34], qualifiers = "w411dp-h915dp")
    fun `both cards' sparklines are exactly the same width, regardless of each card's own text`() {
        // Regression coverage for a class of bug found on-device with a since-removed third
        // card: HistoryGraphCard's text column used to size itself to whatever its own
        // title/value/caption needed, so a card with longer text than its siblings' ended up
        // with a visibly narrower sparkline -- breaking the assumption that the same moment in
        // time lines up at the same physical position across every graph. The SubcomposeLayout
        // fix for that (measuring every card's real text content and sharing the widest result)
        // stays a live invariant worth protecting even with only two cards today.
        setContent()

        seed(count = 11)
        composeTestRule.waitForIdle()

        val pingSuccessWidth = composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_SPARKLINE)
            .fetchSemanticsNode().size.width
        val latencyWidth = composeTestRule.onNodeWithTag(TAG_LATENCY_SPARKLINE)
            .fetchSemanticsNode().size.width

        assertEquals(pingSuccessWidth, latencyWidth)
    }

    // --- Markers: master-toggle transitions -------------------------------------------------

    @Test
    fun `a master-toggle marker does not affect the displayed values or caption, and does not crash rendering`() {
        setContent()

        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 0)
        PingdProbeHistory.recordMasterToggleTransition(timestampMs = 500)
        PingdProbeHistory.recordSuccess(latencyMs = 30, timestampMs = 1_000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("100%")
        composeTestRule.onNodeWithTag(TAG_LATENCY_VALUE).assertTextEquals("20 ms")
        assertEquals(1, PingdProbeHistory.history.value.markers.size)
    }

    // --- Reset ---------------------------------------------------------------------------------

    @Test
    fun `the reset button clears the accumulated history on the spot`() {
        setContent()

        seed(count = 4, failures = 1)
        PingdProbeHistory.recordMasterToggleTransition(timestampMs = 4_000)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("75%")

        composeTestRule.onNodeWithTag(TAG_HISTORY_RESET_BUTTON).performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, PingdProbeHistory.history.value.attemptCount)
        assertEquals(0, PingdProbeHistory.history.value.markers.size)
        composeTestRule.onNodeWithTag(TAG_PING_SUCCESS_VALUE).assertTextEquals("—")
        composeTestRule.onAllNodesWithText("no probes yet").assertCountEquals(2)
    }

    @Test
    fun `the reset button keeps the configured window, so resetting is not also a settings change`() {
        PingdProbeHistory.setWindowMs(2 * 60_000L)
        setContent()
        seed(count = 4)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_HISTORY_RESET_BUTTON).performClick()
        composeTestRule.waitForIdle()

        assertEquals(2 * 60_000L, PingdProbeHistory.history.value.windowMs)
    }

    // --- The caption says only what the data supports ---------------------------------------

    /**
     * A card captioned "last 7 minutes" thirty seconds into a session would be describing six
     * and a half minutes of data that does not exist. The caption names the window only once
     * the samples really span it.
     */
    @Test
    fun `the caption names the span actually covered, not the window, while warming up`() {
        setContent()

        seed(count = 31) // 30 seconds of samples under the default 7-minute window
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("last 30 seconds").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("last 7 minutes").assertCountEquals(0)
    }

    /**
     * Regression test for the on-device report: a 1-minute configured window reported "last 59
     * seconds" indefinitely, even once genuinely, thoroughly full of real data -- root cause was
     * `historySpanCaption` comparing `spanMs >= windowMs`, which real, discretely-paced samples
     * essentially never satisfy exactly. Spaced 700ms apart (not a divisor of 60_000ms) so the
     * display cutoff can't land exactly on a sample boundary, reproducing the real failure mode
     * rather than a contrived exact-alignment case.
     */
    @Test
    fun `the caption names the full window once real data has genuinely aged out of the display, even off a round boundary`() {
        PingdProbeHistory.setWindowMs(60_000L)
        setContent()

        repeat(200) { index -> PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = index * 700L) }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("last 1 minute").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("last 59 seconds").assertCountEquals(0)
    }

    @Test
    fun `a single sample spans no time, and the caption says so rather than inventing one`() {
        setContent()

        PingdProbeHistory.recordSuccess(latencyMs = 10, timestampMs = 1_000)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("just started").assertCountEquals(2)
    }

    @Test
    fun `both cards carry the same caption -- one window, one history, two views of it`() {
        setContent()

        seed(count = 11)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("last 10 seconds").assertCountEquals(2)
    }

    // --- Caption wording, as a plain function -------------------------------------------------

    @Test
    fun `duration wording is singular where it should be and rounds to a glanceable unit`() {
        assertEquals("1 second", describeDuration(1_000))
        assertEquals("45 seconds", describeDuration(45_000))
        assertEquals("1 minute", describeDuration(60_000))
        assertEquals("7 minutes", describeDuration(7 * 60_000))
        assertEquals("30 minutes", describeDuration(30 * 60_000))
        // Sub-second spans still name a real unit rather than reading "0 seconds".
        assertEquals("1 second", describeDuration(120))
        // A partial unit truncates rather than rounding up -- 100 seconds of samples must not
        // read as two minutes of coverage that does not exist.
        assertEquals("1 minute", describeDuration(100_000))
        assertEquals("6 minutes", describeDuration(419_000))
    }

    @Test
    fun `the caption function itself distinguishes empty, warming up, and full`() {
        val window = 60_000L
        assertEquals("no probes yet", historySpanCaption(ProbeHistory(windowMs = window)))
        assertEquals(
            "just started",
            historySpanCaption(ProbeHistory(windowMs = window).recordSuccess(0, 10)),
        )
        assertEquals(
            "last 10 seconds",
            historySpanCaption(
                ProbeHistory(windowMs = window).recordSuccess(0, 10).recordSuccess(10_000, 10),
            ),
        )
        assertEquals(
            "last 1 minute",
            historySpanCaption(
                ProbeHistory(windowMs = window).recordSuccess(0, 10).recordSuccess(60_000, 10),
            ),
        )
    }

    /**
     * Regression test, at the caption-function level, for the on-device "last 59 seconds" report:
     * once real data has genuinely aged out of the display window, the caption must credit the
     * full configured window even though [ProbeHistory.spanMs] falls just short of it -- the
     * exact shape real, discretely-paced probes produce (see [ProbeHistory.isWindowFull]'s doc).
     * 700ms spacing does not evenly divide the 60s window, so the display cutoff can't land
     * exactly on a sample and mask the bug the way an evenly-dividing spacing would.
     */
    @Test
    fun `the caption function credits the full window once data has genuinely aged out, even when spanMs falls short`() {
        var history = ProbeHistory(windowMs = 60_000)
        repeat(200) { index -> history = history.recordSuccess(index * 700L, 10) }

        assertTrue("expected spanMs short of windowMs, was ${history.spanMs}", history.spanMs < 60_000L)
        assertEquals("last 1 minute", historySpanCaption(history))
    }
}
