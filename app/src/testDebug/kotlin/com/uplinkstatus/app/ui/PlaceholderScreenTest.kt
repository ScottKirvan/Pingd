package com.uplinkstatus.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test for the Stage 0 placeholder screen, run on the JVM via
 * Robolectric (no emulator/device needed, so this runs under `./gradlew test`
 * and in ordinary CI). It renders the real composable and asserts on its
 * actual output — it would fail if Compose weren't wired up correctly, or if
 * the screen stopped rendering its text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceholderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun placeholderScreenRendersExpectedText() {
        composeTestRule.setContent {
            MaterialTheme {
                PlaceholderScreen()
            }
        }

        composeTestRule.onNodeWithText(PLACEHOLDER_SCREEN_TEXT).assertExists()
    }
}
