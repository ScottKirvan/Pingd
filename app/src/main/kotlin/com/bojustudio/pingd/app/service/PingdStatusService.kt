package com.bojustudio.pingd.app.service

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
import com.bojustudio.pingd.app.connectivity.ConnectivityManagerNetworkSnapshotProvider
import com.bojustudio.pingd.app.prefs.DataStorePingdPreferencesRepository
import com.bojustudio.pingd.app.prefs.NetworkScope
import com.bojustudio.pingd.app.prefs.PingdPreferencesRepository
import com.bojustudio.pingd.app.prefs.uplinkPreferencesDataStore
import com.bojustudio.pingd.app.state.ConnectivityNetworkScopeStatus
import com.bojustudio.pingd.app.state.NetworkScopeStatus
import com.bojustudio.pingd.app.state.PingdActivityStatus
import com.bojustudio.pingd.app.state.PingdProbeHistory
import com.bojustudio.pingd.app.state.PingdRuntimeStatus
import com.bojustudio.pingd.core.probe.ProbeResult
import com.bojustudio.pingd.core.probe.ProbeTarget
import com.bojustudio.pingd.core.probe.Prober
import com.bojustudio.pingd.core.probe.TcpConnectProber
import com.bojustudio.pingd.core.tracer.BackgroundHistoryProbeLoop
import com.bojustudio.pingd.core.tracer.BarPosition
import com.bojustudio.pingd.core.tracer.ProbeCycleRunner
import com.bojustudio.pingd.core.tracer.TracerScheduler
import com.bojustudio.pingd.core.visibility.PingdVisibility
import com.bojustudio.pingd.core.visibility.VisibilityDecider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The `specialUse` foreground service that drives the status-bar notification from
 * [:core]'s state machine (see `notes/dev/pingd-status-indicator-spec.md` and the
 * manifest's `<service>` declaration for why `specialUse` and not `dataSync`).
 *
 * Responsibilities are deliberately split: this class only knows how to start/stop
 * [ProbeCycleRunner] and translate [PingdVisibility] transitions into foreground-service
 * lifecycle calls (`startForeground`/`stopForeground`/`stopSelf`). Building notification
 * content lives in [PingdNotificationController], which also directly implements
 * [com.bojustudio.pingd.core.tracer.CycleListener] — this service never calls
 * `NotificationManager.notify()` itself, only [Service.startForeground] for the *first*
 * notification of a new state (a required Android API), leaving every subsequent update to
 * the listener reacting to real [com.bojustudio.pingd.core.tracer.CycleEvent]s.
 *
 * The probe cycle runs on a dedicated background [HandlerThread], not the main thread/main
 * looper — see [AndroidTracerScheduler]'s doc for why this deviates from the spec's literal
 * "main looper" wording (the probe is a blocking call, and running it on the main thread
 * risks an ANR for the duration of even a single attempt).
 *
 * Stage 3 replaced Stage 2's `VisibilityInputs` stand-in with a coroutine (started once from
 * [onStartCommand]) that `combine`s [preferencesRepository]'s real, persisted
 * master-toggle/hide-when-disabled/ping-target values with a network-in-scope signal, and
 * re-derives [PingdVisibility] via [VisibilityDecider] on every emission, so a settings
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
class PingdStatusService : Service() {

    internal var prober: Prober = TcpConnectProber()
    internal var probeTarget: ProbeTarget = ProbeTarget(host = ProbeTarget.DEFAULT_HOST)
    internal var stepDelayMs: Long = ProbeCycleRunner.DEFAULT_STEP_DELAY_MS
    internal var schedulerFactory: () -> TracerScheduler = { AndroidTracerScheduler(workerHandler) }
    internal var runOnWorker: (Runnable) -> Unit = { action -> workerHandler.post(action) }
    internal lateinit var preferencesRepository: PingdPreferencesRepository
    internal lateinit var networkScopeStatus: NetworkScopeStatus
    internal var visibilityScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workerThread by lazy { HandlerThread("PingdStatusProbeWorker").apply { start() } }
    private val workerHandler: Handler by lazy { Handler(workerThread.looper) }

