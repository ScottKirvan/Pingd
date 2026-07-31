package com.uplinkstatus.core.probe

/**
 * Performs one probe attempt against a [ProbeTarget] and reports the outcome.
 *
 * This is the seam that keeps the tracer/ack state machine (see the `tracer` package)
 * testable without real sockets: production code wires in [TcpConnectProber], unit tests
 * inject a fake that returns canned [ProbeResult]s instantly, so timing-sensitive tests
 * never actually wait on a socket or the network.
 *
 * [probe] is a blocking call (real implementations perform blocking I/O up to
 * `target.timeoutMs`); callers are responsible for not invoking it from a context where
 * blocking is unsafe (e.g. Android's main thread) — that's a wiring concern for whichever
 * later stage drives this loop, not something this pure-Kotlin module can enforce.
 */
fun interface Prober {
    fun probe(target: ProbeTarget): ProbeResult
}
