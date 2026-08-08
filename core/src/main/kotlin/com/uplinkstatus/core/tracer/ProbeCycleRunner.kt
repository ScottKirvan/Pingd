package com.uplinkstatus.core.tracer

import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober

/**
 * Drives the probe-driven tracer cycle described in the spec's "Core Mechanism": a repeating
 * **ping, ping, fake** sequence, not a strict 1:1 ping/fake alternation --
 *
 * 1. Open a probe (TCP connect, 1000ms timeout).
 * 2. Probe succeeds -> ack -> tracer advances one step.
 * 3. Wait [stepDelayMs] -> a second real probe (back to step 1).
 * 4. Second probe succeeds -> ack -> tracer advances another step.
 * 5. Wait [stepDelayMs] -> ack (automatic, no real probe) -> tracer advances a third step.
 * 6. Wait [stepDelayMs] (no ack) -> back to step 1.
 *
 * Two real probes per automatic ack, not one, deliberately: a strict ping/fake alternation
 * makes every freeze land in the same phase of the bounce, so an outage always stops the
 * tracer on the same handful of bars -- see [realProbesSinceFakeAck].
 *
 * On probe timeout/failure: no ack fires, the tracer freezes at its current position, and
 * the loop retries immediately with a new probe (same timeout, no back-off, and no
 * [stepDelayMs] wait before the retry) until one succeeds — at which point acks resume and
 * the tracer continues from wherever it froze. A failure does not consume a slot in the
 * ping/ping/fake sequence: [realProbesSinceFakeAck] only advances on a real ack, so an
 * outage mid-sequence resumes at the same point once connectivity returns.
 *
 * This class owns no real threads and never blocks on wall-clock time itself: the
 * [stepDelayMs] waits are delegated to an injected [TracerScheduler], and probe attempts are
 * delegated to an injected [Prober]. That's what makes the whole cycle - including its timing
 * and sequencing - unit-testable on the plain JVM with fakes that respond instantly, per the
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
 *
 * ### Live-updatable target and pacing
 *
 * [target] and [stepDelayMs] are settable ([updateTarget]/[updateStepDelayMs]) from any
 * thread, at any time, precisely so a caller whose visibility logic treats "already running"
 * as a no-op (see `UplinkStatusService.applyVisibility`'s `ENABLED` branch) still has a way to
 * push a settings change into a cycle that is already underway. Without this, a ping-target
 * or step-delay preference change made while the tracer was already running would silently
 * do nothing until some unrelated event happened to stop and restart the cycle — the actual
 * bug this pair of methods exists to fix. Deliberately *not* routed through a full
 * stop-then-[start], which would also reset bar position and the notification's remembered
 * session state ([CycleListener]-side), disruption this class has no reason to cause for what
 * is, from the tracer's own point of view, just a pacing/target tweak.
 */
