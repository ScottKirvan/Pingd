package com.uplinkstatus.app.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.uplinkstatus.app.connectivity.ConnectivityManagerNetworkSnapshotProvider
import com.uplinkstatus.app.prefs.DataStoreUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferencesRepository
import com.uplinkstatus.app.prefs.uplinkPreferencesDataStore
import com.uplinkstatus.app.state.ConnectivityNetworkScopeStatus
import com.uplinkstatus.app.state.NetworkScopeStatus
import com.uplinkstatus.app.state.UplinkRuntimeStatus
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
 * Stage 3 replaced Stage 2's `VisibilityInputs` stand-in with a coroutine (started once from
 * [onStartCommand]) that `combine`s [preferencesRepository]'s real, persisted
 * master-toggle/hide-when-disabled/ping-target values with a network-in-scope signal, and
 * re-derives [UplinkVisibility] via [VisibilityDecider] on every emission, so a settings
 * change made while this service is already running is reflected without needing to kill and
 * restart it. Stage 4 replaces that network-in-scope signal itself: [networkScopeStatus] is
 * now [ConnectivityNetworkScopeStatus], a real `ConnectivityManager.NetworkCallback`-driven
 * flow (see [NetworkScopeStatus]'s doc), not the Stage 2/3 manual stand-in. [applyVisibility]
 * itself is unchanged as the lower-level entry point tests drive directly.
 *
 * [prober], [probeTarget], [preferencesRepository], [networkScopeStatus], [schedulerFactory],
 * [runOnWorker], and [visibilityScope] are internal test seams: production code never touches
 * them (they default to the real TCP prober, the spec's default host, the real
 * DataStore-backed repository, the real `ConnectivityManager`-backed scope status, a real
 * `Handler`-backed scheduler, posting to the worker thread, and a real background-dispatcher
 * coroutine scope respectively), but tests override them before triggering a visibility
 * transition so the cycle — and, for the tests that exercise [onStartCommand], the
 * preferences/connectivity read — runs synchronously against fakes instead of touching a real
 * socket, a real DataStore file, a real `ConnectivityManager`, or a real background thread.
 */
class UplinkStatusService : Service() {

    internal var prober: Prober = TcpConnectProber()
    internal var probeTarget: ProbeTarget = ProbeTarget(host = ProbeTarget.DEFAULT_HOST)
    internal var schedulerFactory: () -> TracerScheduler = { AndroidTracerScheduler(workerHandler) }
    internal var runOnWorker: (Runnable) -> Unit = { action -> workerHandler.post(action) }
    internal lateinit var preferencesRepository: UplinkPreferencesRepository
    internal lateinit var networkScopeStatus: NetworkScopeStatus
    internal var visibilityScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workerThread by lazy { HandlerThread("UplinkStatusProbeWorker").apply { start() } }
    private val workerHandler: Handler by lazy { Handler(workerThread.looper) }

    internal lateinit var notificationController: UplinkNotificationController

    /** `@Volatile` because [stopCycle] now runs on whichever thread decided to leave ENABLED
     * (the preferences collector's `Dispatchers.Default` thread, or the main thread via
     * [onDestroy]) rather than being posted to the worker — the reference it reads has to be
     * the one [startCycle] actually published, not a stale cached copy. */
    @Volatile
    private var cycleRunner: ProbeCycleRunner? = null
    private var observingPreferences = false

    /** The most recently observed [com.uplinkstatus.app.prefs.UplinkPreferences.networkScope],
     * kept as a field (rather than threading it through [applyVisibility]'s signature) so the
     * many existing tests that call `applyVisibility(UplinkVisibility.ENABLED)` directly, with
     * no scope argument, keep working -- [notificationForDisabled] still needs to know it to
     * pick SSID-whitelist-specific text. */
    private var currentNetworkScope: NetworkScope = NetworkScope.WIFI_ONLY

    override fun onCreate() {
        super.onCreate()
        if (!::notificationController.isInitialized) {
            notificationController = UplinkNotificationController(applicationContext)
        }
        if (!::preferencesRepository.isInitialized) {
            preferencesRepository = DataStoreUplinkPreferencesRepository(applicationContext.uplinkPreferencesDataStore)
        }
        if (!::networkScopeStatus.isInitialized) {
            val connectivityManager = checkNotNull(
                ContextCompat.getSystemService(applicationContext, ConnectivityManager::class.java),
            )
            networkScopeStatus = ConnectivityNetworkScopeStatus(
                preferencesRepository = preferencesRepository,
                snapshotProvider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager),
                hasLocationPermission = { hasLocationPermission() },
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() to be called shortly after every
        // startForegroundService() call, regardless of what the app's own logic decides
        // afterward -- MainActivity always calls startForegroundService() on launch, but
        // this service's own visibility logic can resolve straight to HIDDEN (master
        // toggle off) without ever calling startForeground() on its own, which is exactly
        // what used to crash the app with ForegroundServiceDidNotStartInTimeException.
        // Posting a safe placeholder immediately -- before the async preferences/
        // connectivity read even resolves -- satisfies that contract unconditionally;
        // applyVisibility() below still tears this back down within a moment if the real
        // answer turns out to be HIDDEN, and idempotently re-posts the correct content
        // otherwise (ENABLED/DISABLED).
        startForeground(
            UplinkNotificationController.NOTIFICATION_ID,
            notificationController.notificationForDisabled(currentNetworkScope),
        )
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
     * [networkScopeStatus]'s real (Stage 4) or fake (tests) network-in-scope signal,
     * re-deriving [UplinkVisibility] via [VisibilityDecider] on every emission from either
     * source — so a live connectivity change (WiFi connect/disconnect, SSID change, cellular
     * fallback) and a scope-preference change (e.g. the user switching from "WiFi only" to
     * "cellular only" while already connected) each independently drive a fresh visibility
     * decision, with neither needing the other to also change. Guarded by
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
                networkScopeStatus.inScopeFlow,
            ) { preferences, networkInScope -> preferences to networkInScope }
                .collect { (preferences, networkInScope) ->
                    probeTarget = ProbeTarget(host = preferences.pingTargetHost)
                    currentNetworkScope = preferences.networkScope
                    // decideOrNull, not decide: networkInScope is nullable ("not reported
                    // yet"), and a null answer means this emission is not grounds for any
                    // user-visible change at all. Skipping it leaves onStartCommand's
                    // placeholder notification in place for the moment it takes connectivity
                    // to report -- which is strictly better than the alternative this replaces,
                    // where "nothing reported yet" was silently spent as a real DISABLED/HIDDEN
                    // verdict and, on a fresh install, was the last word the user ever saw.
                    val visibility = VisibilityDecider.decideOrNull(
                        masterToggleEnabled = preferences.masterToggleEnabled,
                        networkInScope = networkInScope,
                        hideWhenDisabled = preferences.hideWhenDisabled,
                    ) ?: return@collect
                    applyVisibility(visibility)
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
     *
     * Reports to [UplinkRuntimeStatus] once the branch below has actually finished running —
     * not when a preference changed, not when this function was merely called, but once the
     * real `startForeground`/`stopCycle`/`stopSelf` work for this decision has completed. A
     * settings-screen toggle can be told to un-disable itself only once it sees this, which
     * is what makes "credibly applied" a real, observed fact instead of an assumed delay.
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
                    notificationController.notificationForDisabled(currentNetworkScope),
                )
            }

            UplinkVisibility.HIDDEN -> {
                // Per spec: hidden is not a seventh icon — the notification/service simply
                // isn't shown at all. There's nothing left for this service instance to
                // monitor once HIDDEN applies, so it stops itself; this is unchanged from
                // Stage 2/3 and orthogonal to Stage 4's connectivity work -- while this
                // instance keeps running (any other visibility outcome), its own
                // preferences+connectivity collector reacts to either changing on its own,
                // with no restart needed, which is what Stage 4 adds.
                stopCycle()
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationController.hide()
                stopSelf()
            }
        }
        UplinkRuntimeStatus.report(visibility)
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

    /**
     * Stops the cycle **on the calling thread**, deliberately *not* via [runOnWorker].
     *
     * [ProbeCycleRunner.start] blocks its thread for as long as probes keep failing (that's
     * what the spec's "retry immediately, no back-off" means with a blocking prober), and
     * [runOnWorker] posts to a single [HandlerThread] whose [Handler] runs one posted
     * `Runnable` to completion before dispatching the next. Posting `stop()` there during a
     * sustained outage would queue it *behind* the very loop it has to interrupt: it could
     * not run until a probe finally succeeded, so `running` never flipped, the stale cycle
     * kept probing and kept calling its listener — resurrecting a notification the user had
     * already turned off — and the worker thread outlived the service that owned it.
     *
     * [ProbeCycleRunner.stop] is explicitly documented as safe to call from any thread and
     * as never blocking on the thread running the cycle, precisely so this call site can
     * bypass the worker queue. The cycle then ends within one in-flight probe's own timeout
     * at worst, regardless of how long the outage lasts.
     */
    private fun stopCycle() {
        val runner = cycleRunner ?: return
        cycleRunner = null
        runner.stop()
    }

    companion object {
        /** Builds the intent to start this service, per the manifest's `specialUse`
         * declaration — callers (currently just [com.uplinkstatus.app.MainActivity]) should
         * use [android.content.Context.startForegroundService] with it, since Android 8+
         * requires that call for services that intend to call [Service.startForeground]. */
        fun createStartIntent(context: Context): Intent = Intent(context, UplinkStatusService::class.java)
    }
}
