package com.bojustudio.pingd.app.state

import com.bojustudio.pingd.core.visibility.PingdVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What [com.bojustudio.pingd.app.service.PingdStatusService] has actually, most recently applied
 * -- as opposed to [com.bojustudio.pingd.app.prefs.PingdPreferences], which is only what's been
 * *requested*. The two genuinely disagree for a real, measurable window after a settings
 * change: writing to DataStore, that write reaching the service's `combine()` collector, and
 * the collector actually calling `startForeground()`/`stopSelf()` are three separate
 * asynchronous steps, not one atomic operation. `SettingsScreen` uses [sequence] (not just
 * [visibility]) to know whether a change it just made has actually been acted on yet, rather
 * than assuming that happens the instant a preference is written -- comparing [visibility]
 * alone can't tell "nothing has happened yet" apart from "the service re-confirmed the exact
 * same state it was already in," since both look identical.
 */
object PingdRuntimeStatus {
    private val state = MutableStateFlow(Report(visibility = null, sequence = 0))

    val reports: StateFlow<Report> = state.asStateFlow()

    /** Called by [com.bojustudio.pingd.app.service.PingdStatusService] every time it applies a
     * visibility decision, whether or not it actually changed anything -- the sequence bump
     * is what lets a listener detect "a fresh decision just happened," not the value itself. */
    fun report(visibility: PingdVisibility) {
        state.update { Report(visibility, it.sequence + 1) }
    }

    /** This is a process-wide singleton, so tests that exercise it must reset it between runs
     * -- otherwise the sequence counter (and whatever a previous test last reported) leaks
     * across unrelated test methods sharing the same JVM. Production code never calls this. */
    internal fun resetForTest() {
        state.value = Report(visibility = null, sequence = 0)
    }

    data class Report(val visibility: PingdVisibility?, val sequence: Int)
}
