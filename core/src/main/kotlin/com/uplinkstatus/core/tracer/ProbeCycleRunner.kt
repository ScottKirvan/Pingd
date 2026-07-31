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
 *
 * ### Threading contract
 *
 * [start] blocks its calling thread for as long as probes keep failing (that's what "retry
 * immediately, no back-off" means when the prober is a blocking call), so it is expected to
 * be driven from a dedicated background thread. [stop] is deliberately the opposite: it is
 * safe to call from *any* thread, at any time, and it never waits on the thread running the
 * cycle. That asymmetry is the whole point — a caller must be able to end the cycle during a
 * sustained outage, when the thread inside [runProbeAttempts] is not going to become
 * available to accept any queued work of its own for as long as the outage lasts. Callers
 * must therefore invoke [stop] *directly*, never by posting it to the same queue/executor
 * that is running [start] (see `UplinkStatusService.stopCycle`'s comment for the concrete
 * Android case this rule came out of).
 *
 * Once [stop] returns, two things are guaranteed: no further [Prober.probe] call will be
 * made, and no further [CycleListener.onEvent] callback will be delivered — including from
 * the probe that was already in flight when [stop] was called (its result is simply
 * discarded). Delivery of the whole cycle therefore ends within, at most, one in-flight
 * probe's own timeout, not within however long the outage lasts. That guarantee is what
 * keeps a torn-down consumer (e.g. a stopped foreground service) from having a notification
 * resurrected underneath it by a cycle it already stopped.
 */
class ProbeCycleRunner(
    private val prober: Prober,
    private val target: ProbeTarget,
    private val scheduler: TracerScheduler,
    private val listener: CycleListener,
    private val tracer: AckTracer = AckTracer(),
) {

    /**
     * Guards [running], [pendingTask], and every [CycleListener] callback — never held
     * across a [Prober.probe] call, which is exactly what lets [stop] take it promptly even
     * while a probe is in flight (holding it across the probe would reintroduce the very
     * "stop has to wait out the outage" problem this design exists to prevent).
     *
     * Emitting events under this lock is deliberate: it's what upgrades "no listener
     * callback after [stop]" from a best-effort check-then-act into a real guarantee. The
     * obligations that puts on a listener (return promptly, don't block on a lock a
     * concurrent [stop] caller could hold) are documented on [CycleListener] itself.
     */
    private val lifecycleLock = Any()

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

    /** Starts the cycle. No-op if already running. Blocks the calling thread for as long as
     * probes keep failing — see the class doc's threading contract. */
    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
        }
        runProbeAttempts()
    }

    /** Stops the cycle: cancels any pending scheduled step, prevents further probe attempts,
     * and prevents any further [CycleListener] callback — including one that would otherwise
     * have been produced by a probe already in flight. The tracer position is left exactly
     * where it was — stopping is not a distinct freeze state, it simply means the cycle isn't
     * driving the tracer anymore (e.g. because the caller transitioned out of ENABLED).
     *
     * Safe to call from any thread, and never blocks on the thread running the cycle: it only
     * takes [lifecycleLock], which that thread never holds while probing. Call it directly —
     * posting it to the queue/executor that is running [start] would make it wait out the
     * outage it needs to interrupt. */
    fun stop() {
        val task = synchronized(lifecycleLock) {
            running = false
            pendingTask.also { pendingTask = null }
        }
        task?.cancel()
    }

    /**
     * Step 1 (and the immediate-retry loop around it): attempt probes back-to-back with
     * no delay until one succeeds, emitting a [CycleEvent.Frozen] for each failure. Exits
     * (without looping) as soon as a probe succeeds, handing off to [onProbeSucceeded]
     * which schedules the rest of the cycle asynchronously — or as soon as [stop] is called
     * from another thread, which is the only other way out of this loop during an outage
     * with no gap between retries.
     *
     * Every exit from the loop re-checks [running] *after* the blocking probe returns, not
     * just before it: a [stop] that landed while the probe was in flight must discard that
     * probe's result entirely rather than emit one last event into a listener whose owner
     * has already torn down.
     */
    private fun runProbeAttempts() {
        while (running) {
            when (val result = prober.probe(target)) {
                is ProbeResult.Success -> {
                    onProbeSucceeded(result)
                    return
                }

                ProbeResult.Failure -> {
                    if (!emitIfRunning(CycleEvent.Frozen(tracer.position, FreezeReason.PROBE_FAILURE))) return
                    // No scheduled delay: retry immediately, no back-off, per spec.
                }

                ProbeResult.DnsResolutionFailure -> {
                    val event = CycleEvent.Frozen(tracer.position, FreezeReason.DNS_RESOLUTION_FAILURE)
                    if (!emitIfRunning(event)) return
                }
            }
        }
    }

    /** Step 2: the probe-success ack, then schedules step 3 (the automatic ack) 500ms out. */
    private fun onProbeSucceeded(result: ProbeResult.Success) {
        synchronized(lifecycleLock) {
            if (!running) return
            val position = tracer.ack()
            listener.onEvent(CycleEvent.Advanced(position, AckSource.PROBE_SUCCESS, result.latencyMs))
            pendingTask = scheduler.postDelayed(AUTO_ACK_DELAY_MS) {
                onAutoAckDue()
            }
        }
    }

    /** Step 3: the automatic ack, then schedules step 4 (the non-ack gap) 500ms out. */
    private fun onAutoAckDue() {
        synchronized(lifecycleLock) {
            if (!running) return
            val position = tracer.ack()
            listener.onEvent(CycleEvent.Advanced(position, AckSource.AUTOMATIC))
            pendingTask = scheduler.postDelayed(GAP_DELAY_MS) {
                onGapElapsed()
            }
        }
    }

    /** Step 4 -> step 5: the gap produces no ack; once it elapses, go back to step 1. */
    private fun onGapElapsed() {
        synchronized(lifecycleLock) {
            if (!running) return
            pendingTask = null
        }
        runProbeAttempts()
    }

    /** Delivers [event] to [listener] unless the cycle has already been stopped, returning
     * whether it was delivered (i.e. whether the caller should keep going). The check and the
     * call happen under [lifecycleLock] together, so a [stop] on another thread can't slip
     * between them. */
    private fun emitIfRunning(event: CycleEvent): Boolean {
        synchronized(lifecycleLock) {
            if (!running) return false
            listener.onEvent(event)
            return true
        }
    }

    companion object {
        const val AUTO_ACK_DELAY_MS: Long = 500L
        const val GAP_DELAY_MS: Long = 500L
    }
}
