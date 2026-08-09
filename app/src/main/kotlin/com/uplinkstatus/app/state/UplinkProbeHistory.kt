package com.uplinkstatus.app.state

import android.os.SystemClock
import android.util.Log
import com.uplinkstatus.core.history.ProbeHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// TEMPORARY diagnostic instrumentation for the "graph clears on reconnect" investigation --
// not meant to ship. See debug/probe-history-clear-diagnostics. Surfaced two ways: `adb
// logcat -s UplinkProbeHistory` if available, and (since adb isn't always at hand mid-test) a
// small on-screen panel on the settings screen itself, fed by [UplinkProbeHistory.diagnosticLog]
// -- see [DiagnosticsPanel] in the ui package.
//   - "singleton created" / the on-screen session age resetting to ~0 -- should happen exactly
//     once per app launch. A second occurrence (or the on-screen age visibly jumping back to
//     "0s ago" instead of counting up smoothly) during one test session proves the process (and
//     this object) restarted, which is the only way this class's data can vanish other than an
//     explicit reset() call.
//   - "reset() called" would prove the button (or something calling the same function) fired
//     -- reset() itself is neutered below (state.update removed) so even if this does fire,
//     it cannot actually be the cause of a clear seen alongside this log line.
//   - "SHRANK" on a record*() call means the legitimate windowMs-based pruning dropped
//     samples on that call -- logs the before/after counts and the gap in timestamps so a
//     real prune-to-near-empty (e.g. a genuine outage longer than the window) is
//     distinguishable from anything else.
private const val DIAG_TAG = "UplinkProbeHistory"

/** Cap on [UplinkProbeHistory.diagnosticLog]'s retained entries -- just enough to see the
 * relevant sequence around one repro without scrolling forever. */
private const val DIAG_LOG_MAX_ENTRIES = 30

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

    /** DIAGNOSTIC ONLY (see top-of-file note). When this singleton was constructed -- read by
     * the on-screen diagnostics panel to show "session started Xs ago," live. If that number
     * ever jumps back down instead of counting up smoothly, the process restarted. */
    val diagnosticSessionStartMs: Long = SystemClock.elapsedRealtime()

    private val diagLogState = MutableStateFlow<List<String>>(emptyList())

    /** DIAGNOSTIC ONLY (see top-of-file note). The on-screen panel's feed -- newest last. */
    val diagnosticLog: StateFlow<List<String>> = diagLogState.asStateFlow()

    init {
        // See this file's top-of-file diagnostic note.
        diagLog("singleton created (identity=${System.identityHashCode(this)})", Log.WARN)
    }

    val history: StateFlow<ProbeHistory> = state.asStateFlow()

    /** A real probe answered in [latencyMs]. */
    fun recordSuccess(latencyMs: Long, timestampMs: Long = nowMs()) {
        // update(), not a plain `value =` read-modify-write: samples arrive on the probe
        // worker thread while the settings screen can call reset()/setWindowMs() from the main
        // thread, and a lost update there would silently drop a real measurement.
        diagUpdate("recordSuccess") { it.recordSuccess(timestampMs, latencyMs) }
    }

    /** A real probe attempt failed. */
    fun recordFailure(timestampMs: Long = nowMs()) {
        diagUpdate("recordFailure") { it.recordFailure(timestampMs) }
    }

    /** The master toggle flipped (off, or back on) — recorded so the history graphs can draw a
     * vertical marker at the point the whole app stopped or resumed measuring, distinct from an
     * ordinary gap in the data (see [ProbeHistory.recordMarker]'s doc). Called from
     * [com.uplinkstatus.app.service.UplinkStatusService], the one place that already observes
     * the persisted master-toggle preference changing. */
    fun recordMasterToggleTransition(timestampMs: Long = nowMs()) {
        diagUpdate("recordMasterToggleTransition") { it.recordMarker(timestampMs) }
    }

    /** Applies the user's history-window preference, pruning anything it has already outlived
     * so a shortened window takes effect immediately rather than at the next probe. */
    fun setWindowMs(windowMs: Long) {
        diagUpdate("setWindowMs($windowMs)") { if (it.windowMs == windowMs) it else it.withWindowMs(windowMs) }
    }

    /** The user's explicit "reset history" action: drops every accumulated sample at once,
     * keeping the configured window, without needing the service restarted.
     *
     * TEMPORARILY NEUTERED for the diagnostic build (see top-of-file note): logs loudly instead
     * of actually calling `state.update`, so if a clear is observed alongside this build, it is
     * conclusively *not* this function that did it. Restore the `state.update { it.cleared() }`
     * body before merging. */
    fun reset() {
        diagLog("reset() called -- NEUTERED for diagnostics, state NOT actually cleared", Log.ERROR)
    }

    /** Process-wide singleton; tests must reset it between runs. Production code never calls
     * this — [reset] is the user-facing action and deliberately keeps the window. */
    internal fun resetForTest() {
        state.value = ProbeHistory()
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    /** Same as a plain `state.update { transform(it) }`, plus diagnostic logging: every call
     * (so the full sequence of what happened is visible in logcat, not just the anomalies), and
     * a loud warning specifically when the retained sample count *drops* -- the signature of a
     * legitimate windowMs-based prune wiping out old data, as opposed to anything else. See
     * top-of-file note. */
    private inline fun diagUpdate(label: String, transform: (ProbeHistory) -> ProbeHistory) {
        state.update { before ->
            val after = transform(before)
            if (after.attemptCount < before.attemptCount) {
                diagLog(
                    "SHRANK on $label: attemptCount ${before.attemptCount} -> ${after.attemptCount}, " +
                        "markers ${before.markers.size} -> ${after.markers.size}, windowMs=${after.windowMs}",
                    Log.ERROR,
                )
            } else {
                diagLog("$label: attemptCount=${after.attemptCount} markers=${after.markers.size}", Log.DEBUG)
            }
            after
        }
    }

    /** DIAGNOSTIC ONLY (see top-of-file note). Writes to both logcat (if reachable) and
     * [diagnosticLog] (always reachable, straight from the settings screen). */
    private fun diagLog(message: String, priority: Int) {
        Log.println(priority, DIAG_TAG, message)
        val ageSeconds = (SystemClock.elapsedRealtime() - diagnosticSessionStartMs) / 1000
        diagLogState.update { (it + "+${ageSeconds}s: $message").takeLast(DIAG_LOG_MAX_ENTRIES) }
    }
}
