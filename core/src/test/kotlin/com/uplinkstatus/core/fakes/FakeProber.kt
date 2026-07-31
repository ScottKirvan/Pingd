package com.uplinkstatus.core.fakes

import com.uplinkstatus.core.probe.ProbeResult
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober

/**
 * Test double for [Prober]: returns a scripted sequence of [ProbeResult]s, one per call,
 * falling back to [defaultResult] once the queue is drained. Never touches a real socket
 * or the network, so tests using it run instantly and deterministically.
 */
class FakeProber(
    vararg scriptedResults: ProbeResult,
    private val defaultResult: ProbeResult = ProbeResult.Failure,
) : Prober {

    private val queue = ArrayDeque(scriptedResults.toList())

    var callCount: Int = 0
        private set

    val targetsProbed = mutableListOf<ProbeTarget>()

    fun enqueue(result: ProbeResult) {
        queue.addLast(result)
    }

    override fun probe(target: ProbeTarget): ProbeResult {
        callCount++
        targetsProbed += target
        return if (queue.isNotEmpty()) queue.removeFirst() else defaultResult
    }
}
