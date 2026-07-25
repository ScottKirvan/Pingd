package com.uplinkstatus.app.ui

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.uplinkstatus.app.prefs.FakeUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferences
import com.uplinkstatus.core.probe.ProbeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [SettingsScreen], run on the JVM via Robolectric (the same approach
 * `PlaceholderScreenTest` used in Stage 0 -- see the project's `app/src/testDebug` pattern).
 * Exercises the four interactions the Stage 3 brief calls out explicitly: toggling the
 * master switch, changing the network-scope selection, adding/removing an SSID, and editing
 * the ping-target host -- each verified by reading the real value back out of a
 * [FakeUplinkPreferencesRepository], not just by asserting a click landed.
 *
 * The screen's root `Column` scrolls (see [SettingsScreen]'s `verticalScroll`), and
 * Robolectric's default test-host window is short enough that several controls (the ping
 * target section especially) sit below the initial viewport. `performScrollTo()` before each
 * interaction brings the target node into the scrollable's visible bounds first -- without
 * it, `performClick()` silently no-ops on a node positioned outside the window rather than
 * throwing, which is exactly the kind of failure that looks like "the click didn't work"
 * when it's really "the click landed nowhere."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var repository: FakeUplinkPreferencesRepository

    @Before
    fun setUp() {
        repository = FakeUplinkPreferencesRepository()
        // ACCESS_FINE_LOCATION is granted up front for these tests purely so selecting the
        // SSID-whitelist scope doesn't pop a real system permission dialog mid-test --
        // Robolectric can't drive that dialog, and it's irrelevant to what these tests
        // check (the scope preference actually being persisted). The *code path* that
        // requests the permission only when the user selects that scope is exercised by
        // reading the source directly; simulating the OS permission-grant UI itself is
        // outside what a Robolectric Compose test can do.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun setContent() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(repository = repository)
            }
        }
    }

    @Test
    fun `toggling the master switch persists the new value`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(false, repository.current.masterToggleEnabled)

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, repository.current.masterToggleEnabled)
    }

    @Test
    fun `toggling hide-when-disabled persists the new value`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_HIDE_WHEN_DISABLED_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, repository.current.hideWhenDisabled)
    }

    @Test
    fun `selecting a network scope option persists it`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_SCOPE_CELLULAR_ONLY).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(NetworkScope.CELLULAR_ONLY, repository.current.networkScope)
    }

    @Test
    fun `selecting SSID whitelist scope reveals the whitelist editor`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_SCOPE_SSID_WHITELIST).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(NetworkScope.SSID_WHITELIST, repository.current.networkScope)
        composeTestRule.onNodeWithTag(TAG_SSID_INPUT).assertExists()
    }

    @Test
    fun `adding an SSID appends it to the whitelist`() {
        setContent()
        composeTestRule.onNodeWithTag(TAG_SCOPE_SSID_WHITELIST).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SSID_INPUT).performScrollTo().performTextInput("HomeWifi")
        composeTestRule.onNodeWithTag(TAG_SSID_ADD_BUTTON).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(setOf("HomeWifi"), repository.current.ssidWhitelist)
        composeTestRule.onNodeWithText("HomeWifi").assertExists()
    }

    @Test
    fun `removing an SSID takes it back out of the whitelist`() {
        repository = FakeUplinkPreferencesRepository(
            UplinkPreferences(
                networkScope = NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeWifi", "OfficeWifi"),
            ),
        )
        setContent()

        composeTestRule.onNodeWithTag(ssidRemoveButtonTag("HomeWifi")).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(setOf("OfficeWifi"), repository.current.ssidWhitelist)
    }

    @Test
    fun `selecting the Google quick-pick persists dns_google`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_PING_TARGET_ALTERNATE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(ProbeTarget.ALTERNATE_HOST, repository.current.pingTargetHost)
    }

    @Test
    fun `entering and saving a valid custom host persists it`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_INPUT).performScrollTo().performTextInput("probe.example.com")
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_SAVE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("probe.example.com", repository.current.pingTargetHost)
    }

    @Test
    fun `an invalid custom host is rejected and not persisted`() {
        setContent()
        val originalHost = repository.current.pingTargetHost

        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_INPUT).performScrollTo().performTextInput("not a valid host!!")
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_SAVE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(originalHost, repository.current.pingTargetHost)
        assertTrue(originalHost != "not a valid host!!")
    }
}
