package com.uplinkstatus.app.prefs

import com.uplinkstatus.core.probe.ProbeTarget
import com.uplinkstatus.core.tracer.ProbeCycleRunner

/**
 * The full set of user-editable preferences from the spec's "User Preferences" section, as
 * a single immutable snapshot. [UplinkPreferencesRepository.preferencesFlow] emits one of
 * these on every change; nothing in `:app` should read an individual DataStore key directly.
 *
 * Defaults here matter: they're what a fresh install (no DataStore file yet) reads as, and
 * they're chosen to match [com.uplinkstatus.core.visibility.VisibilityDecider]'s
 * `ENABLED`-by-default posture that Stage 2's `VisibilityInputs` stand-in used, plus the
 * spec's documented defaults for scope and ping target.
 */
data class UplinkPreferences(
    val masterToggleEnabled: Boolean = true,
    val hideWhenDisabled: Boolean = false,
    val networkScope: NetworkScope = NetworkScope.WIFI_ONLY,
    val ssidWhitelist: Set<String> = emptySet(),
    val pingTargetHost: String = ProbeTarget.DEFAULT_HOST,
    /** The pacing wait between every step of the ping/ping/fake cycle -- see
     * [ProbeCycleRunner]'s class doc. 0..1000ms, default matches
     * [ProbeCycleRunner.DEFAULT_STEP_DELAY_MS] so a fresh install's behavior is identical to
     * what every install had before this became configurable. */
    val stepDelayMs: Long = ProbeCycleRunner.DEFAULT_STEP_DELAY_MS,
) {
    init {
        require(stepDelayMs in STEP_DELAY_RANGE_MS) {
            "stepDelayMs must be within $STEP_DELAY_RANGE_MS, was $stepDelayMs"
        }
    }

    companion object {
        /** The settings screen's slider range for [stepDelayMs] -- 0 ("free wheeling") to
         * 1000ms, per spec. */
        val STEP_DELAY_RANGE_MS: LongRange = 0L..1000L
    }
}
