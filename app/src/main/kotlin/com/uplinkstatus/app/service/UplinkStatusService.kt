package com.uplinkstatus.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.uplinkstatus.app.prefs.DataStoreUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.UplinkPreferencesRepository
import com.uplinkstatus.app.prefs.uplinkPreferencesDataStore
import com.uplinkstatus.app.state.NetworkScopeStatus
import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.probe.Prober
import com.uplinkstatus.core.probe.TcpConnectProber
import com.uplinkstatus.core.tracer.BarPosition
import com.uplinkstatus.core.tracer.ProbeCycleRunner
import com.uplinkstatus.core.tracer.TracerScheduler
import com.uplinkstatus.core.visibility.UplinkVisibility
import com.uplinkstatus.core.visibility.VisibilityDecider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
 * Stage 3 replaces Stage 2's `VisibilityInputs` stand-in: [onStartCommand] now starts
 * (once) a coroutine that `combine`s [preferencesRepository]'s real, persisted
 * master-toggle/hide-when-disabled/ping-target values with
 * [NetworkScopeStatus.inScopeFlow] (Stage 4's still-manual network-scope stand-in — see its
 * doc) and re-derives [UplinkVisibility] via [VisibilityDecider] on every emission, so a
 * settings change made while this service is already running is reflected without needing
 * to kill and restart it. [applyVisibility] itself is unchanged as the lower-level entry
 * point tests drive directly.
 *
 * [prober], [probeTarget], [preferencesRepository], [schedulerFactory], [runOnWorker], and
 * [visibilityScope] are internal test seams: production code never touches them (they
 * default to the real TCP prober, the spec's default host, the real DataStore-backed
 * repository, a real `Handler`-backed scheduler, posting to the worker thread, and a real
 * background-dispatcher coroutine scope respectively), but tests override them before
 * triggering a visibility transition so the cycle — and, for the two tests that exercise
 * [onStartCommand], the preferences read — runs synchronously against fakes instead of
 * touching a real socket, a real DataStore file, or a real background thread.
 */
class UplinkStatusService : Service() {

    internal var prober: Prober = TcpConnectProber()
    internal var probeTarget: ProbeTarget = ProbeTarget(host = ProbeTarget.DEFAULT_HOST)
    internal var schedulerFactory: () -> TracerScheduler = { AndroidTracerScheduler(workerHandler) }
    internal var runOnWorker: (Runnable) -> Unit = { action -> workerHandler.post(action) }
    internal lateinit var preferencesRepository: UplinkPreferencesRepository
    internal var visibilityScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workerThread by lazy { HandlerThread("UplinkStatusProbeWorker").apply { start() } }
    private val workerHandler: Handler by lazy { Handler(workerThread.looper) }

    private lateinit var notificationController: UplinkNotificationController
    private var cycleRunner: ProbeCycleRunner? = null
    private var observingPreferences = false

    override fun onCreate() {
        super.onCreate()
        notificationController = UplinkNotificationController(applicationContext)
        if (!::preferencesRepository.isInitialized) {
            preferencesRepository = DataStoreUplinkPreferencesRepository(applicationContext.uplinkPreferencesDataStore)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startObservingPreferencesIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCycle()
        visibilityScope.cancel()
        if (workerThread.isAlive) {
            workerThread.quitSafely()
        }
        super.onDestroy()
    }

    /**
     * Starts (once) a coroutine that combines the real persisted preferences with
     * [NetworkScopeStatus]'s Stage 4 stand-in, re-deriving [UplinkVisibility] via
     * [VisibilityDecider] on every emission from either source. Guarded by
     * [observingPreferences] so a second `onStartCommand` (e.g. a real device redelivering
     * `START_STICKY`, or a settings-screen "nudge" restart) doesn't stack a second collector
     * — the existing one already reacts to whatever changed.
     */
    private fun startObservingPreferencesIfNeeded() {
        if (observingPreferences) return
        observingPreferences = true
        visibilityScope.launch {
            combine(
                preferencesRepository.preferencesFlow,
                NetworkScopeStatus.inScopeFlow,
            ) { preferences, networkInScope -> preferences to networkInScope }
                .collect { (preferences, networkInScope) ->
                    probeTarget = ProbeTarget(host = preferences.pingTargetHost)
                    applyVisibility(
                        VisibilityDecider.decide(
                            masterToggleEnabled = preferences.masterToggleEnabled,
                            networkInScope = networkInScope,
                            hideWhenDisabled = preferences.hideWhenDisabled,
                        ),
                    )
                }
        }
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
                // stops itself; Stage 4's future connectivity listener (and Stage 3's
                // settings screen, for the master-toggle/hide-when-disabled cases) is
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
