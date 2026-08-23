package com.bojustudio.pingd.core.tracer

/**
 * Abstraction over "run this after N milliseconds." Production wiring (Stage 2) will
 * implement this with Android's `Handler.postDelayed` on the main looper, per the spec's
 * Technical Notes ("Handler/postDelayed loop on the main looper; no wake lock").
 *
 * This exists so [ProbeCycleRunner] never calls `Thread.sleep` or otherwise blocks on
 * real wall-clock time itself — it just asks the scheduler to call it back later. Unit
 * tests inject a fake scheduler that captures the callback and lets the test fire it
 * synchronously and instantly, so the 500ms/500ms ack-cycle timing tests run in
 * microseconds rather than actually sleeping for a real second per test.
 */
fun interface TracerScheduler {
    fun postDelayed(delayMs: Long, action: () -> Unit): ScheduledTask
}

/** A handle to a scheduled callback, so it can be cancelled (e.g. when [ProbeCycleRunner]
 * is stopped mid-cycle, such as a transition out of ENABLED). */
fun interface ScheduledTask {
    fun cancel()
}
