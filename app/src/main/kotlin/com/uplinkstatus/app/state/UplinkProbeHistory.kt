package com.uplinkstatus.app.state

import android.os.SystemClock
import com.uplinkstatus.core.history.ProbeHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The rolling record of what the app's **real** probes actually did, published so the settings
 * screen's history graphs can render it live — the same shape, and for the same reason, as
 * [UplinkIconDisplay]: a process-wide singleton the service feeds and Compose observes,
 * because the probe results exist inside a foreground service that the UI has no other handle
 * on.
 *
 * Fed from exactly one place ([com.uplinkstatus.app.service.UplinkNotificationController.onEvent],
 * the one function that already sees every [com.uplinkstatus.core.tracer.CycleEvent]), and only
 * ever from a real probe attempt:
 *
 * - a probe-success ack -> [recordSuccess] with its measured latency,
 * - a freeze -> [recordFailure], once per failed attempt including the immediate retries of a
 *   sustained outage,
 * - the automatic ("fake") ack of the ping/ping/fake cycle -> **nothing at all**. It is a
 *   timer, not a probe: counting it would inflate the success percentage with an attempt that
 *   never happened and, having no latency of its own, could only contribute a measurement
 *   nobody made.
 *
 * ### Lifetime
 * In memory only. There is no DataStore key, no file, nothing to restore from — per spec the
 * sample history is session-only, exactly like [com.uplinkstatus.core.tracer.AckTracer]'s bar
 * position, and the honest way to guarantee that is to have no persistence path to forget to
 * skip. Note this is the *process*'s lifetime, not the probe cycle's: the history deliberately
 * survives the cycle stopping and restarting (leaving and re-entering `ENABLED` when a network
 * drops out of scope and comes back), since the failures around exactly that transition are
 * what a connectivity history is for. [reset] — the user's explicit action — is the only thing
 * that clears it early.
 *
 * ### Time
 * Timestamps come from [SystemClock.elapsedRealtime], not `System.currentTimeMillis`: the
 * window is a duration between samples, and a wall clock that an NTP correction or a timezone
 * change can move backwards would corrupt the ordering the whole window depends on.
 */
object UplinkProbeHistory {
    private val state = MutableStateFlow(ProbeHistory())

    val history: StateFlow<ProbeHistory> = state.asStateFlow()

    /** A real probe answered in [latencyMs]. */
    fun recordSuccess(latencyMs: Long, timestampMs: Long = nowMs()) {
        // update(), not a plain `value =` read-modify-write: samples arrive on the probe
        // worker thread while the settings screen can call reset()/setWindowMs() from the main
        // thread, and a lost update there would silently drop a real measurement.
        state.update { it.recordSuccess(timestampMs, latencyMs) }
    }

    /** A real probe attempt failed. */
    fun recordFailure(timestampMs: Long = nowMs()) {
        state.update { it.recordFailure(timestampMs) }
    }

    /** Applies the user's history-window preference, pruning anything it has already outlived
     * so a shortened window takes effect immediately rather than at the next probe. */
    fun setWindowMs(windowMs: Long) {
        state.update { if (it.windowMs == windowMs) it else it.withWindowMs(windowMs) }
    }

    /** The user's explicit "reset history" action: drops every accumulated sample at once,
     * keeping the configured window, without needing the service restarted. */
    fun reset() {
        state.update { it.cleared() }
    }

    /** Process-wide singleton; tests must reset it between runs. Production code never calls
     * this — [reset] is the user-facing action and deliberately keeps the window. */
    internal fun resetForTest() {
        state.value = ProbeHistory()
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}
