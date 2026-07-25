package com.uplinkstatus.app.service

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.uplinkstatus.app.R
import com.uplinkstatus.core.tracer.BarPosition
import com.uplinkstatus.core.tracer.CycleEvent
import com.uplinkstatus.core.tracer.CycleListener

/**
 * Builds and posts the status-bar notification, and is the single place that decides when
 * `notify()` actually fires.
 *
 * Implements [CycleListener] so it can be wired directly as [com.uplinkstatus.core.tracer.ProbeCycleRunner]'s
 * listener: every [CycleEvent.Advanced] (an ack) updates the notification with the new bar
 * icon and accessibility text. [CycleEvent.Frozen] is intentionally a no-op — per spec,
 * "Only call notify() on an ack (tracer advance) or a state transition," and a frozen
 * attempt is neither. The tracer visibly staying put is achieved by simply not touching the
 * notification, not by re-posting an unchanged one; this also matters because
 * [com.uplinkstatus.core.tracer.ProbeCycleRunner] emits one `Frozen` event per failed probe
 * attempt, including back-to-back immediate retries during an outage — calling `notify()`
 * for each of those would be exactly the "bare timer tick" spam the spec rules out.
 *
 * Visibility-state transitions (ENABLED/DISABLED/HIDDEN) are handled separately via
 * [notificationForEnabled]/[notificationForDisabled]/[hide], called by
 * [UplinkStatusService] when [com.uplinkstatus.core.visibility.UplinkVisibility] changes —
 * the other half of the spec's "ack or state transition" rule.
 */
class UplinkNotificationController(
    private val context: Context,
    private val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context),
) : CycleListener {

    /** The latency from the most recent [CycleEvent.Advanced] whose source carried one
     * (a probe-success ack). Retained across the automatic ack (which has no latency of
     * its own) so the displayed text doesn't flicker to "unknown" every other step; reset
     * whenever a fresh cycle starts (see [resetSession]), matching bar position's own
     * per-process reset per spec. */
    @Volatile
    private var lastLatencyMs: Long? = null

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_name))
            .setDescription(context.getString(R.string.notification_channel_description))
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    /** Called when a fresh probe cycle starts (transition into `ENABLED` from not-running).
     * Clears any latency remembered from a previous session, since bar position also resets
     * to [BarPosition.START] at that point — nothing here should look like a value from an
     * earlier run. */
    fun resetSession() {
        lastLatencyMs = null
    }

    override fun onEvent(event: CycleEvent) {
        when (event) {
            is CycleEvent.Advanced -> {
                event.latencyMs?.let { lastLatencyMs = it }
                notify(buildEnabledNotification(event.position, lastLatencyMs))
            }

            is CycleEvent.Frozen -> {
                // Intentionally a no-op — see class doc. The icon/text simply stay as they
                // were at the last ack; nothing is re-posted.
            }
        }
    }

    /** Builds (and, via [UplinkStatusService], posts through `startForeground`) the
     * notification for the `ENABLED` state at a given bar position — used for the very
     * first notification of a fresh cycle, before any [CycleEvent] has arrived. */
    fun notificationForEnabled(position: BarPosition): Notification =
        buildEnabledNotification(position, lastLatencyMs)

    /** Builds the notification for the `DISABLED` state: the sixth icon frame (all bars
     * dim), tracer paused. */
    fun notificationForDisabled(): Notification = buildNotification(
        iconRes = disabledIconRes,
        text = context.getString(R.string.notification_text_disabled),
    )

    /** Removes the notification entirely — the `HIDDEN` state. Per spec, hidden is not a
     * seventh icon frame; it's the absence of the icon/notification altogether. */
    fun hide() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun buildEnabledNotification(position: BarPosition, latencyMs: Long?): Notification {
        val text = if (latencyMs != null) {
            context.getString(R.string.notification_text_connected_with_latency, latencyMs)
        } else {
            context.getString(R.string.notification_text_connected_unknown_latency)
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
    }

    private fun buildNotification(iconRes: Int, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        const val CHANNEL_ID = "uplink_status"
        const val NOTIFICATION_ID = 1
    }
}