    internal lateinit var notificationController: PingdNotificationController

    /** `@Volatile` because [stopCycle] now runs on whichever thread decided to leave ENABLED
     * (the preferences collector's `Dispatchers.Default` thread, or the main thread via
     * [onDestroy]) rather than being posted to the worker — the reference it reads has to be
     * the one [startCycle] actually published, not a stale cached copy. */
    @Volatile
    private var cycleRunner: ProbeCycleRunner? = null

    /** Keeps the history graphs recording through a `DISABLED` period (network out of scope) --
     * see [BackgroundHistoryProbeLoop]'s own doc for why this is a separate, throttled loop
     * rather than [cycleRunner] itself. Only ever running while `DISABLED`; [applyVisibility]
     * stops it the moment either `ENABLED` (the visible tracer takes over) or `HIDDEN` (master
     * toggle off -- the whole service stops) applies. `@Volatile` for the same reason
     * [cycleRunner] is. */
    @Volatile
    private var backgroundHistoryLoop: BackgroundHistoryProbeLoop? = null

    private var observingPreferences = false

    /** The master-toggle value the preferences collector last observed, so it can tell a real
     * transition apart from any other emission -- `null` means "nothing observed yet," which a
     * fresh start must not itself be treated as a transition from. Read/written only from that
     * collector's own coroutine, never concurrently. */
    private var lastObservedMasterToggleEnabled: Boolean? = null

    /** The most recently observed [com.bojustudio.pingd.app.prefs.PingdPreferences.networkScope],
     * kept as a field (rather than threading it through [applyVisibility]'s signature) so the
     * many existing tests that call `applyVisibility(PingdVisibility.ENABLED)` directly, with
     * no scope argument, keep working -- [notificationForDisabled] still needs to know it to
     * pick SSID-whitelist-specific text. */
    private var currentNetworkScope: NetworkScope = NetworkScope.ANY_CONNECTION

    /** Whether [applyVisibility] has run at least once for *this* service instance, i.e.
     * whether a real visibility decision exists yet. Only used to keep [onStartCommand] from
     * reporting [PingdActivityStatus.Activity.Starting] over a state the service has already
     * genuinely reached: a settings change nudges `startForegroundService` again while this
     * instance is happily running, and "starting up…" would be a false claim at that point. */
    @Volatile
    private var hasAppliedVisibility = false

