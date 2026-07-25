package com.uplinkstatus.core.tracer

import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober

/**
 * Drives the probe-driven tracer cycle described in the spec's "Core Mechanism":
 *
 * 1. Open a probe (TCP connect, 1000ms timeout).
 * 2. Probe succeeds -> ack -> tracer advances one step.
 * 3. Wait 500ms -> ack (automatic) -> tracer advances another step.
 * 4. Wait another 500ms (no ack).
 * 5. Back to step 1.
 *
 * On probe timeout/failure: no ack fires, the tracer freezes at its current position, and
 * the loop retries immediately with a new probe (same timeout, no back-off) until one
 * succeeds — at which point acks resume and the tracer continues from wherever it froze.
 *
 * This class owns no real threads and never blocks on wall-clock time itself: the 500ms
 * waits are delegated to an injected [TracerScheduler], and probe attempts are delegated
 * to an injected [Prober]. That's what makes the whole cycle - including its timing and
 * sequencing - unit-testable on the plain JVM with fakes that respond instantly, per the
 * brief's requirement not to make tests slow/flaky by sleeping for real intervals.
 *
 * Failure retries are a plain `while` loop rather than recursive calls, specifically so a
 * sustained outage (many consecutive probe failures) can't grow the call stack — with
 * recursion instead, an outage lasting long enough would eventually overflow the stack in
 * production, since each failed probe would add another frame.
 */
class ProbeCycleRunner(
    private val prober: Prober,
    private val target: ProbeTarget,
    private val scheduler: TracerScheduler,
    private val listener: CycleListener,
    private val tracer: AckTracer = AckTracer(),
) {

    @Volatile
    private var running: Boolean = false

    private var pendingTask: ScheduledTask? = null

    /** Current tracer position, readable at any time (e.g. by a consumer that wants the
     * icon state without waiting for the next event). */
    val currentPosition: BarPosition
        get() = tracer.position

    /** Whether the cycle is currently running (started and not yet stopped). */
    val isRunning: Boolean
        get() = running

    /** Starts the cycle. No-op if already running. */
    fun start() {
        if (running) return
        running = true
        runProbeAttempts()
    }

    /** Stops the cycle: cancels any pending scheduled step and prevents further probe
     * attempts. The tracer position is left exactly where it was — stopping is not a
     * distinct freeze state, it simply means the cycle isn't driving the tracer anymore
     * (e.g. because the caller transitioned out of ENABLED). */
    fun stop() {
        running = false
        pendingTask?.cancel()
        pendingTask = null
    }

    /**
     * Step 1 (and the immediate-retry loop around it): attempt probes back-to-back with
     * no delay until one succeeds, emitting a [CycleEvent.Frozen] for each failure. Exits
     * (without looping) as soon as a probe succeeds, handing off to [onProbeSucceeded]
     * which schedules the rest of the cycle asynchronously.
     */
    private fun runProbeAttempts() {
        while (running) {
            when (val result = prober.probe(target)) {
                is ProbeResult.Success -> {
                    onProbeSucceeded(result)
                    return
                }

                ProbeResult.Failure -> {
                    listener.onEvent(CycleEvent.Frozen(tracer.position, FreezeReason.PROBE_FAILURE))
                    // No scheduled delay: retry immediately, no back-off, per spec.
                }

                ProbeResult.DnsResolutionFailure -> {
                    listener.onEvent(
                        CycleEvent.Frozen(tracer.position, FreezeReason.DNS_RESOLUTION_FAILURE),
                    )
                }
            }
        }
    }

    /** Step 2: the probe-success ack, then schedules step 3 (the automatic ack) 500ms out. */
    private fun onProbeSucceeded(result: ProbeResult.Success) {
        val position = tracer.ack()
        listener.onEvent(CycleEvent.Advanced(position, AckSource.PROBE_SUCCESS))

        pendingTask = scheduler.postDelayed(AUTO_ACK_DELAY_MS) {
            onAutoAckDue()
        }
    }

    /** Step 3: the automatic ack, then schedules step 4 (the non-ack gap) 500ms out. */
    private fun onAutoAckDue() {
        if (!running) return
        val position = tracer.ack()
        listener.onEvent(CycleEvent.Advanced(position, AckSource.AUTOMATIC))

        pendingTask = scheduler.postDelayed(GAP_DELAY_MS) {
            onGapElapsed()
        }
    }

    /** Step 4 -> step 5: the gap produces no ack; once it elapses, go back to step 1. */
    private fun onGapElapsed() {
        if (!running) return
        pendingTask = null
        runProbeAttempts()
    }

    companion object {
        const val AUTO_ACK_DELAY_MS: Long = 500L
        const val GAP_DELAY_MS: Long = 500L
    }
}
