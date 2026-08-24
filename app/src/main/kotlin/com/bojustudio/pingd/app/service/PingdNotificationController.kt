package com.bojustudio.pingd.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bojustudio.pingd.app.MainActivity
import com.bojustudio.pingd.app.R
import com.bojustudio.pingd.app.prefs.NetworkScope
import com.bojustudio.pingd.app.state.PingdActivityStatus
import com.bojustudio.pingd.app.state.PingdIconDisplay
import com.bojustudio.pingd.app.state.PingdProbeHistory
import com.bojustudio.pingd.core.tracer.AckSource
import com.bojustudio.pingd.core.tracer.BarPosition
import com.bojustudio.pingd.core.tracer.CycleEvent
import com.bojustudio.pingd.core.tracer.CycleListener
import com.bojustudio.pingd.core.tracer.FreezeReason

/**
 * Builds and posts the status-bar notification, and is the single place that decides when
 * `notify()` actually fires.
 *
 * Implements [CycleListener] so it can be wired directly as [com.bojustudio.pingd.core.tracer.ProbeCycleRunner]'s
 * listener: every [CycleEvent.Advanced] (an ack) updates the notification with the new bar
 * icon and accessibility text.
 *
 * [CycleEvent.Frozen] is *not* a blanket no-op (revised in Stage 5 from an earlier draft
 * that treated every `Frozen` as a pure no-op, which left a DNS failure and a generic probe
 * failure indistinguishable to a screen-reader user — and, worse, left a real outage
 * indistinguishable from "everything's fine, just slow," since nothing about the
 * notification ever changed on any freeze). The icon still never gets a distinct "lost"
 * frame — it stays on [CycleEvent.Frozen.position], exactly where the tracer visually
 * froze, per spec — but the accessibility text now updates to name what's actually
 * happening, and the DNS-resolution case gets genuinely distinct text from the generic
 * case (see [R.string.notification_text_dns_failure] / [R.string.notification_text_probe_failure]).
 *
 * This still has to respect "Only call notify() on an ack (tracer advance) or a state
 * transition — not on every internal timer tick": [com.bojustudio.pingd.core.tracer.ProbeCycleRunner]
 * emits one `Frozen` event per failed probe attempt, including every retry (paced by a
 * fixed floor delay, not zero) during a sustained outage, so posting on every single one of
 * those would be exactly the "bare timer tick" spam the spec rules out. [lastNotifiedState]
 * tracks what was last actually posted (connected, or frozen-for-a-given-reason) so a
 * repeat `Frozen` with the *same* [FreezeReason] as what's already showing is suppressed —
 * only the transition into a freeze, or a change in *why* it's frozen (e.g. a generic
 * failure that turns into an unresolvable host mid-outage), triggers a fresh `notify()`.
 *
 * Visibility-state transitions (ENABLED/DISABLED/HIDDEN) are handled separately via
 * [notificationForEnabled]/[notificationForDisabled]/[hide], called by
 * [PingdStatusService] when [com.bojustudio.pingd.core.visibility.PingdVisibility] changes —
 * the other half of the spec's "ack or state transition" rule. [notificationForStarting]
 * stands apart from all of those: it is not a transition at all, just the content for the
 * post Android requires the moment the service starts, before any state is known.
 *
 * This class reports the states it genuinely observes — acks and freezes — to
 * [PingdActivityStatus], but *only* from [onEvent]; building notification content never
 * reports a connectivity claim there, deliberately: see [buildNotification]. It separately
 * reports every icon it builds, unconditionally, to [PingdIconDisplay] — a claim about
 * connectivity and a mirror of "what icon is showing" are different obligations, and
 * [buildNotification] runs for the starting placeholder too, which has nothing to claim but
 * still has an icon.
 *
 * It reports to a third singleton, [PingdProbeHistory], on a narrower rule again: only real
 * probe attempts, which means every [CycleEvent.Frozen] and only those [CycleEvent.Advanced]s
 * whose source is [AckSource.PROBE_SUCCESS]. The automatic ack contributes nothing there even
 * though it updates both the notification and the status line — see [onEvent].
 *
 * Open (and [onEvent] separately marked `open`) purely so [PingdStatusServiceTest] can
 * inject a thin recording subclass that observes every [CycleEvent] this class receives
 * while still exercising the real notify()/permission-check/de-duplication logic via
 * `super.onEvent(event)` — that's what lets a test prove the DNS-vs-generic-failure
 * distinction and the no-back-off retry behavior hold *end to end* through a real,
 * running [PingdStatusService], not just at this class's own unit-test level.
 */
