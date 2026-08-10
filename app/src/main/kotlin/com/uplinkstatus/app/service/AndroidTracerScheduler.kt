package com.uplinkstatus.app.service

import android.os.Handler
import com.uplinkstatus.core.tracer.ScheduledTask
import com.uplinkstatus.core.tracer.TracerScheduler

/**
 * Production [TracerScheduler]: `Handler.postDelayed`, per the spec's Technical Notes.
 *
 * Deliberately bound to a caller-supplied [Handler] rather than constructing
 * `Handler(Looper.getMainLooper())` itself. The spec's Technical Notes describe this as
 * running "on the main looper," but [com.uplinkstatus.core.probe.Prober.probe] is a
 * *blocking* call (TCP connect, up to 1000ms) that
 * [com.uplinkstatus.core.tracer.ProbeCycleRunner] invokes synchronously on whatever thread
 * drives it — including from inside this scheduler's own `postDelayed` callback, both for a
 * completed step and for a failure retry (paced by a fixed floor delay, not zero — see
 * `ProbeCycleRunner.FAILURE_RETRY_DELAY_MS`'s doc). Binding that to the real main-thread
 * `Looper` would block the UI thread for the duration of every probe attempt, repeating
 * every retry for as long as a sustained outage lasts — a real ANR risk even though each
 * individual block is bounded, not indefinite. [UplinkStatusService] instead supplies a
 * `Handler` on a dedicated background `HandlerThread`, which preserves the spec's actual
 * intent (a lightweight Handler loop, not a raw Timer/animation loop, and still subject to
 * Doze throttling while the screen is off — "no wake lock" still holds) without risking the
 * UI thread. See `notes/dev/uplink-status-indicator-spec.md`'s Technical Notes, updated in
 * this stage to reflect this correction.
 */
class AndroidTracerScheduler(
    private val handler: Handler,
) : TracerScheduler {
    override fun postDelayed(delayMs: Long, action: () -> Unit): ScheduledTask {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return ScheduledTask { handler.removeCallbacks(runnable) }
    }
}
