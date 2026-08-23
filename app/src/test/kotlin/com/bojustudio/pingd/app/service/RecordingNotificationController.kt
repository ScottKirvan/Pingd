package com.bojustudio.pingd.app.service

import android.content.Context
import com.bojustudio.pingd.core.tracer.CycleEvent

/**
 * A thin [PingdNotificationController] subclass that records every [CycleEvent] it receives
 * (in order) before delegating to the real implementation via `super.onEvent(event)`.
 *
 * This exists specifically so [PingdStatusServiceTest] can prove Stage 5's
 * DNS-vs-generic-failure notification fix, and the pre-existing no-back-off retry
 * behavior, hold *end to end* — through a real [com.bojustudio.pingd.core.tracer.ProbeCycleRunner]
 * created by a real, running [PingdStatusService] — rather than only at
 * [PingdNotificationControllerTest]'s standalone unit level (which drives the controller
 * directly with hand-built [CycleEvent]s, never through an actual cycle/service). Because it
 * delegates to `super`, [notifyCallCount] (inherited) and the real posted notification
 * (readable via the shadowed `NotificationManager`, same as in the other service tests) both
 * still reflect real behavior -- this class only adds observability, it changes nothing about
 * what gets posted or suppressed.
 */
internal class RecordingNotificationController(context: Context) : PingdNotificationController(context) {

    val events = mutableListOf<CycleEvent>()

    override fun onEvent(event: CycleEvent) {
        events += event
        super.onEvent(event)
    }
}
