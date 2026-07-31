package com.uplinkstatus.core.fakes

import com.uplinkstatus.core.tracer.CycleEvent
import com.uplinkstatus.core.tracer.CycleListener

/** Test double for [CycleListener]: just records every event, in order, for assertions. */
class RecordingCycleListener : CycleListener {
    val events: MutableList<CycleEvent> = mutableListOf()

    override fun onEvent(event: CycleEvent) {
        events += event
    }
}
