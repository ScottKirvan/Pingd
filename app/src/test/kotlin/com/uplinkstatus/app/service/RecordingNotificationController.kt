package com.uplinkstatus.app.service

import android.content.Context
import com.uplinkstatus.core.tracer.CycleEvent

/**
 * A thin [UplinkNotificationController] subclass that records every [CycleEvent] it receives
 * (in order) before delegating to the real implementation via `super.onEvent(event)`.
 *
 * This exists specifically so [UplinkStatusServiceTest] can prove Stage 5's
 * DNS-vs-generic-failure notification fix, and the pre-existing no-back-off retry
 * behavior, hold *end to end* — through a real [com.uplinkstatus.core.tracer.ProbeCycleRunner]
 * created by a real, running [UplinkStatusService] — rather than only at
 * [UplinkNotificationControllerTest]'s standalone unit level (which drives the controller
 * directly with hand-built [CycleEvent]s, never through an actual cycle/service). Because it
 * delegates to `super`, [notifyCallCount] (inherited) and the real posted notification
 * (readable via the shadowed `NotificationManager`, same as in the other service tests) both
 * still reflect real behavior -- this class only adds observability, it changes nothing about
 * what gets posted or suppressed.
 */
internal class RecordingNotificationController(context: Context) : UplinkNotificationController(context) {

    val events = mutableListOf<CycleEvent>()

    override fun onEvent(event: CycleEvent) {
        events += event
        super.onEvent(event)
    }
}
