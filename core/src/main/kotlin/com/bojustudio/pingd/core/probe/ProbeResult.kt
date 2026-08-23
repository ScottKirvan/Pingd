package com.bojustudio.pingd.core.probe

/**
 * Outcome of one probe attempt (a TCP connect-time "ping" — see [ProbeTarget]).
 *
 * [DnsResolutionFailure] is intentionally a distinct case from [Failure], per the spec:
 * "If the custom override is a hostname and it fails to resolve, treat that as a distinct
 * 'can't resolve target' condition rather than folding it into the generic probe-failure
 * case — a DNS problem and a network-down problem shouldn't look the same to the user."
 * Both cases cause the tracer to freeze (see the `tracer` package), but callers (e.g. a
 * future notification-text layer) can tell them apart.
 */
sealed interface ProbeResult {
    /** The TCP connect succeeded. [latencyMs] is how long `connect()` took — the spec's
     * only latency signal, deliberately not scaled or interpreted any further here. */
    data class Success(val latencyMs: Long) : ProbeResult

    /** Connect attempt timed out or otherwise failed (connection refused, host unreachable,
     * etc.) after the hostname resolved successfully. */
    data object Failure : ProbeResult

    /** The target hostname itself could not be resolved to an address. Distinct from
     * [Failure] per spec — see class doc above. */
    data object DnsResolutionFailure : ProbeResult
}
