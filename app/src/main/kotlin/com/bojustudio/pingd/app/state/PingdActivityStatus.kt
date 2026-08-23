package com.bojustudio.pingd.app.state

import android.content.Context
import com.bojustudio.pingd.app.R
import com.bojustudio.pingd.app.prefs.NetworkScope
import com.bojustudio.pingd.core.tracer.FreezeReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What [com.bojustudio.pingd.app.service.PingdStatusService] has actually, currently confirmed
 * about its own operation — surfaced verbatim as `SettingsScreen`'s "Status: …" line, which
 * exists to be a small honest log of what the service is really doing right now.
 *
 * This is a closed set of [Activity] states, not free-form text, and that is the whole point.
 * A status line is only worth anything if every value it can hold corresponds to a real,
 * confirmed transition — so there is deliberately no way to push an arbitrary string in here
 * as an incidental side effect of some unrelated work. Each [Activity] has exactly one call
 * site, and each of those call sites sits on a transition the service has genuinely made:
 *
 * - [Activity.Starting] — `onStartCommand` has run but no visibility decision has been
 *   reached yet by this service instance.
 * - [Activity.CheckingConnection] — a real `ENABLED` decision was applied and the probe cycle
 *   has been started, but no probe has completed. Notably *not* "connected": nothing has been
 *   confirmed reachable at this point.
 * - [Activity.Connected] — a real ack ([com.bojustudio.pingd.core.tracer.CycleEvent.Advanced]).
 * - [Activity.ConnectionTrouble] — a real failed probe
 *   ([com.bojustudio.pingd.core.tracer.CycleEvent.Frozen]), carrying *why* it failed.
 * - [Activity.Paused] — a real `DISABLED` decision.
 * - [Activity.Hidden] — a real `HIDDEN` decision.
 * - [Activity.Stopped] — the service instance was destroyed, so nothing is going to update
 *   this again until a new one starts.
 *
 * In particular this is *not* fed from notification content. The foreground notification has
 * to be posted unconditionally the moment the service starts, before anything has been
 * decided, purely to satisfy Android's `startForeground()`-after-`startForegroundService()`
 * deadline; deriving the status line from whatever text that placeholder happened to carry
 * would state a diagnosis the app has not made. The two surfaces are related but not the
 * same, and only this one is required to be true.
 *
 * Kept separate from [PingdRuntimeStatus] on purpose: that object answers "has the service
 * caught up with a requested change yet," which drives behavior; this one answers "what
 * should the user be told," which drives nothing.
 */
object PingdActivityStatus {
    private val state = MutableStateFlow<Activity?>(null)

    /** `null` until the service confirms its first real state — `SettingsScreen` shows no
     * status line at all in that window rather than guessing at one. */
    val activity: StateFlow<Activity?> = state.asStateFlow()

    fun report(activity: Activity) {
        state.value = activity
    }

    /** Process-wide singleton; tests must reset it between runs. Production code never calls
     * this. */
    internal fun resetForTest() {
        state.value = null
    }

    /** The closed set of things the service is allowed to claim about itself. */
    sealed interface Activity {
        /** Started, nothing decided yet. */
        data object Starting : Activity

        /** `ENABLED` applied, cycle running, no probe has completed yet. */
        data object CheckingConnection : Activity

        /** A probe (or the automatic ack following one) advanced the tracer. [latencyMs] is
         * the most recent measured round trip, or `null` if an automatic ack arrived before
         * any probe-success ack ever carried one. */
        data class Connected(val latencyMs: Long?) : Activity

        /** A probe attempt failed; [reason] distinguishes an unresolvable host from a
         * generic connect failure, which are genuinely different problems to the user. */
        data class ConnectionTrouble(val reason: FreezeReason) : Activity

        /** `DISABLED` applied — icon shown, tracer paused. [scope] lets the wording name what
         * is actually being waited for under an SSID whitelist. */
        data class Paused(val scope: NetworkScope) : Activity

        /** `HIDDEN` applied — no icon at all. */
        data object Hidden : Activity

        /** The service instance is gone. */
        data object Stopped : Activity
    }
}

/**
 * The user-facing wording for an [PingdActivityStatus.Activity], written for someone
 * glancing at the settings screen.
 *
 * Deliberately its own set of strings rather than a reuse of the notification's: the
 * notification and the status line agree about confirmed states but genuinely diverge around
 * startup, where the notification must carry a placeholder and the status line must not.
 */
fun PingdActivityStatus.Activity.describe(context: Context): String = when (this) {
    PingdActivityStatus.Activity.Starting -> context.getString(R.string.status_text_starting)
    PingdActivityStatus.Activity.CheckingConnection ->
        context.getString(R.string.status_text_checking)

    is PingdActivityStatus.Activity.Connected -> if (latencyMs != null) {
        context.getString(R.string.status_text_connected_with_latency, latencyMs)
    } else {
        context.getString(R.string.status_text_connected)
    }

    is PingdActivityStatus.Activity.ConnectionTrouble -> when (reason) {
        FreezeReason.PROBE_FAILURE -> context.getString(R.string.status_text_probe_failure)
        FreezeReason.DNS_RESOLUTION_FAILURE -> context.getString(R.string.status_text_dns_failure)
    }

    is PingdActivityStatus.Activity.Paused -> if (scope == NetworkScope.SSID_WHITELIST) {
        context.getString(R.string.status_text_paused_ssid_scope)
    } else {
        context.getString(R.string.status_text_paused)
    }

    PingdActivityStatus.Activity.Hidden -> context.getString(R.string.status_text_hidden)
    PingdActivityStatus.Activity.Stopped -> context.getString(R.string.status_text_stopped)
}
