package com.uplinkstatus.core.tracer

import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober

/**
 * A minimal, independent probe loop whose only job is to keep producing real [ProbeResult]s --
 * it drives no [AckTracer], posts no [CycleListener] event, and has no notion of bar position or
 * ping/ping/fake sequencing. `UplinkStatusService` uses one of these to keep the settings
 * screen's history graphs recording through a period the *visible* tracer ([ProbeCycleRunner])
 * is not running in -- currently `DISABLED` (network out of scope) -- per
 * `notes/dev/uplink-status-indicator-spec.md`'s "In-App History Graphs": an out-of-scope period
 * is exactly the kind of outage a connectivity history exists to show, not a reason for it to go
 * blind.
 *
 * Deliberately *not* built on [ProbeCycleRunner]. That class's failure retries are immediate
 * with no back-off, by design, for a live tracer outage (see its own class doc) -- but that
 * design assumes a failure that takes close to its own timeout to arrive, which a real "router
 * lost upstream" outage does. It does not hold for the specific case this loop exists to cover:
 * total connectivity loss (e.g. airplane mode), where a connect attempt fails almost instantly
 * because there is no interface to even try. An unthrottled retry loop against that would spin
 * as fast as the CPU allows for as long as the outage lasts -- directly working against the
 * app's own battery-conscious design. This class paces *every* attempt, success or failure
 * alike, by [retryDelayMs] instead -- callers must floor that value themselves (see
 * [MIN_RETRY_DELAY_MS]'s doc), specifically to protect someone who has the ordinary tracer's
 * pacing set to 0 ("free wheeling") and then loses connectivity entirely.
 *
 * Same threading contract as [ProbeCycleRunner]: [start] blocks its calling thread for as long
 * as it keeps running (though never for more than one probe's timeout at a time, since every
 * attempt here -- not just failures -- is paced), so it must be driven from a dedicated
 * background thread; [stop] is safe to call from any thread, never blocks, and guarantees no
 * further [Prober.probe] call and no further [onResult] callback once it returns -- including
 * discarding the result of a probe that was already in flight when [stop] was called.
 */
class BackgroundHistoryProbeLoop(
    private val prober: Prober,
    initialTarget: ProbeTarget,
    private val scheduler: TracerScheduler,
    private val onResult: (ProbeResult) -> Unit,
    initialRetryDelayMs: Long = MIN_RETRY_DELAY_MS,
) {

    init {
        // Same reasoning as ProbeCycleRunner's own init block: a property initializer assigns
        // straight to the backing field, bypassing retryDelayMs's custom setter below, so the
        // constructor's own value needs this separate check to be rejected at all.
        require(initialRetryDelayMs >= MIN_RETRY_DELAY_MS) {
            "retryDelayMs must be at least $MIN_RETRY_DELAY_MS, was $initialRetryDelayMs"
        }
    }

    /** The probe target, read fresh on every attempt. `@Volatile` for the same cross-thread-
     * write reason as [ProbeCycleRunner.target]. */
    @Volatile
    var target: ProbeTarget = initialTarget
        private set

    /** The pacing wait between every attempt, success or failure alike -- never below
     * [MIN_RETRY_DELAY_MS], enforced by this setter exactly like the constructor's own value
     * above. */
    @Volatile
    var retryDelayMs: Long = initialRetryDelayMs
        private set(value) {
            require(value >= MIN_RETRY_DELAY_MS) {
                "retryDelayMs must be at least $MIN_RETRY_DELAY_MS, was $value"
            }
            field = value
        }

    /** Pushes a new probe target into an already-running loop -- same reasoning as
     * [ProbeCycleRunner.updateTarget]. */
    fun updateTarget(newTarget: ProbeTarget) {
        target = newTarget
    }

    /** Pushes a new pacing delay into an already-running loop -- same reasoning as
     * [ProbeCycleRunner.updateStepDelayMs]. Throws the same way the constructor does for a
     * value below [MIN_RETRY_DELAY_MS]; the caller is responsible for flooring a raw preference
     * value before passing it here, exactly as it must before construction. */
    fun updateRetryDelayMs(newRetryDelayMs: Long) {
        retryDelayMs = newRetryDelayMs
    }

    /** Guards [running] and [pendingTask], never held across a [Prober.probe] call -- same
     * reasoning as [ProbeCycleRunner.lifecycleLock]. */
    private val lifecycleLock = Any()

    @Volatile
    private var running: Boolean = false

    private var pendingTask: ScheduledTask? = null

    /** Whether the loop is currently running (started and not yet stopped). */
    val isRunning: Boolean
        get() = running

    /** Starts the loop. No-op if already running. Blocks the calling thread for the first probe
     * attempt -- see the class doc's threading contract. */
    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
        }
        runOnce()
    }

    /** Stops the loop: cancels any pending scheduled attempt, prevents further probe attempts,
     * and prevents any further [onResult] callback -- including one that would otherwise have
     * been produced by a probe already in flight. Safe to call from any thread; never blocks on
     * the thread running the loop. */
    fun stop() {
        val task = synchronized(lifecycleLock) {
            running = false
            pendingTask.also { pendingTask = null }
        }
        task?.cancel()
    }

    /** One probe attempt, then -- unless [stop] landed while it was in flight -- reports the
     * result and schedules the next attempt after [retryDelayMs]. Every attempt is paced this
     * way, success or failure alike; unlike [ProbeCycleRunner] there is no "retry immediately"
     * branch here at all, per the class doc. */
    private fun runOnce() {
        if (!running) return
        val result = prober.probe(target)
        synchronized(lifecycleLock) {
            if (!running) return
            onResult(result)
            pendingTask = scheduler.postDelayed(retryDelayMs) { runOnce() }
        }
    }

    companion object {
        /** The minimum [retryDelayMs] a caller may configure, regardless of the user's own
         * step-delay preference (which can be set as low as 0, "free wheeling," for the visible
         * tracer). This loop exists specifically to keep probing during a total-connectivity-loss
         * outage, where a connect attempt fails almost instantly -- so without a floor, "free
         * wheeling" plus "no network at all" would spin this loop as fast as the CPU allows for
         * as long as the outage lasts. */
        const val MIN_RETRY_DELAY_MS: Long = 250L
    }
}
