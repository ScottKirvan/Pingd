package com.bojustudio.pingd.core.fakes

import com.bojustudio.pingd.core.tracer.CycleEvent
import com.bojustudio.pingd.core.tracer.CycleListener

/** Test double for [CycleListener]: just records every event, in order, for assertions. */
class RecordingCycleListener : CycleListener {
    val events: MutableList<CycleEvent> = mutableListOf()

    override fun onEvent(event: CycleEvent) {
        events += event
    }
}
