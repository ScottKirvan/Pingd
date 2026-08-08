package com.uplinkstatus.app.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.tracer.ProbeCycleRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads/writes the spec's "User Preferences" as a [Flow] of immutable [UplinkPreferences]
 * snapshots. This is the seam [UplinkStatusService] and `SettingsScreen` both go through —
 * neither touches a DataStore key directly.
 *
 * An interface (not just a concrete DataStore-backed class) specifically so tests can supply
 * an in-memory fake instead of standing up a real `DataStore<Preferences>` file, the same way
 * [com.uplinkstatus.core.probe.Prober] and
 * [com.uplinkstatus.core.tracer.TracerScheduler] are interfaces `:core`'s tests fake rather
 * than concrete classes tests have to work around.
 */
interface UplinkPreferencesRepository {
    val preferencesFlow: Flow<UplinkPreferences>

    suspend fun setMasterToggleEnabled(enabled: Boolean)
    suspend fun setHideWhenDisabled(hide: Boolean)
    suspend fun setNetworkScope(scope: NetworkScope)
    suspend fun setSsidWhitelist(ssids: Set<String>)
    suspend fun setPingTargetHost(host: String)
    suspend fun setStepDelayMs(delayMs: Long)
}

/** The single, app-wide Preferences DataStore file. Both [UplinkStatusService] and the
 * settings screen read/write through `applicationContext.uplinkPreferencesDataStore` so they
 * always observe the same underlying storage, whether the process was started for the
 * activity, the service, or (after process death) either one independently. */
val Context.uplinkPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "uplink_preferences",
)

class DataStoreUplinkPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : UplinkPreferencesRepository {

    override val preferencesFlow: Flow<UplinkPreferences> = dataStore.data.map { prefs ->
        UplinkPreferences(
            masterToggleEnabled = prefs[MASTER_TOGGLE_ENABLED] ?: true,
            hideWhenDisabled = prefs[HIDE_WHEN_DISABLED] ?: false,
            networkScope = prefs[NETWORK_SCOPE]?.let { stored ->
                // A value written by a future app version that this build doesn't know
                // about falls back to the documented default rather than crashing.
                runCatching { NetworkScope.valueOf(stored) }.getOrDefault(NetworkScope.WIFI_ONLY)
            } ?: NetworkScope.WIFI_ONLY,
            ssidWhitelist = prefs[SSID_WHITELIST] ?: emptySet(),
            pingTargetHost = prefs[PING_TARGET_HOST]?.takeIf { it.isNotBlank() }
                ?: ProbeTarget.DEFAULT_HOST,
            // A value outside the slider's own range can only get into the store from a
            // future app version's wider range (or direct file tampering) -- coerced back
            // into range rather than trusted verbatim, same reasoning as networkScope's
            // valueOf fallback above.
            stepDelayMs = prefs[STEP_DELAY_MS]
                ?.coerceIn(UplinkPreferences.STEP_DELAY_RANGE_MS)
                ?: ProbeCycleRunner.DEFAULT_STEP_DELAY_MS,
        )
    }

    override suspend fun setMasterToggleEnabled(enabled: Boolean) {
        dataStore.edit { it[MASTER_TOGGLE_ENABLED] = enabled }
    }

    override suspend fun setHideWhenDisabled(hide: Boolean) {
        dataStore.edit { it[HIDE_WHEN_DISABLED] = hide }
    }

    override suspend fun setNetworkScope(scope: NetworkScope) {
        dataStore.edit { it[NETWORK_SCOPE] = scope.name }
    }

    override suspend fun setSsidWhitelist(ssids: Set<String>) {
        dataStore.edit { it[SSID_WHITELIST] = ssids }
    }

    override suspend fun setPingTargetHost(host: String) {
        dataStore.edit { it[PING_TARGET_HOST] = host }
    }

    override suspend fun setStepDelayMs(delayMs: Long) {
        dataStore.edit { it[STEP_DELAY_MS] = delayMs.coerceIn(UplinkPreferences.STEP_DELAY_RANGE_MS) }
    }

    companion object {
        private val MASTER_TOGGLE_ENABLED = booleanPreferencesKey("master_toggle_enabled")
        private val HIDE_WHEN_DISABLED = booleanPreferencesKey("hide_when_disabled")
        private val NETWORK_SCOPE = stringPreferencesKey("network_scope")
        private val SSID_WHITELIST = stringSetPreferencesKey("ssid_whitelist")
        private val PING_TARGET_HOST = stringPreferencesKey("ping_target_host")
        private val STEP_DELAY_MS = longPreferencesKey("step_delay_ms")
    }
}
