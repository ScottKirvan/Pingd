package com.bojustudio.pingd.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.bojustudio.pingd.app.R
import com.bojustudio.pingd.app.state.PingdIconDisplay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ScannerPreview] in isolation, driving [PingdIconDisplay] directly rather than through a
 * real [com.bojustudio.pingd.app.service.PingdNotificationController] -- that class's own tests
 * already cover which icon gets reported when; this file only has to prove the composable
 * reacts to [PingdIconDisplay] correctly, live, and renders nothing at all while it's `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerPreviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun reset() = PingdIconDisplay.resetForTest()

    @After
    fun tearDown() = PingdIconDisplay.resetForTest()

    private fun setContent() {
        composeTestRule.setContent {
            MaterialTheme {
                ScannerPreview()
            }
        }
    }

    @Test
    fun `renders nothing while PingdIconDisplay is null`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertDoesNotExist()
    }

    @Test
    fun `appears the moment PingdIconDisplay reports a frame`() {
        setContent()
        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertDoesNotExist()

        PingdIconDisplay.report(R.drawable.ic_scan_3)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertExists()
    }

    @Test
    fun `disappears again the moment PingdIconDisplay goes back to null`() {
        PingdIconDisplay.report(R.drawable.ic_scan_disabled)
        setContent()
        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertExists()

        PingdIconDisplay.report(null)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertDoesNotExist()
    }

    @Test
    fun `live updates as the reported icon changes, without needing to be recomposed from outside`() {
        PingdIconDisplay.report(R.drawable.ic_scan_1)
        setContent()
        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertExists()

        // Every position (and the dim frame) in turn -- the preview must keep tracking the
        // live singleton, not just render whatever was current the moment it first composed.
        listOf(
            R.drawable.ic_scan_2,
            R.drawable.ic_scan_3,
            R.drawable.ic_scan_4,
            R.drawable.ic_scan_5,
            R.drawable.ic_scan_disabled,
        ).forEach { iconRes ->
            PingdIconDisplay.report(iconRes)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertExists()
        }
    }
}