class ProbeCycleRunner(
    private val prober: Prober,
    initialTarget: ProbeTarget,
    private val scheduler: TracerScheduler,
    private val listener: CycleListener,
    private val tracer: AckTracer = AckTracer(),
    /** The user-configurable pacing wait between every step -- real ack, automatic ack, and
     * before the next probe alike. 0 means back-to-back with no added wait ("free wheeling");
     * never applied before a failure retry, which stays immediate per spec regardless of this
     * value. Must be non-negative; a negative delay isn't a real duration and every scheduler
     * implementation this project uses treats it as an error or as an unintended "immediately"
     * that would silently defeat the pacing this setting exists to provide. */
    initialStepDelayMs: Long = DEFAULT_STEP_DELAY_MS,
) {

    init {
        // A property initializer assigns straight to the backing field in Kotlin, bypassing
        // any custom setter entirely -- confirmed against the compiled bytecode, not assumed
        // -- so stepDelayMs's own setter below only ever validates a *later* updateStepDelayMs
        // call. The constructor's own value needs this separate check to be rejected at all.
        require(initialStepDelayMs >= 0) {
            "stepDelayMs must be non-negative, was $initialStepDelayMs"
        }
    }

    /** The probe target, read fresh on every attempt in [runProbeAttempts]. `@Volatile`
     * because [updateTarget] is called from whichever thread reacts to a preferences change
     * (not the thread running the cycle), and that write has to become visible to the next
     * read without both sides sharing a lock -- the read happens deliberately outside
     * [lifecycleLock] (see [runProbeAttempts]'s doc), the same reason [running] is `@Volatile`
     * rather than lock-guarded. */
    @Volatile
    var target: ProbeTarget = initialTarget
        private set

    /** The pacing wait, read fresh each time a step schedules the next one. `@Volatile` for
     * the same cross-thread-write reason as [target]. The custom setter validates every
     * later [updateStepDelayMs] call; the constructor's own initial value is validated
     * separately, in `init` above -- see that block's doc for why both are needed. */
    @Volatile
    var stepDelayMs: Long = initialStepDelayMs
        private set(value) {
            require(value >= 0) { "stepDelayMs must be non-negative, was $value" }
            field = value
        }

    /** Pushes a new probe target into an already-running cycle -- see the class doc's
     * "Live-updatable target and pacing" section. A no-op call (the same target again) is
     * harmless: the field write is unconditional and cheap either way. */
    fun updateTarget(newTarget: ProbeTarget) {
        target = newTarget
    }

    /** Pushes a new pacing delay into an already-running cycle -- see the class doc's
     * "Live-updatable target and pacing" section. Throws the same way the constructor does
     * for a negative value, via [stepDelayMs]'s own setter. */
    fun updateStepDelayMs(newStepDelayMs: Long) {
        stepDelayMs = newStepDelayMs
    }

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

    /** How many real probe successes have happened since the last automatic ack, 0 or 1 --
     * only ever touched under [lifecycleLock], alongside [pendingTask]. Reaching
     * [REAL_PROBES_PER_FAKE_ACK] is what triggers the automatic ack in [onStepDelayElapsed];
     * the automatic ack itself resets this back to 0. A failed probe never touches this field
     * at all (see the class doc's failure-retry paragraph), which is what makes an outage
     * mid-sequence resume at the same point rather than restarting the pattern. */
    private var realProbesSinceFakeAck: Int = 0

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

    /** A real probe succeeded: ack, count it toward [realProbesSinceFakeAck], then schedule
     * [onStepDelayElapsed] to decide what happens once [stepDelayMs] has passed. */
    private fun onProbeSucceeded(result: ProbeResult.Success) {
        synchronized(lifecycleLock) {
            if (!running) return
            val position = tracer.ack()
            listener.onEvent(CycleEvent.Advanced(position, AckSource.PROBE_SUCCESS, result.latencyMs))
            realProbesSinceFakeAck++
            pendingTask = scheduler.postDelayed(stepDelayMs) {
                onStepDelayElapsed()
            }
        }
    }

    /**
     * Fires [stepDelayMs] after *any* completed step — a real ack or the automatic ack alike
     * — and is the one place that decides what the next step is, purely from
     * [realProbesSinceFakeAck]: a second real probe (if only one has happened since the last
     * automatic ack), or the automatic ack (if two have). This is what makes ping/ping/fake a
     * single loop rather than two near-duplicate step-3/step-4 methods that would otherwise
     * need to stay in sync by hand.
     *
     * The real probe branch runs outside [lifecycleLock] deliberately, same as [start]: it's
     * about to block on [Prober.probe], and holding the lock across that would reintroduce
     * the "stop has to wait out the outage" problem the class doc's threading contract exists
     * to prevent.
     */
    private fun onStepDelayElapsed() {
        val nextStepIsFakeAck = synchronized(lifecycleLock) {
            if (!running) return
            pendingTask = null
            realProbesSinceFakeAck >= REAL_PROBES_PER_FAKE_ACK
        }
        if (nextStepIsFakeAck) {
            onFakeAckDue()
        } else {
            runProbeAttempts()
        }
    }

    /** The automatic ack: no real probe, just an ack and a reset of [realProbesSinceFakeAck]
     * back to 0 so the next [onStepDelayElapsed] sends the cycle back to a real probe. */
    private fun onFakeAckDue() {
        synchronized(lifecycleLock) {
            if (!running) return
            val position = tracer.ack()
            listener.onEvent(CycleEvent.Advanced(position, AckSource.AUTOMATIC))
            realProbesSinceFakeAck = 0
            pendingTask = scheduler.postDelayed(stepDelayMs) {
                onStepDelayElapsed()
            }
        }
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
        /** The number of real probe successes per automatic ack -- see the class doc's
         * "Two real probes per automatic ack, not one, deliberately" paragraph. */
        const val REAL_PROBES_PER_FAKE_ACK: Int = 2

        /** The default step delay (see [stepDelayMs]'s doc) -- unchanged from the fixed value
         * every step used before this became user-configurable, so a fresh install's behavior
         * doesn't silently change out from under anyone who never touches the new setting. */
        const val DEFAULT_STEP_DELAY_MS: Long = 500L
    }
}
