package com.bojustudio.pingd.app.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [PingdPreferencesRepository] test double, local to `:app`'s test source set --
 * used by both [com.bojustudio.pingd.app.service.PingdStatusServiceTest] and
 * `SettingsScreenTest`. No real DataStore file, no real disk I/O; backed by a
 * [MutableStateFlow] so it always has a current value ready for `combine`/`collect` (or
 * Compose's `collectAsState`) to read synchronously, the same way
 * [com.bojustudio.pingd.app.service.FakeScheduler] avoids real waiting.
 *
 * Lives in this package (rather than the `service` test package the way `FakeProber` and
 * `FakeScheduler` do) since it's a fake of a `prefs`-package interface shared by tests in
 * both `service` and `ui` -- same reasoning `FakeProber`/`FakeScheduler` give for living
 * next to what they fake, just applied to a fake two different test suites need.
 */
internal class FakePingdPreferencesRepository(
    initial: PingdPreferences = PingdPreferences(),
) : PingdPreferencesRepository {

    private val state = MutableStateFlow(initial)

    override val preferencesFlow: Flow<PingdPreferences> = state.asStateFlow()

    val current: PingdPreferences get() = state.value

    override suspend fun setMasterToggleEnabled(enabled: Boolean) {
        state.value = state.value.copy(masterToggleEnabled = enabled)
    }

    override suspend fun setHideWhenDisabled(hide: Boolean) {
        state.value = state.value.copy(hideWhenDisabled = hide)
    }

    override suspend fun setNetworkScope(scope: NetworkScope) {
        state.value = state.value.copy(networkScope = scope)
    }

    override suspend fun setSsidWhitelist(ssids: Set<String>) {
        state.value = state.value.copy(ssidWhitelist = ssids)
    }

    override suspend fun setPingTargetHost(host: String) {
        state.value = state.value.copy(pingTargetHost = host)
    }

    override suspend fun setStepDelayMs(delayMs: Long) {
        state.value = state.value.copy(stepDelayMs = delayMs.coerceIn(PingdPreferences.STEP_DELAY_RANGE_MS))
    }

    override suspend fun setHistoryWindowMs(windowMs: Long) {
        state.value = state.value.copy(
            historyWindowMs = windowMs.coerceIn(PingdPreferences.HISTORY_WINDOW_RANGE_MS),
        )
    }
}
