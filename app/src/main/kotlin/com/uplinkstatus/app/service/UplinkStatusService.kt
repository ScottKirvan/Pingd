package com.uplinkstatus.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.uplinkstatus.app.state.VisibilityInputs
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober
import com.uplinkstatus.core.probe.TcpConnectProber
import com.uplinkstatus.core.tracer.BarPosition
import com.uplinkstatus.core.tracer.ProbeCycleRunner
import com.uplinkstatus.core.tracer.TracerScheduler
import com.uplinkstatus.core.visibility.UplinkVisibility

/**
 * The `specialUse` foreground service that drives the status-bar notification from
 * [:core]'s state machine (see `notes/dev/uplink-status-indicator-spec.md` and the
 * manifest's `<service>` declaration for why `specialUse` and not `dataSync`).
 *
 * Responsibilities are deliberately split: this class only knows how to start/stop
 * [ProbeCycleRunner] and translate [UplinkVisibility] transitions into foreground-service
 * lifecycle calls (`startForeground`/`stopForeground`/`stopSelf`). Building notification
 * content lives in [UplinkNotificationController], which also directly implements
 * [com.uplinkstatus.core.tracer.CycleListener] — this service never calls
 * `NotificationManager.notify()` itself, only [Service.startForeground] for the *first*
 * notification of a new state (a required Android API), leaving every subsequent update to
 * the listener reacting to real [com.uplinkstatus.core.tracer.CycleEvent]s.
 *
 * The probe cycle runs on a dedicated background [HandlerThread], not the main thread/main
 * looper — see [AndroidTracerScheduler]'s doc for why this deviates from the spec's literal
 * "main looper" wording (the probe is a blocking call, and running it on the main thread
 * risks ANRs, especially during a sustained outage's back-to-back immediate retries).
 *
 * [prober], [probeTarget], [schedulerFactory], and [runOnWorker] are internal test seams:
 * production code never touches them (they default to the real TCP prober, the spec's
 * default host, a real `Handler`-backed scheduler, and posting to the worker thread), but
 * tests override them before triggering a visibility transition so the cycle runs
 * synchronously against fakes instead of touching a real socket or a real background
 * thread. [applyVisibility] is itself the other test seam: it's the same entry point
 * `onStartCommand` uses, so a test can drive ENABLED/DISABLED/HIDDEN transitions directly
 * without needing Stage 3/4's real preferences or connectivity plumbing, which don't exist
 * yet (see [VisibilityInputs]).
 */
class UplinkStatusService : Service() {

    internal var prober: Prober = TcpConnectProber()
    internal var probeTarget: ProbeTarget = ProbeTarget(host = ProbeTarget.DEFAULT_HOST)
    internal var schedulerFactory: () -> TracerScheduler = { AndroidTracerScheduler(workerHandler) }
    internal var runOnWorker: (Runnable) -> Unit = { action -> workerHandler.post(action) }

    private val workerThread by lazy { HandlerThread("UplinkStatusProbeWorker").apply { start() } }
    private val workerHandler: Handler by lazy { Handler(workerThread.looper) }

    private lateinit var notificationController: UplinkNotificationController
    private var cycleRunner: ProbeCycleRunner? = null

    override fun onCreate() {
        super.onCreate()
        notificationController = UplinkNotificationController(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyVisibility(VisibilityInputs.currentVisibility())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCycle()
        if (workerThread.isAlive) {
            workerThread.quitSafely()
        }
        super.onDestroy()
    }

    /**
     * Reacts to a [UplinkVisibility] value by driving the foreground-service/notification
     * lifecycle accordingly. This is where the spec's "notify() only on an ack or a state
     * transition" rule's other half lives: a transition calls `startForeground` (or
     * `stopForeground`/`stopSelf`) exactly once here, and nowhere else does this class post
     * a notification — every update in between comes from [UplinkNotificationController]
     * reacting to real cycle events.
     */
    internal fun applyVisibility(visibility: UplinkVisibility) {
        when (visibility) {
            UplinkVisibility.ENABLED -> {
                if (cycleRunner?.isRunning != true) {
                    notificationController.resetSession()
                    startForeground(
                        UplinkNotificationController.NOTIFICATION_ID,
                        notificationController.notificationForEnabled(BarPosition.START),
                    )
                    startCycle()
                }
            }

            UplinkVisibility.DISABLED -> {
                stopCycle()
                startForeground(
                    UplinkNotificationController.NOTIFICATION_ID,
                    notificationController.notificationForDisabled(),
                )
            }

            UplinkVisibility.HIDDEN -> {
                // Per spec: hidden is not a seventh icon — the notification/service simply
                // isn't shown at all. There's nothing left for this service to monitor once
                // master-toggle-off (or out-of-scope-and-hide-when-disabled) applies, so it
                // stops itself; Stage 4's future connectivity/preference listener is
                // responsible for starting it again once the state changes back.
                stopCycle()
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationController.hide()
                stopSelf()
            }
        }
    }

    private fun startCycle() {
        val runner = ProbeCycleRunner(
            prober = prober,
            target = probeTarget,
            scheduler = schedulerFactory(),
            listener = notificationController,
        )
        cycleRunner = runner
        runOnWorker(Runnable { runner.start() })
    }

    private fun stopCycle() {
        val runner = cycleRunner ?: return
        cycleRunner = null
        runOnWorker(Runnable { runner.stop() })
    }

    companion object {
        /** Builds the intent to start this service, per the manifest's `specialUse`
         * declaration — callers (currently just [com.uplinkstatus.app.MainActivity]) should
         * use [android.content.Context.startForegroundService] with it, since Android 8+
         * requires that call for services that intend to call [Service.startForeground]. */
        fun createStartIntent(context: Context): Intent = Intent(context, UplinkStatusService::class.java)
    }
}
