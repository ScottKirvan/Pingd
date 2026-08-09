package com.uplinkstatus.app.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.uplinkstatus.core.history.ProbeHistory
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.tracer.ProbeCycleRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies [DataStoreUplinkPreferencesRepository] actually round-trips through a real
 * `DataStore<Preferences>` file (not a fake), per the Stage 3 acceptance criteria that
 * preferences "survive process death" -- a fake in-memory repository wouldn't prove that; a
 * real DataStore instance backed by a real file on disk (via [PreferenceDataStoreFactory],
 * pointed at a fresh [TemporaryFolder] per test rather than the shared app-wide
 * `uplinkPreferencesDataStore` singleton) does. Each test constructs its own
 * [DataStoreUplinkPreferencesRepository] instance around the same on-disk file where it
 * needs to prove persistence survives an instance being discarded and recreated -- standing
 * in for the process-death case a real device would exercise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UplinkPreferencesRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(fileName: String = "test.preferences_pb"): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(temporaryFolder.root, fileName) },
        )

    @Test
    fun `a fresh DataStore reads back documented defaults`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        val preferences = repository.preferencesFlow.first()

        assertEquals(true, preferences.masterToggleEnabled)
        assertEquals(false, preferences.hideWhenDisabled)
        assertEquals(NetworkScope.ANY_CONNECTION, preferences.networkScope)
        assertEquals(emptySet<String>(), preferences.ssidWhitelist)
        assertEquals(ProbeTarget.DEFAULT_HOST, preferences.pingTargetHost)
        assertEquals(ProbeCycleRunner.DEFAULT_STEP_DELAY_MS, preferences.stepDelayMs)
        assertEquals(ProbeHistory.DEFAULT_WINDOW_MS, preferences.historyWindowMs)
    }

    @Test
    fun `setMasterToggleEnabled writes and reads back`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setMasterToggleEnabled(false)

        assertEquals(false, repository.preferencesFlow.first().masterToggleEnabled)
    }

    @Test
    fun `setHideWhenDisabled writes and reads back`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setHideWhenDisabled(true)

        assertEquals(true, repository.preferencesFlow.first().hideWhenDisabled)
    }

    @Test
    fun `setNetworkScope writes and reads back every mode`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        for (scope in NetworkScope.entries) {
            repository.setNetworkScope(scope)
            assertEquals(scope, repository.preferencesFlow.first().networkScope)
        }
    }

    @Test
    fun `setSsidWhitelist writes and reads back a set of SSIDs`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setSsidWhitelist(setOf("HomeWifi", "OfficeWifi"))

        assertEquals(setOf("HomeWifi", "OfficeWifi"), repository.preferencesFlow.first().ssidWhitelist)
    }

    @Test
    fun `setPingTargetHost writes and reads back a custom host`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setPingTargetHost("probe.example.com")

        assertEquals("probe.example.com", repository.preferencesFlow.first().pingTargetHost)
    }

    @Test
    fun `setStepDelayMs writes and reads back a custom delay`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setStepDelayMs(137L)

        assertEquals(137L, repository.preferencesFlow.first().stepDelayMs)
    }

    @Test
    fun `setStepDelayMs coerces a value outside the slider range instead of storing it verbatim`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setStepDelayMs(-50L)
        assertEquals(UplinkPreferences.STEP_DELAY_RANGE_MS.first, repository.preferencesFlow.first().stepDelayMs)

        repository.setStepDelayMs(5_000L)
        assertEquals(UplinkPreferences.STEP_DELAY_RANGE_MS.last, repository.preferencesFlow.first().stepDelayMs)
    }

    @Test
    fun `a persisted step delay outside the slider range is coerced back into range on read`() = runTest {
        val dataStore = newDataStore()
        // Simulates a value from a future app version with a wider range, or direct file
        // tampering -- written straight to the DataStore file, bypassing setStepDelayMs's own
        // coercion, the same way the unrecognized-network-scope test above bypasses
        // setNetworkScope.
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[longPreferencesKey("step_delay_ms")] = 9_999L
            }
        }

        val preferences = DataStoreUplinkPreferencesRepository(dataStore).preferencesFlow.first()

        assertEquals(UplinkPreferences.STEP_DELAY_RANGE_MS.last, preferences.stepDelayMs)
    }

    @Test
    fun `setHistoryWindowMs writes and reads back a custom window`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setHistoryWindowMs(3 * 60_000L)

        assertEquals(3 * 60_000L, repository.preferencesFlow.first().historyWindowMs)
    }

    @Test
    fun `setHistoryWindowMs coerces a value outside the slider range instead of storing it verbatim`() = runTest {
        val repository = DataStoreUplinkPreferencesRepository(newDataStore())

        repository.setHistoryWindowMs(0L)
        assertEquals(
            UplinkPreferences.HISTORY_WINDOW_RANGE_MS.first,
            repository.preferencesFlow.first().historyWindowMs,
        )

        repository.setHistoryWindowMs(24 * 60 * 60_000L)
        assertEquals(
            UplinkPreferences.HISTORY_WINDOW_RANGE_MS.last,
            repository.preferencesFlow.first().historyWindowMs,
        )
    }

    /**
     * Coercion on *read* matters more here than for most preferences: [UplinkPreferences]'s own
     * `init` rejects an out-of-range window, so a stored value trusted verbatim would turn a
     * file written by a future app version (or tampered with directly) into an exception thrown
     * inside the preferences flow every service and the settings screen collect.
     */
    @Test
    fun `a persisted history window outside the slider range is coerced back into range on read`() = runTest {
        val dataStore = newDataStore()
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[longPreferencesKey("history_window_ms")] = 0L
            }
        }

        val preferences = DataStoreUplinkPreferencesRepository(dataStore).preferencesFlow.first()

        assertEquals(UplinkPreferences.HISTORY_WINDOW_RANGE_MS.first, preferences.historyWindowMs)
    }

    @Test
    fun `preferences survive a fresh repository instance over the same underlying file`() = runTest {
        val dataStore = newDataStore("shared.preferences_pb")
        val firstInstance = DataStoreUplinkPreferencesRepository(dataStore)
        firstInstance.setMasterToggleEnabled(false)
        firstInstance.setNetworkScope(NetworkScope.SSID_WHITELIST)
        firstInstance.setSsidWhitelist(setOf("HomeWifi"))
        firstInstance.setPingTargetHost(ProbeTarget.ALTERNATE_HOST)
        firstInstance.setStepDelayMs(250L)

        // A second repository instance around the *same* DataStore file simulates the
        // process being killed and the app cold-starting again -- nothing here is held in
        // memory between the two instances, only what actually reached disk.
        val secondInstance = DataStoreUplinkPreferencesRepository(dataStore)
        val reread = secondInstance.preferencesFlow.first()

        assertEquals(false, reread.masterToggleEnabled)
        assertEquals(NetworkScope.SSID_WHITELIST, reread.networkScope)
        assertEquals(setOf("HomeWifi"), reread.ssidWhitelist)
        assertEquals(ProbeTarget.ALTERNATE_HOST, reread.pingTargetHost)
        assertEquals(250L, reread.stepDelayMs)
    }

    @Test
    fun `an unrecognized persisted network scope value falls back to the documented default`() = runTest {
        val dataStore = newDataStore()
        // Write a raw, unrecognized string directly -- simulates a future app version
        // persisting a scope value this build doesn't know about, or a corrupted value.
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.stringPreferencesKey("network_scope")] = "SOME_FUTURE_MODE"
            }
        }

        val preferences = DataStoreUplinkPreferencesRepository(dataStore).preferencesFlow.first()

        assertEquals(NetworkScope.ANY_CONNECTION, preferences.networkScope)
    }
}
