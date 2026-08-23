package com.bojustudio.pingd.app.prefs

import com.bojustudio.pingd.core.history.ProbeHistory
import com.bojustudio.pingd.core.probe.ProbeTarget
import com.bojustudio.pingd.core.tracer.ProbeCycleRunner

/**
 * The full set of user-editable preferences from the spec's "User Preferences" section, as
 * a single immutable snapshot. [PingdPreferencesRepository.preferencesFlow] emits one of
 * these on every change; nothing in `:app` should read an individual DataStore key directly.
 *
 * Defaults here matter: they're what a fresh install (no DataStore file yet) reads as, and
 * they're chosen to match [com.bojustudio.pingd.core.visibility.VisibilityDecider]'s
 * `ENABLED`-by-default posture that Stage 2's `VisibilityInputs` stand-in used, plus the
 * spec's documented defaults for scope and ping target.
 */
data class PingdPreferences(
    val masterToggleEnabled: Boolean = true,
    val hideWhenDisabled: Boolean = false,
    val networkScope: NetworkScope = NetworkScope.ANY_CONNECTION,
    val ssidWhitelist: Set<String> = emptySet(),
    val pingTargetHost: String = ProbeTarget.DEFAULT_HOST,
    /** The pacing wait between every step of the ping/ping/fake cycle -- see
     * [ProbeCycleRunner]'s class doc. 0..1000ms, default matches
     * [ProbeCycleRunner.DEFAULT_STEP_DELAY_MS] so a fresh install's behavior is identical to
     * what every install had before this became configurable. */
    val stepDelayMs: Long = ProbeCycleRunner.DEFAULT_STEP_DELAY_MS,
    /** How far back the settings screen's history graphs reach. One setting shared by both
     * graphs -- the latency trend's time axis, the ping-success line's, and the success
     * percentage's own rolling window are all this same value, per spec, not three
     * independently configurable views of the same samples. Default
     * [ProbeHistory.DEFAULT_WINDOW_MS] (7 minutes). */
    val historyWindowMs: Long = ProbeHistory.DEFAULT_WINDOW_MS,
) {
    init {
        require(stepDelayMs in STEP_DELAY_RANGE_MS) {
            "stepDelayMs must be within $STEP_DELAY_RANGE_MS, was $stepDelayMs"
        }
        require(historyWindowMs in HISTORY_WINDOW_RANGE_MS) {
            "historyWindowMs must be within $HISTORY_WINDOW_RANGE_MS, was $historyWindowMs"
        }
    }

    companion object {
        /** The settings screen's slider range for [stepDelayMs] -- 0 ("free wheeling") to
         * 1000ms, per spec. */
        val STEP_DELAY_RANGE_MS: LongRange = 0L..1000L

        /** One minute of granularity for [historyWindowMs]; the settings screen's slider is
         * denominated in whole minutes. */
        const val HISTORY_WINDOW_STEP_MS: Long = 60_000L

        /**
         * The settings screen's slider range for [historyWindowMs]: 1 to 30 minutes.
         *
         * The lower bound is a window still wide enough to show a trend at the slowest pacing
         * (a 1000ms step delay is roughly one probe per second, so even a minute is ~60
         * samples). The upper bound is bounded by memory rather than by principle -- see
         * [ProbeHistory.MAX_SAMPLES], which a 30-minute window stays comfortably inside at any
         * pacing a user is likely to leave running.
         */
        val HISTORY_WINDOW_RANGE_MS: LongRange = HISTORY_WINDOW_STEP_MS..(30 * HISTORY_WINDOW_STEP_MS)
    }
}
