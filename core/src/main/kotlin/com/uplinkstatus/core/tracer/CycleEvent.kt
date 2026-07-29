package com.uplinkstatus.core.tracer

/** Which of the cycle's two ack sources produced a [CycleEvent.Advanced]. */
enum class AckSource {
    /** Step 2 of the cycle: the probe itself succeeded. */
    PROBE_SUCCESS,

    /** Step 3 of the cycle: the automatic ack that fires ~500ms after a successful probe,
     * independent of any further network activity. */
    AUTOMATIC,
}

/** Why the tracer is frozen (not advancing) this attempt. Kept distinct per spec — a DNS
 * problem and a network-down problem shouldn't look the same to the user. */
enum class FreezeReason {
    /** Generic probe failure: timeout, connection refused, host unreachable, etc. */
    PROBE_FAILURE,

    /** The target hostname itself failed to resolve. */
    DNS_RESOLUTION_FAILURE,
}

/** Events emitted by [ProbeCycleRunner] as the cycle progresses. Consumers (e.g. a future
 * notification layer) should react to these — never poll on a bare timer tick, per spec:
 * "Only call notify() on an ack (tracer advance) or a state transition." */
sealed interface CycleEvent {
    /** The tracer advanced one step.
     *
     * [latencyMs] is the probe's connect-time latency when [source] is
     * [AckSource.PROBE_SUCCESS] (taken straight from the [com.uplinkstatus.core.probe.ProbeResult.Success]
     * that triggered this ack) and `null` when [source] is [AckSource.AUTOMATIC] — the
     * automatic ack is a timer, not a new probe, so there's no fresh latency to report at
     * that step. This exists so a consumer (Stage 2's notification layer) can render
     * accessibility text like "Uplink: connected, 42ms" without needing to poll the prober
     * itself or duplicate the cycle's probe-timing logic — latency naturally travels with
     * the event that already carries the position/source it belongs to. */
    data class Advanced(
        val position: BarPosition,
        val source: AckSource,
        val latencyMs: Long? = null,
    ) : CycleEvent

    /** No ack fired this attempt; the tracer remains at [position]. Emitted once per
     * failed probe attempt (including repeated immediate retries), not just once per
     * outage, so a listener can react to each attempt if it wants to. */
    data class Frozen(val position: BarPosition, val reason: FreezeReason) : CycleEvent
}

/**
 * Receives [CycleEvent]s from a running [ProbeCycleRunner].
 *
 * [onEvent] is called while the runner holds its internal lifecycle lock — that's what
 * guarantees no event is ever delivered after [ProbeCycleRunner.stop] returns, including one
 * produced by a probe that was already in flight. Implementations must therefore return
 * promptly (this is the "post a notification" step, not a place for slow or blocking work),
 * since a concurrent [ProbeCycleRunner.stop] waits on that lock for the duration of the
 * callback, and must not block on a lock that another thread could be holding while calling
 * [ProbeCycleRunner.stop].
 */
fun interface CycleListener {
    fun onEvent(event: CycleEvent)
}