open class PingdNotificationController(
    private val context: Context,
    private val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context),
) : CycleListener {

    /** The latency from the most recent [CycleEvent.Advanced] whose source carried one
     * (a probe-success ack). Retained across the automatic ack (which has no latency of
     * its own) so the displayed text doesn't flicker to "unknown" every other step; reset
     * whenever a fresh cycle starts (see [resetSession]), matching bar position's own
     * per-cycle reset per spec. */
    @Volatile
    private var lastLatencyMs: Long? = null

    /** What the notification last actually reflected: either the connected/ack state, or a
     * freeze with a specific [FreezeReason]. `null` means nothing has been posted yet this
     * session. Used purely to de-duplicate repeated [CycleEvent.Frozen] events with an
     * unchanged reason — see class doc — never read for anything else. */
    @Volatile
    private var lastNotifiedState: NotifiedState? = null

    /** Counts every real call through to [notificationManager]`.notify()` (i.e. past the
     * permission check). Production code never reads this; it exists so tests can prove the
     * de-duplication in [onEvent] actually suppresses repeated system calls during a
     * sustained outage's repeated retries, not just that the visible end state happens to
     * look right. */
    @Volatile
    internal var notifyCallCount: Int = 0
        private set

    private sealed interface NotifiedState {
        data object Connected : NotifiedState
        data class Frozen(val reason: FreezeReason) : NotifiedState
    }

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.notification_channel_name))
            .setDescription(context.getString(R.string.notification_channel_description))
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    /** Called when a fresh probe cycle starts (transition into `ENABLED` from not-running).
     * Clears any latency remembered from a previous session, since bar position also resets
     * to [BarPosition.START] at that point — nothing here should look like a value from an
     * earlier run. Also clears [lastNotifiedState] so a freeze in a brand-new session is
     * never mistaken for a repeat of one from a previous run. */
    fun resetSession() {
        lastLatencyMs = null
        lastNotifiedState = null
    }

    open override fun onEvent(event: CycleEvent) {
        when (event) {
            is CycleEvent.Advanced -> {
                // A sample for the history graphs, but only for a *real* probe. The automatic
                // ack is a timer firing between probes, not a network measurement: recording
                // it would add an attempt that never happened to the success percentage, and
                // it carries no latency of its own to contribute anyway. Deliberately keyed on
                // the ack's source rather than on "did a latency come with it" -- those happen
                // to coincide today, but only one of them is the actual question being asked.
                if (event.source == AckSource.PROBE_SUCCESS) {
                    event.latencyMs?.let { PingdProbeHistory.recordSuccess(it) }
                }
                event.latencyMs?.let { lastLatencyMs = it }
                lastNotifiedState = NotifiedState.Connected
                // A real ack: the target answered (or the automatic ack that follows one
                // fired). Reported unconditionally, before the permission-gated notify()
                // below, because the status line describes what the service established,
                // not what it managed to draw in the status bar.
                PingdActivityStatus.report(PingdActivityStatus.Activity.Connected(lastLatencyMs))
                notify(buildEnabledNotification(event.position, lastLatencyMs))
            }

            is CycleEvent.Frozen -> {
                // Recorded *before* the de-duplication below, and outside it, on purpose: that
                // guard exists to keep the notification from being re-posted with nothing new
                // to say, which is a statement about the status bar, not about the network.
                // Every Frozen is one real probe that really failed -- including every retry
                // of a sustained outage, which are precisely the attempts a success
                // percentage has to count if it is to mean anything.
                PingdProbeHistory.recordFailure()
                val state = NotifiedState.Frozen(event.reason)
                if (lastNotifiedState != state) {
                    // Either the first freeze after a successful ack, or the reason itself
                    // changed (e.g. generic failure -> can't-resolve-host mid-outage) --
                    // either way this is a genuine change worth surfacing, not a bare tick.
                    lastNotifiedState = state
                    PingdActivityStatus.report(
                        PingdActivityStatus.Activity.ConnectionTrouble(event.reason),
                    )
                    notify(buildFrozenNotification(event.position, event.reason))
                }
                // Same reason as what's already showing: a repeated immediate-retry attempt
                // with nothing new to say -- suppressed, per the class doc.
            }
        }
    }

    /** Builds the notification Android requires the instant the service starts, before any
     * visibility decision has been reached. It shows the dim (paused) frame because that is
     * the least-wrong thing an icon can be while nothing is known — but its *text* claims
     * nothing, which is the part that matters: this notification is a deadline obligation,
     * not a verdict, and [PingdStatusService] deliberately does not let it speak for the
     * on-screen status line. */
    fun notificationForStarting(): Notification =
        buildNotification(
            iconRes = disabledIconRes,
            text = context.getString(R.string.notification_text_starting),
        )

    /** Builds (and, via [PingdStatusService], posts through `startForeground`) the very
     * first notification of a fresh `ENABLED` cycle, before any [CycleEvent] has arrived.
     *
     * The text says "checking," not "connected": at this point the cycle has been started but
     * no probe has completed, so there is nothing confirmed to report. (An *ack* with no
     * latency is a different matter — that one really did happen, and
     * [buildEnabledNotification] still calls it connected.) */
    fun notificationForEnabled(position: BarPosition): Notification =
        buildNotification(
            iconRes = iconResFor(position),
            text = context.getString(R.string.notification_text_checking),
        )

    /** Builds the notification for the `DISABLED` state: the sixth icon frame (all bars
     * dim), tracer paused. [scope] picks more specific text when it's actually available --
     * "out of scope" is technically true but unhelpfully vague when what's really happening,
     * under an SSID whitelist, is "none of your whitelisted networks are in range." */
    fun notificationForDisabled(scope: NetworkScope): Notification {
        val textRes = if (scope == NetworkScope.SSID_WHITELIST) {
            R.string.notification_text_disabled_ssid_scope
        } else {
            R.string.notification_text_disabled
        }
        return buildNotification(iconRes = disabledIconRes, text = context.getString(textRes))
    }

    /** Removes the notification entirely — the `HIDDEN` state. Per spec, hidden is not a
     * seventh icon frame; it's the absence of the icon/notification altogether. Reporting
     * that state to the on-screen status line (which outlives the notification) belongs to
     * whoever made the decision, not to this method: this one only knows it was told to take
     * the icon down. */
    fun hide() {
        notificationManager.cancel(NOTIFICATION_ID)
        PingdIconDisplay.report(null)
    }

    private fun buildEnabledNotification(position: BarPosition, latencyMs: Long?): Notification {
        val text = if (latencyMs != null) {
            context.getString(R.string.notification_text_connected_with_latency, latencyMs)
        } else {
            context.getString(R.string.notification_text_connected_unknown_latency)
        }
        return buildNotification(iconRes = iconResFor(position), text = text)
    }

    /** Builds the notification for a [CycleEvent.Frozen]: same icon as the last ack (the
     * tracer freezes in place, per spec -- there is no distinct "lost" frame), but text that
     * genuinely distinguishes "can't resolve the target host" from a generic connect
     * failure/timeout, per the spec's "a DNS problem and a network-down problem shouldn't
     * look the same to the user." */
    private fun buildFrozenNotification(position: BarPosition, reason: FreezeReason): Notification {
        val text = when (reason) {
            FreezeReason.PROBE_FAILURE -> context.getString(R.string.notification_text_probe_failure)
            FreezeReason.DNS_RESOLUTION_FAILURE -> context.getString(R.string.notification_text_dns_failure)
        }
        return buildNotification(iconRes = iconResFor(position), text = text)
    }

    private fun notify(notification: Notification) {
        // POST_NOTIFICATIONS (Android 13+) is a revocable runtime permission: MainActivity
        // only starts the service once it's granted, but the user can revoke it from system
        // settings at any point while the service keeps running. Checking here (rather than
        // trusting that earlier gate) is both the actually-correct behavior if the
        // permission is pulled out from under a running cycle -- silently skip the update
        // instead of crashing with a SecurityException -- and what lint's MissingPermission
        // check requires for a direct notify() call. The check has to be inline (not
        // delegated to a private helper) for lint's flow analysis to recognize it as
        // guarding this specific call.
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return
        notificationManager.notify(NOTIFICATION_ID, notification)
        notifyCallCount++
    }

    private fun buildNotification(iconRes: Int, text: String): Notification {
        // Deliberately does *not* feed the on-screen status line. Every notification this
        // class can be asked to build funnels through here, including the placeholder posted
        // to satisfy Android's startForeground deadline before anything has been decided --
        // so "some notification content was built" is not evidence of anything, and treating
        // it as such is exactly how the status line ends up asserting states the app never
        // reached. Status reporting happens at the real transitions instead: acks and
        // freezes in onEvent above, visibility decisions in PingdStatusService.
        //
        // PingdIconDisplay *is* fed unconditionally here, deliberately unlike the status
        // line: it exists purely to mirror the icon this notification is about to carry, not
        // to make a claim about connectivity, so every call reaching this point -- including
        // the starting placeholder -- is exactly the moment its mirror should update too.
        PingdIconDisplay.report(iconRes)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    /** Tapping the notification (in the shade) opens the settings screen. There's no separate
     * way to make tapping just the tiny status-bar icon itself do this -- that always expands
     * the shade first, on every Android app, not something this app can override -- so this
     * is the one tap target that actually exists. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "uplink_status"
        const val NOTIFICATION_ID = 1
    }
}
