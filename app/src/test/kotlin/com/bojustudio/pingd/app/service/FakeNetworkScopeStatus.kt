package com.bojustudio.pingd.app.service

import com.bojustudio.pingd.app.state.NetworkScopeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [NetworkScopeStatus] test double for [PingdStatusServiceTest] -- replaces the old
 * `NetworkScopeStatus.inScope = true/false` singleton mutation these tests used before Stage 4
 * turned [NetworkScopeStatus] into a real per-instance interface. [inScope] keeps the same
 * settable-property ergonomics the tests already relied on; it's just an instance field now
 * instead of a process-wide global, so tests no longer need to reset a shared singleton
 * between runs.
 */
internal class FakeNetworkScopeStatus(
    initial: Boolean? = true,
) : NetworkScopeStatus {

    private val state = MutableStateFlow(initial)

    override val inScopeFlow: Flow<Boolean?> = state.asStateFlow()

    /** Nullable to let a test hold the service in the real "connectivity hasn't reported yet"
     * state that a fresh subscription starts in -- `null` is not "out of scope," and the
     * service must be able to tell the difference. */
    var inScope: Boolean?
        get() = state.value
        set(value) {
            state.value = value
        }
}
