package com.uplinkstatus.core.fakes

import com.uplinkstatus.core.tracer.ScheduledTask
import com.uplinkstatus.core.tracer.TracerScheduler

/**
 * Test double for [TracerScheduler]: instead of actually waiting [delayMs], it records the
 * scheduled call and holds the callback until the test explicitly fires it via
 * [fireNext]. This is what lets ack-cycle timing tests assert on exact delay values and
 * sequencing without ever sleeping for a real 500ms/1000ms.
 */
class FakeTracerScheduler : TracerScheduler {

    private data class Scheduled(val delayMs: Long, val action: () -> Unit)

    private val pending = ArrayDeque<Scheduled>()

    /** Every delay value passed to [postDelayed], in call order — lets tests assert the
     * exact 500/500 sequencing (or that no delay was ever scheduled, for the immediate
     * no-back-off retry case). */
    val history: MutableList<Long> = mutableListOf()

    var cancelledCount: Int = 0
        private set

    override fun postDelayed(delayMs: Long, action: () -> Unit): ScheduledTask {
        history += delayMs
        val scheduled = Scheduled(delayMs, action)
        pending.addLast(scheduled)
        return ScheduledTask {
            if (pending.remove(scheduled)) {
                cancelledCount++
            }
        }
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    val pendingCount: Int
        get() = pending.size

    /** Fires the single oldest pending scheduled callback, simulating that its delay has
     * elapsed. Fails loudly if nothing is pending, since that means the code under test
     * didn't schedule what the test expected. */
    fun fireNext() {
        val scheduled = pending.removeFirstOrNull()
            ?: error("FakeTracerScheduler.fireNext() called with no pending scheduled task")
        scheduled.action()
    }
}