    override fun onCreate() {
        super.onCreate()
        if (!::notificationController.isInitialized) {
            notificationController = PingdNotificationController(applicationContext)
        }
        if (!::preferencesRepository.isInitialized) {
            preferencesRepository = DataStorePingdPreferencesRepository(applicationContext.uplinkPreferencesDataStore)
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

    /**
     * Read on demand, never cached, so an SSID-whitelist scope is evaluated against the
     * permission state as it is *now* rather than as it was when this service started.
     *
     * On demand is not the same as reactive, though, and nothing here watches for the grant
     * itself: that is
     * [com.bojustudio.pingd.app.permissions.LocationPermissionStatus.changes]'s job, which
     * [ConnectivityManagerNetworkSnapshotProvider] collects (by default, hence no argument at
     * the construction above) to re-read the platform's networks whenever the grant changes.
     * Without that, this function would start returning `true` while the snapshot it is being
     * combined with still carried the SSID the platform redacted before the grant.
     */
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
        //
        // What this placeholder must not do is state a diagnosis. The disabled/paused
        // notification is the obvious thing to reach for -- the tracer isn't running, so the
        // dim frame is right -- but its *text* names a specific network condition ("paused
        // (network out of scope)") that nothing has evaluated yet, and on a fresh install
        // that would be the very first thing the app ever tells the user.
        // notificationForStarting() keeps the required post and the dim icon while saying
        // only what is actually true: this is starting up. The on-screen status line gets the
        // same treatment, and only while there is genuinely nothing better to say -- a nudge
        // restart of an already-running instance is not a start.
        if (!hasAppliedVisibility) {
            PingdActivityStatus.report(PingdActivityStatus.Activity.Starting)
        }
        startForeground(
            PingdNotificationController.NOTIFICATION_ID,
            notificationController.notificationForStarting(),
        )
        startObservingPreferencesIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCycle()
        // Nothing will update the status line again until a new service instance starts, so
        // leaving whatever was last true ("connected, 42ms") on screen would turn it into a
        // lie the moment this instance goes away. HIDDEN is the exception: it already
        // reported the specific reason this service is stopping, which is strictly more
        // informative than "stopped."
        if (PingdActivityStatus.activity.value != PingdActivityStatus.Activity.Hidden) {
            PingdActivityStatus.report(PingdActivityStatus.Activity.Stopped)
        }
        visibilityScope.cancel()
        if (workerThread.isAlive) {
            workerThread.quitSafely()
        }
        super.onDestroy()
    }

    /**
     * Starts (once) a coroutine that combines the real persisted preferences with
     * [networkScopeStatus]'s real (Stage 4) or fake (tests) network-in-scope signal,
     * re-deriving [PingdVisibility] via [VisibilityDecider] on every emission from either
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
                    stepDelayMs = preferences.stepDelayMs
                    // applyVisibility's ENABLED branch deliberately no-ops while a cycle is
                    // already running -- re-confirming ENABLED must not reset bar position or
                    // session state over an unrelated preference edit (see its own doc). That
                    // means a *new* ProbeCycleRunner picking up the two fields just above is
                    // not enough on its own: an already-running one never gets reconstructed,
                    // so it would otherwise keep the target/delay it was born with for as long
                    // as it keeps running. Nudging it live (a no-op if cycleRunner is null --
                    // nothing to push into yet, and startCycle() below will read the fresh
                    // fields whenever it does run) is what makes changing either setting take
                    // effect immediately instead of only at the next unrelated restart.
                    cycleRunner?.updateTarget(probeTarget)
                    cycleRunner?.updateStepDelayMs(stepDelayMs)
                    // Same live-update reasoning, for the DISABLED-only history loop -- a no-op
                    // while it isn't running (nothing to push into yet; startBackgroundHistoryLoopIfNeeded()
                    // reads the fresh fields whenever it does start).
                    backgroundHistoryLoop?.updateTarget(probeTarget)
                    backgroundHistoryLoop?.updateRetryDelayMs(backgroundHistoryRetryDelayMs())
                    currentNetworkScope = preferences.networkScope
                    // The history graphs' shared window. Applied here rather than from the
                    // settings screen so the retention the samples are actually recorded under
                    // follows the preference wherever it was changed from -- and because this
                    // is already the one collector that turns persisted preferences into
                    // running behavior. A window change made while this service is stopped
                    // (HIDDEN) is picked up on its next start, which the settings screen's own
                    // ensureServiceRunning() nudge triggers immediately anyway.
                    PingdProbeHistory.setWindowMs(preferences.historyWindowMs)
                    // A vertical marker in the history graphs at the exact point the whole app
                    // stopped measuring -- see PingdProbeHistory.recordMasterToggleTransition's
                    // doc. Only the off transition is marked: the on transition happens in a
                    // *new* service instance (HIDDEN tears this one down via stopSelf()), which
                    // starts with no memory of "was previously off" to detect a transition from
                    // -- and the resumption is already visible in the data itself once real
                    // samples start flowing again, so there is nothing a second marker would add.
                    val previousMasterToggleEnabled = lastObservedMasterToggleEnabled
                    lastObservedMasterToggleEnabled = preferences.masterToggleEnabled
                    if (previousMasterToggleEnabled == true && !preferences.masterToggleEnabled) {
                        PingdProbeHistory.recordMasterToggleTransition()
                    }
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
     * Reacts to a [PingdVisibility] value by driving the foreground-service/notification
     * lifecycle accordingly. This is where the spec's "notify() only on an ack or a state
     * transition" rule's other half lives: a transition calls `startForeground` (or
     * `stopForeground`/`stopSelf`) exactly once here, and nowhere else does this class post
     * a notification — every update in between comes from [PingdNotificationController]
     * reacting to real cycle events.
     *
     * Reports to [PingdRuntimeStatus] once the branch below has actually finished running —
     * not when a preference changed, not when this function was merely called, but once the
     * real `startForeground`/`stopCycle`/`stopSelf` work for this decision has completed. A
     * settings-screen toggle can be told to un-disable itself only once it sees this, which
     * is what makes "credibly applied" a real, observed fact instead of an assumed delay.
     */
    internal fun applyVisibility(visibility: PingdVisibility) {
        when (visibility) {
            PingdVisibility.ENABLED -> {
                // The history-only loop below is DISABLED-only -- once the visible tracer takes
                // over, it would just be probing the same target a second time. A no-op if it
                // was never running (the common case: going straight from HIDDEN/a fresh start
                // to ENABLED without ever passing through DISABLED).
                stopBackgroundHistoryLoop()
                if (cycleRunner?.isRunning != true) {
                    notificationController.resetSession()
                    // "Checking," not "connected." The cycle is about to start; no probe has
                    // been attempted, let alone answered. The first CycleEvent replaces this
                    // with a real result (connected, or trouble) the moment there is one.
                    // Reported before startCycle() because with a fast/synchronous probe the
                    // cycle can produce that real result before this call even returns.
                    PingdActivityStatus.report(PingdActivityStatus.Activity.CheckingConnection)
                    startForeground(
                        PingdNotificationController.NOTIFICATION_ID,
                        notificationController.notificationForEnabled(BarPosition.START),
                    )
                    startCycle()
                }
                // Already running: the cycle's own last event is still the current truth --
                // re-confirming ENABLED changed nothing and must not overwrite it.
            }

            PingdVisibility.DISABLED -> {
                stopCycle()
                // The visible tracer pauses here -- nothing to show while out of scope -- but
                // per notes/dev/pingd-status-indicator-spec.md's "In-App History Graphs", an
                // out-of-scope period is exactly the outage a connectivity history exists to
                // show, so it must not go blind for its duration. A separate, throttled probe
                // loop keeps recording real samples underneath the dim/paused notification; see
                // BackgroundHistoryProbeLoop's own doc for why this is a distinct class rather
                // than just leaving cycleRunner running.
                startBackgroundHistoryLoopIfNeeded()
                PingdActivityStatus.report(PingdActivityStatus.Activity.Paused(currentNetworkScope))
                startForeground(
                    PingdNotificationController.NOTIFICATION_ID,
                    notificationController.notificationForDisabled(currentNetworkScope),
                )
            }

            PingdVisibility.HIDDEN -> {
                // Per spec: hidden is not a seventh icon — the notification/service simply
                // isn't shown at all. There's nothing left for this service instance to
                // monitor once HIDDEN applies, so it stops itself; this is unchanged from
                // Stage 2/3 and orthogonal to Stage 4's connectivity work -- while this
                // instance keeps running (any other visibility outcome), its own
                // preferences+connectivity collector reacts to either changing on its own,
                // with no restart needed, which is what Stage 4 adds.
                stopCycle()
                // Master toggle off means the *entire service* stops, history recording
                // included -- confirmed explicitly: this is not the same "keep it running
                // quietly" treatment DISABLED gets above. There is nothing left to keep the
                // history-only loop fed once this instance tears itself down.
                stopBackgroundHistoryLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationController.hide()
                PingdActivityStatus.report(PingdActivityStatus.Activity.Hidden)
                stopSelf()
            }
        }
        hasAppliedVisibility = true
        PingdRuntimeStatus.report(visibility)
    }

    private fun startCycle() {
        val runner = ProbeCycleRunner(
            prober = prober,
            initialTarget = probeTarget,
            scheduler = schedulerFactory(),
            listener = notificationController,
            initialStepDelayMs = stepDelayMs,
        )
        cycleRunner = runner
        runOnWorker(Runnable { runner.start() })
    }

    /**
     * Stops the cycle **on the calling thread**, deliberately *not* via [runOnWorker].
     *
     * A single probe attempt blocks [ProbeCycleRunner]'s thread for up to its own timeout
     * (a failure retry is otherwise paced through its scheduler, not looped inline — see
     * `ProbeCycleRunner.FAILURE_RETRY_DELAY_MS`'s doc), and [runOnWorker] posts to a single
     * [HandlerThread] whose [Handler] runs one posted `Runnable` to completion before
     * dispatching the next. Posting `stop()` there would queue it *behind* whatever probe
     * attempt is currently in flight: for up to that attempt's own timeout, `running` would
     * not have flipped yet, so the stale cycle could still call its listener once more —
     * resurrecting a notification the user had already turned off, however briefly.
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

    /** Starts [backgroundHistoryLoop] if it isn't already running -- a no-op on a repeated
     * `DISABLED` decision (e.g. an unrelated preference edit while still out of scope), same
     * shape as [startCycle]'s own `cycleRunner?.isRunning != true` guard in the `ENABLED`
     * branch. */
    private fun startBackgroundHistoryLoopIfNeeded() {
        if (backgroundHistoryLoop?.isRunning == true) return
        val loop = BackgroundHistoryProbeLoop(
            prober = prober,
            initialTarget = probeTarget,
            scheduler = schedulerFactory(),
            onResult = ::recordHistorySample,
            initialRetryDelayMs = backgroundHistoryRetryDelayMs(),
        )
        backgroundHistoryLoop = loop
        runOnWorker(Runnable { loop.start() })
    }

    /** Stops [backgroundHistoryLoop] on the calling thread, same reasoning as [stopCycle]'s own
     * doc: it must not be queued behind [runOnWorker], which could be stuck inside a long-running
     * probe attempt. */
    private fun stopBackgroundHistoryLoop() {
        val loop = backgroundHistoryLoop ?: return
        backgroundHistoryLoop = null
        loop.stop()
    }

    /** At least [BackgroundHistoryProbeLoop.MIN_RETRY_DELAY_MS], regardless of the user's own
     * step-delay preference -- see that constant's doc for why the floor exists independent of
     * whatever pacing the visible tracer is configured with. */
    private fun backgroundHistoryRetryDelayMs(): Long =
        maxOf(BackgroundHistoryProbeLoop.MIN_RETRY_DELAY_MS, stepDelayMs)

    /** [BackgroundHistoryProbeLoop] has no notion of success/failure beyond the raw
     * [ProbeResult] it was handed -- this is the one place that turns that into what
     * [PingdProbeHistory] actually records, mirroring (deliberately not sharing code with)
     * [PingdNotificationController.onEvent]'s own narrower rule for the visible cycle's real
     * probes. */
    private fun recordHistorySample(result: ProbeResult) {
        when (result) {
            is ProbeResult.Success -> PingdProbeHistory.recordSuccess(result.latencyMs)
            ProbeResult.Failure, ProbeResult.DnsResolutionFailure -> PingdProbeHistory.recordFailure()
        }
    }

    companion object {
        /** Builds the intent to start this service, per the manifest's `specialUse`
         * declaration — callers (currently just [com.bojustudio.pingd.app.MainActivity]) should
         * use [android.content.Context.startForegroundService] with it, since Android 8+
         * requires that call for services that intend to call [Service.startForeground]. */
        fun createStartIntent(context: Context): Intent = Intent(context, PingdStatusService::class.java)
    }
}
