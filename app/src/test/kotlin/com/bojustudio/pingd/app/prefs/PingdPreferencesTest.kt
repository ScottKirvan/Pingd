package com.bojustudio.pingd.app.prefs

import com.bojustudio.pingd.core.history.ProbeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PingdPreferences.stepDelayMs]'s and [PingdPreferences.historyWindowMs]'s range validation
 * -- everything else on this data class is a plain default, exercised indirectly through
 * [PingdPreferencesRepositoryTest] and the various fakes/tests that construct it. This file
 * exists only for the `init` checks, which are direct-constructor behavior no other test
 * happens to cover.
 */
class PingdPreferencesTest {

    @Test
    fun `the default step delay is within the valid range`() {
        val preferences = PingdPreferences()

        require(preferences.stepDelayMs in PingdPreferences.STEP_DELAY_RANGE_MS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative step delay is rejected`() {
        PingdPreferences(stepDelayMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a step delay above the slider's range is rejected`() {
        PingdPreferences(stepDelayMs = PingdPreferences.STEP_DELAY_RANGE_MS.last + 1)
    }

    @Test
    fun `the range's own endpoints are accepted`() {
        PingdPreferences(stepDelayMs = PingdPreferences.STEP_DELAY_RANGE_MS.first)
        PingdPreferences(stepDelayMs = PingdPreferences.STEP_DELAY_RANGE_MS.last)
    }

    @Test
    fun `the default history window is the spec's seven minutes, and within the valid range`() {
        val preferences = PingdPreferences()

        assertEquals(ProbeHistory.DEFAULT_WINDOW_MS, preferences.historyWindowMs)
        assertEquals(7 * 60 * 1000L, preferences.historyWindowMs)
        require(preferences.historyWindowMs in PingdPreferences.HISTORY_WINDOW_RANGE_MS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a history window below the slider's range is rejected`() {
        PingdPreferences(historyWindowMs = PingdPreferences.HISTORY_WINDOW_RANGE_MS.first - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a history window above the slider's range is rejected`() {
        PingdPreferences(historyWindowMs = PingdPreferences.HISTORY_WINDOW_RANGE_MS.last + 1)
    }

    @Test
    fun `the history window range's own endpoints are accepted`() {
        PingdPreferences(historyWindowMs = PingdPreferences.HISTORY_WINDOW_RANGE_MS.first)
        PingdPreferences(historyWindowMs = PingdPreferences.HISTORY_WINDOW_RANGE_MS.last)
    }

    @Test
    fun `the history window range is whole minutes, so the slider's stops land on real values`() {
        assertEquals(0L, PingdPreferences.HISTORY_WINDOW_RANGE_MS.first % PingdPreferences.HISTORY_WINDOW_STEP_MS)
        assertEquals(0L, PingdPreferences.HISTORY_WINDOW_RANGE_MS.last % PingdPreferences.HISTORY_WINDOW_STEP_MS)
        assertEquals(0L, PingdPreferences().historyWindowMs % PingdPreferences.HISTORY_WINDOW_STEP_MS)
    }

    /** The window is bounded by memory as well as by the slider -- a window wide enough to
     * blow past [ProbeHistory.MAX_SAMPLES] at ordinary pacing would quietly stop meaning what
     * it says. At the default 500ms step delay the cycle produces roughly one probe per
     * second, so this is the arithmetic that keeps the two settings consistent with each
     * other. */
    @Test
    fun `the widest history window still fits inside the sample cap at default pacing`() {
        val probesPerSecondAtDefaultPacing = 1
        val widestWindowSeconds = PingdPreferences.HISTORY_WINDOW_RANGE_MS.last / 1000

        assertTrue(widestWindowSeconds * probesPerSecondAtDefaultPacing <= ProbeHistory.MAX_SAMPLES)
    }
}
