package com.uplinkstatus.app.prefs

import org.junit.Test

/**
 * [UplinkPreferences.stepDelayMs]'s range validation -- everything else on this data class is
 * a plain default, exercised indirectly through [UplinkPreferencesRepositoryTest] and the
 * various fakes/tests that construct it. This file exists only because the `init` check is
 * new, direct-constructor behavior no other test happens to cover.
 */
class UplinkPreferencesTest {

    @Test
    fun `the default step delay is within the valid range`() {
        val preferences = UplinkPreferences()

        require(preferences.stepDelayMs in UplinkPreferences.STEP_DELAY_RANGE_MS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative step delay is rejected`() {
        UplinkPreferences(stepDelayMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a step delay above the slider's range is rejected`() {
        UplinkPreferences(stepDelayMs = UplinkPreferences.STEP_DELAY_RANGE_MS.last + 1)
    }

    @Test
    fun `the range's own endpoints are accepted`() {
        UplinkPreferences(stepDelayMs = UplinkPreferences.STEP_DELAY_RANGE_MS.first)
        UplinkPreferences(stepDelayMs = UplinkPreferences.STEP_DELAY_RANGE_MS.last)
    }
}
