package com.uplinkstatus.app.service

import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober
import com.uplinkstatus.core.tracer.ScheduledTask
import com.uplinkstatus.core.tracer.TracerScheduler

/**
 * Small [:core] test doubles, local to `:app`'s test source set. `:core`'s own fakes
 * ([com.uplinkstatus.core.fakes.FakeProber], [com.uplinkstatus.core.fakes.FakeTracerScheduler])
 * live in `core/src/test`, which isn't a dependency `:app` can reach into (Gradle doesn't
 * expose one module's test sources to another's without a `java-test-fixtures` setup, which
 * would be more machinery than two small functional-interface fakes justify for this
 * stage). These mirror them just enough for [UplinkStatusServiceTest]'s needs: no real
 * sockets, no real waiting.
 *
 * Mirrors `:core`'s own [com.uplinkstatus.core.fakes.FakeProber] shape (Stage 5): a scripted
 * queue of results, one per call, falling back to [defaultResult] once drained -- so a
 * service-level test can drive a real [com.uplinkstatus.core.tracer.ProbeCycleRunner] through
 * a specific sequence of failures (including a mix of generic and DNS-resolution failures)
 * before it eventually succeeds, and prove the resulting notification/no-back-off behavior
 * end to end, not just at [com.uplinkstatus.core.tracer.ProbeCycleRunnerTest]'s or
 * [UplinkNotificationControllerTest]'s unit level.
 */
internal class FakeProber(
    vararg scriptedResults: ProbeResult,
    private val defaultResult: ProbeResult = ProbeResult.Success(1),
) : Prober {

    private val queue = ArrayDeque(scriptedResults.toList())

    var callCount: Int = 0
        private set

    override fun probe(target: ProbeTarget): ProbeResult {
        callCount++
        return if (queue.isNotEmpty()) queue.removeFirst() else defaultResult
    }
}

/** Captures every scheduled callback instead of running it; the test decides if/when a
 * delay "elapses" by invoking [fireAll] or simply never firing it (sufficient for tests
 * that only care about the *first* notification a transition posts). */
internal class FakeScheduler : TracerScheduler {
    val scheduled = mutableListOf<() -> Unit>()

    override fun postDelayed(delayMs: Long, action: () -> Unit): ScheduledTask {
        scheduled += action
        return ScheduledTask { scheduled.remove(action) }
    }
}
