package com.uplinkstatus.app.ui

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.uplinkstatus.app.prefs.FakeUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferences
import com.uplinkstatus.app.service.UplinkStatusService
import com.uplinkstatus.app.state.UplinkActivityStatus
import com.uplinkstatus.app.state.UplinkRuntimeStatus
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.visibility.UplinkVisibility
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
        // UplinkRuntimeStatus is a process-wide singleton -- without this, a previous test's
        // sequence number (and whatever it last reported) would leak into this one.
        UplinkRuntimeStatus.resetForTest()
        // Same reasoning as UplinkRuntimeStatus above -- a previous test's last status text
        // must not leak into this one's "status line absent by default" assertion.
        UplinkActivityStatus.resetForTest()
    }

    private fun setContent() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(repository = repository)
            }
        }
    }

    /** No real [com.uplinkstatus.app.service.UplinkStatusService] runs in these tests, so
     * nothing else will ever bump [UplinkRuntimeStatus]'s sequence number on its own the way
     * it eventually would on a real device -- this simulates that confirmation arriving, to
     * unblock [SettingsScreen]'s pending-lock before a test's next interaction. The specific
     * [UplinkVisibility] reported doesn't matter to any of these tests, only the fresh
     * sequence number does. */
    private fun confirmServiceCaughtUp() {
        UplinkRuntimeStatus.report(UplinkVisibility.ENABLED)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `toggling the master switch persists the new value`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(false, repository.current.masterToggleEnabled)

        // The toggle is locked (disabled) until the service confirms it caught up with the
        // first change -- simulate that arriving before attempting the second click, the
        // same way real elapsed time would on a device.
        confirmServiceCaughtUp()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, repository.current.masterToggleEnabled)
    }

    @Test
    fun `turning the master toggle off then back on restarts the service`() {
        // Regression test: UplinkStatusService tears itself down completely (stopSelf())
        // on a HIDDEN transition (e.g. master toggle off). Writing the preference back to
        // true alone doesn't bring it back -- only Context.startForegroundService() makes
        // Android deliver a fresh onStartCommand that re-evaluates visibility. Every
        // preference-write callback on this screen must call that, not just persist the
        // value; this test fails immediately if that call is ever removed from the master
        // toggle's callback specifically.
        setContent()
        val application = RuntimeEnvironment.getApplication()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick() // off
        composeTestRule.waitForIdle()
        confirmServiceCaughtUp() // unlocks the toggle for the second click below
        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick() // back on
        composeTestRule.waitForIdle()

        var sawServiceRestart = false
        var startedIntent = shadowOf(application).nextStartedService
        while (startedIntent != null) {
            if (startedIntent.component?.className == UplinkStatusService::class.java.name) {
                sawServiceRestart = true
            }
            startedIntent = shadowOf(application).nextStartedService
        }
        assertTrue(
            "Expected a startForegroundService() call targeting UplinkStatusService after " +
                "re-enabling the master toggle, but none was recorded.",
            sawServiceRestart,
        )
    }

    @Test
    fun `any settings change locks the whole panel until the service confirms it caught up`() {
        // Regression test: writing a preference and the service actually applying it are
        // separate asynchronous steps with a real, measurable gap between them (confirmed
        // directly on-device -- a persisted "on" was observed with the service not yet
        // running, and a persisted "off" with the service still running from before). Every
        // control must stay disabled through that gap, not just re-enable optimistically the
        // instant a preference write completes.
        setContent()

        // Network scope doesn't touch masterToggleEnabled at all, so if the master toggle
        // becomes disabled after this, it can only be the pending-lock -- not the separate
        // "master is off" mechanism `turning off the master toggle disables the rest of the
        // settings` below already covers.
        composeTestRule.onNodeWithTag(TAG_NETWORK_SCOPE_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_SCOPE_CELLULAR_ONLY).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).assertIsNotEnabled()

        confirmServiceCaughtUp()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).assertIsEnabled()
    }

    @Test
    fun `turning off the master toggle disables the rest of the settings`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_HIDE_WHEN_DISABLED_TOGGLE).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TAG_NETWORK_SCOPE_DROPDOWN).performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_DROPDOWN).performScrollTo().assertIsNotEnabled()
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

        composeTestRule.onNodeWithTag(TAG_NETWORK_SCOPE_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_SCOPE_CELLULAR_ONLY).performClick()
        composeTestRule.waitForIdle()

        assertEquals(NetworkScope.CELLULAR_ONLY, repository.current.networkScope)
    }

    @Test
    fun `selecting SSID whitelist scope reveals the whitelist editor`() {
        setContent()

        composeTestRule.onNodeWithTag(TAG_NETWORK_SCOPE_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_SCOPE_SSID_WHITELIST).performClick()
        composeTestRule.waitForIdle()

        assertEquals(NetworkScope.SSID_WHITELIST, repository.current.networkScope)
        composeTestRule.onNodeWithTag(TAG_SSID_INPUT).assertExists()
    }

    @Test
    fun `adding an SSID appends it to the whitelist`() {
        setContent()
        composeTestRule.onNodeWithTag(TAG_NETWORK_SCOPE_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_SCOPE_SSID_WHITELIST).performClick()
        composeTestRule.waitForIdle()
        // The whitelist editor's Add button is locked until the service confirms the scope
        // change above -- same pending-lock mechanism as the master toggle.
        confirmServiceCaughtUp()

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

        composeTestRule.onNodeWithTag(TAG_PING_TARGET_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_ALTERNATE).performClick()
        composeTestRule.waitForIdle()

        assertEquals(ProbeTarget.ALTERNATE_HOST, repository.current.pingTargetHost)
    }

    @Test
    fun `the custom host field is hidden until Custom is selected`() {
        setContent()

        composeTestRule.onAllNodesWithTag(TAG_PING_TARGET_CUSTOM_INPUT).assertCountEquals(0)
    }

    @Test
    fun `entering and saving a valid custom host persists it`() {
        setContent()
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_OPTION).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_INPUT).performScrollTo().performTextInput("probe.example.com")
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_SAVE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("probe.example.com", repository.current.pingTargetHost)
    }

    @Test
    fun `no status line is shown before the service has reported anything`() {
        setContent()

        composeTestRule.onAllNodesWithTag(TAG_STATUS_LINE).assertCountEquals(0)
    }

    @Test
    fun `the status line shows the service's latest activity text, with the Uplink prefix stripped`() {
        setContent()

        UplinkActivityStatus.update("Uplink: connected, 42ms")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_STATUS_LINE).assertExists()
        composeTestRule.onNodeWithText("Status: connected, 42ms").assertExists()
    }

    @Test
    fun `an invalid custom host is rejected and not persisted`() {
        setContent()
        val originalHost = repository.current.pingTargetHost
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_DROPDOWN).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_OPTION).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_INPUT).performScrollTo().performTextInput("not a valid host!!")
        composeTestRule.onNodeWithTag(TAG_PING_TARGET_CUSTOM_SAVE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(originalHost, repository.current.pingTargetHost)
        assertTrue(originalHost != "not a valid host!!")
    }
}
