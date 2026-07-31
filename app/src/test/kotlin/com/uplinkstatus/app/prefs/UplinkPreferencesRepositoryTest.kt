package com.uplinkstatus.app.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.uplinkstatus.core.probe.ProbeTarget
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
        assertEquals(NetworkScope.WIFI_ONLY, preferences.networkScope)
        assertEquals(emptySet<String>(), preferences.ssidWhitelist)
        assertEquals(ProbeTarget.DEFAULT_HOST, preferences.pingTargetHost)
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
    fun `preferences survive a fresh repository instance over the same underlying file`() = runTest {
        val dataStore = newDataStore("shared.preferences_pb")
        val firstInstance = DataStoreUplinkPreferencesRepository(dataStore)
        firstInstance.setMasterToggleEnabled(false)
        firstInstance.setNetworkScope(NetworkScope.SSID_WHITELIST)
        firstInstance.setSsidWhitelist(setOf("HomeWifi"))
        firstInstance.setPingTargetHost(ProbeTarget.ALTERNATE_HOST)

        // A second repository instance around the *same* DataStore file simulates the
        // process being killed and the app cold-starting again -- nothing here is held in
        // memory between the two instances, only what actually reached disk.
        val secondInstance = DataStoreUplinkPreferencesRepository(dataStore)
        val reread = secondInstance.preferencesFlow.first()

        assertEquals(false, reread.masterToggleEnabled)
        assertEquals(NetworkScope.SSID_WHITELIST, reread.networkScope)
        assertEquals(setOf("HomeWifi"), reread.ssidWhitelist)
        assertEquals(ProbeTarget.ALTERNATE_HOST, reread.pingTargetHost)
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

        assertEquals(NetworkScope.WIFI_ONLY, preferences.networkScope)
    }
}
