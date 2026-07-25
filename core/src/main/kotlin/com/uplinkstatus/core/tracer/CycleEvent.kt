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
    /** The tracer advanced one step. */
    data class Advanced(val position: BarPosition, val source: AckSource) : CycleEvent

    /** No ack fired this attempt; the tracer remains at [position]. Emitted once per
     * failed probe attempt (including repeated immediate retries), not just once per
     * outage, so a listener can react to each attempt if it wants to. */
    data class Frozen(val position: BarPosition, val reason: FreezeReason) : CycleEvent
}

/** Receives [CycleEvent]s from a running [ProbeCycleRunner]. */
fun interface CycleListener {
    fun onEvent(event: CycleEvent)
}
