package com.uplinkstatus.app.ui

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.uplinkstatus.app.R
import com.uplinkstatus.app.prefs.DataStoreUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.FakeUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferences
import com.uplinkstatus.app.prefs.UplinkPreferencesRepository
import com.uplinkstatus.app.service.UplinkStatusService
import com.uplinkstatus.app.state.UplinkActivityStatus
import com.uplinkstatus.app.state.UplinkIconDisplay
import com.uplinkstatus.app.state.UplinkRuntimeStatus
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.visibility.UplinkVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Only used by the master-toggle restart-ordering test below, which needs a real
     * `DataStore<Preferences>` file rather than an in-memory fake -- see its own doc. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        // Same reasoning again -- a previous test's last mirrored icon must not leak into
        // this one's "preview absent by default" assertion below.
        UplinkIconDisplay.resetForTest()
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

    /**
     * Regression test for the "re-enabling the master toggle races the service restart against
     * the DataStore write it depends on" defect.
     *
     * Unlike the test above, this one does not use [FakeUplinkPreferencesRepository]: its
     * writes are synchronous in-memory assignments, which removes the exact asynchrony the bug
     * lives in. Here the screen talks to a real [DataStoreUplinkPreferencesRepository] over a
     * real `DataStore<Preferences>` file, wrapped so the master-toggle write only completes
     * when this test says so. That models the losing ordering deterministically: on a device
     * it's a coroutine-dispatched DataStore write racing a Binder round-trip, with no ordering
     * guarantee either way; here the write is simply held until after the point where the
     * unfixed code would already have issued the restart.
     *
     * The property under test is the one that closes the race: `startForegroundService()` must
     * not be issued while the value the fresh service instance is about to read is still the
     * stale one. If it is, that instance derives HIDDEN, calls `stopSelf()` again, tears down
     * the collector it just subscribed, and the icon silently never comes back when the real
     * write lands.
     */
    @Test
    fun `re-enabling the master toggle restarts the service only after the new value is persisted`() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
        )
        val gatedRepository = GatedMasterToggleRepository(DataStoreUplinkPreferencesRepository(dataStore))
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(repository = gatedRepository)
            }
        }

        // Turn it off first -- on a device this is the transition that drives HIDDEN and
        // stops the service outright, which is what makes turning it back on a genuine
        // start-from-nothing rather than a nudge to an already-running instance.
        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        gatedRepository.releaseOneWrite()
        composeTestRule.waitUntil { persistedMasterToggle(gatedRepository) == false }
        confirmServiceCaughtUp()
        drainStartedServices() // discard everything the first toggle produced

        // Turn it back on, with the write deliberately still in flight.
        composeTestRule.onNodeWithTag(TAG_MASTER_TOGGLE).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "startForegroundService() must not be issued while the master-toggle write is " +
                "still in flight -- a fresh service instance reads preferences at startup, " +
                "and a stale read makes it derive HIDDEN and stopSelf() again, with nothing " +
                "left listening when the real write finally lands.",
            emptyList<String>(),
            drainStartedServices(),
        )

        gatedRepository.releaseOneWrite()

        val restarts = mutableListOf<String>()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            restarts += drainStartedServices()
            restarts.contains(UplinkStatusService::class.java.name)
        }
        // ...and by the time the restart went out, the value it exists to make the service
        // notice was already committed to disk.
        assertEquals(true, persistedMasterToggle(gatedRepository))
    }

    private fun persistedMasterToggle(repository: UplinkPreferencesRepository): Boolean =
        runBlocking { repository.preferencesFlow.first().masterToggleEnabled }

    /** Drains (and therefore consumes) every `startService`/`startForegroundService` intent
     * recorded since the last call, as class names. */
    private fun drainStartedServices(): List<String> {
        val application = RuntimeEnvironment.getApplication()
        val started = mutableListOf<String>()
        var intent = shadowOf(application).nextStartedService
        while (intent != null) {
            intent.component?.className?.let(started::add)
            intent = shadowOf(application).nextStartedService
        }
        return started
    }

    /**
     * A real repository whose `setMasterToggleEnabled` only completes once the test releases
     * it -- every other operation (including [preferencesFlow], which stays a real
     * `DataStore<Preferences>` read) is delegated untouched. Gating just the one write keeps
     * the test aimed at the ordering between *that* write and the service restart, without
     * turning the rest of the screen into a fake.
     */
    private class GatedMasterToggleRepository(
        private val delegate: UplinkPreferencesRepository,
    ) : UplinkPreferencesRepository by delegate {

        private val permits = Channel<Unit>(Channel.UNLIMITED)

        fun releaseOneWrite() {
            permits.trySend(Unit)
        }

        override suspend fun setMasterToggleEnabled(enabled: Boolean) {
            permits.receive()
            delegate.setMasterToggleEnabled(enabled)
        }
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

    /**
     * Thin wiring check -- [ScannerPreviewTest] already covers the composable's own reaction
     * to [UplinkIconDisplay] in isolation; this only has to prove it's actually mounted on the
     * real [SettingsScreen], not a second copy of that logic.
     */
    @Test
    fun `no scanner preview is shown before any icon has been reported`() {
        setContent()

        composeTestRule.onAllNodesWithTag(TAG_SCANNER_PREVIEW).assertCountEquals(0)
    }

    @Test
    fun `the scanner preview mirrors the icon UplinkIconDisplay reports, live`() {
        setContent()

        UplinkIconDisplay.report(R.drawable.ic_scan_3)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SCANNER_PREVIEW).assertExists()
    }

    @Test
    fun `the status line renders the service's latest reported activity`() {
        setContent()

        UplinkActivityStatus.report(UplinkActivityStatus.Activity.Connected(latencyMs = 42))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_STATUS_LINE).assertExists()
        composeTestRule.onNodeWithText("Status: connected, 42ms").assertExists()
    }

    /**
     * Startup and a real out-of-scope verdict are genuinely different things and have to read
     * as different things: "starting up…" is what the service can honestly say before it has
     * decided anything, and only a decision it actually made can produce the paused wording.
     */
    @Test
    fun `starting up and a real paused verdict read as different states`() {
        setContent()

        UplinkActivityStatus.report(UplinkActivityStatus.Activity.Starting)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Status: starting up…").assertExists()

        UplinkActivityStatus.report(UplinkActivityStatus.Activity.Paused(NetworkScope.WIFI_ONLY))
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Status: starting up…").assertCountEquals(0)
        composeTestRule.onNodeWithText("Status: paused (this network is out of scope)").assertExists()
    }

    @Test
    fun `checking the connection reads as distinct from being connected`() {
        setContent()

        UplinkActivityStatus.report(UplinkActivityStatus.Activity.CheckingConnection)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Status: checking the connection…").assertExists()
        composeTestRule.onAllNodesWithText("Status: connected").assertCountEquals(0)
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
