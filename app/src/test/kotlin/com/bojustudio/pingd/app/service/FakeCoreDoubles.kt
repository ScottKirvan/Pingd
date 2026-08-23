package com.bojustudio.pingd.app.service

import com.bojustudio.pingd.core.probe.ProbeResult
import com.bojustudio.pingd.core.probe.ProbeTarget
import com.bojustudio.pingd.core.probe.Prober
import com.bojustudio.pingd.core.tracer.ScheduledTask
import com.bojustudio.pingd.core.tracer.TracerScheduler

/**
 * Small [:core] test doubles, local to `:app`'s test source set. `:core`'s own fakes
 * ([com.bojustudio.pingd.core.fakes.FakeProber], [com.bojustudio.pingd.core.fakes.FakeTracerScheduler])
 * live in `core/src/test`, which isn't a dependency `:app` can reach into (Gradle doesn't
 * expose one module's test sources to another's without a `java-test-fixtures` setup, which
 * would be more machinery than two small functional-interface fakes justify for this
 * stage). These mirror them just enough for [PingdStatusServiceTest]'s needs: no real
 * sockets, no real waiting.
 *
 * Mirrors `:core`'s own [com.bojustudio.pingd.core.fakes.FakeProber] shape (Stage 5): a scripted
 * queue of results, one per call, falling back to [defaultResult] once drained -- so a
 * service-level test can drive a real [com.bojustudio.pingd.core.tracer.ProbeCycleRunner] through
 * a specific sequence of failures (including a mix of generic and DNS-resolution failures)
 * before it eventually succeeds, and prove the resulting notification/no-back-off behavior
 * end to end, not just at [com.bojustudio.pingd.core.tracer.ProbeCycleRunnerTest]'s or
 * [PingdNotificationControllerTest]'s unit level.
 */
internal class FakeProber(
    vararg scriptedResults: ProbeResult,
    private val defaultResult: ProbeResult = ProbeResult.Success(1),
) : Prober {

    private val queue = ArrayDeque(scriptedResults.toList())

    var callCount: Int = 0
        private set

    /** Every [ProbeTarget] actually probed, in call order -- lets a test prove a running
     * cycle picked up a new target live, not just that the service's own field changed. */
    val targetsProbed = mutableListOf<ProbeTarget>()

    override fun probe(target: ProbeTarget): ProbeResult {
        callCount++
        targetsProbed += target
        return if (queue.isNotEmpty()) queue.removeFirst() else defaultResult
    }
}

/** Captures every scheduled callback instead of running it; the test decides if/when a
 * delay "elapses" by invoking [fireAll] or simply never firing it (sufficient for tests
 * that only care about the *first* notification a transition posts). */
internal class FakeScheduler : TracerScheduler {
    val scheduled = mutableListOf<() -> Unit>()

    /** Every delay value passed to [postDelayed], in call order -- lets a test prove a
     * running cycle picked up a new step delay live, not just that the service's own field
     * changed. */
    val delays = mutableListOf<Long>()

    override fun postDelayed(delayMs: Long, action: () -> Unit): ScheduledTask {
        delays += delayMs
        scheduled += action
        return ScheduledTask { scheduled.remove(action) }
    }
}
