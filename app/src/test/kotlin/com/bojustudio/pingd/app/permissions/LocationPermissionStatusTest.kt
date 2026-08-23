package com.bojustudio.pingd.app.permissions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [LocationPermissionStatus] is the only piece of the permission-refresh path that isn't
 * Android-shaped, so it is pinned here with plain JUnit: what counts as an event, and what
 * doesn't.
 *
 * The "doesn't" half carries the weight. Both call sites report unconditionally --
 * [com.bojustudio.pingd.app.MainActivity]'s `onResume` on every single resume, and the settings
 * screen on every permission-request result -- because neither can know whether the other
 * already noticed. Each of those reports triggers a synchronous re-read of every network the
 * device holds if it is treated as a change, so "the same state, again" has to be silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPermissionStatusTest {

    @Before
    fun reset() = LocationPermissionStatus.resetForTest()

    @After
    fun tearDown() = LocationPermissionStatus.resetForTest()

    @Test
    fun `a grant after a denial is an event`() = runTest {
        LocationPermissionStatus.report(granted = false)

        val changes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            LocationPermissionStatus.changes.toList(changes)
        }
        assertEquals(0, changes.size)

        LocationPermissionStatus.report(granted = true)

        assertEquals(1, changes.size)
    }

    @Test
    fun `re-reporting a state already recorded is not an event`() = runTest {
        LocationPermissionStatus.report(granted = true)

        val changes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            LocationPermissionStatus.changes.toList(changes)
        }

        // Every resume of the settings screen runs this call, and the permission-request result
        // runs it again moments later. Neither is news.
        repeat(5) { LocationPermissionStatus.report(granted = true) }

        assertEquals(0, changes.size)
    }

    @Test
    fun `a revocation is an event too, and so is the next grant`() = runTest {
        LocationPermissionStatus.report(granted = true)

        val changes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            LocationPermissionStatus.changes.toList(changes)
        }

        LocationPermissionStatus.report(granted = false)
        LocationPermissionStatus.report(granted = true)

        assertEquals(2, changes.size)
    }

    @Test
    fun `the state at subscription time is not replayed as a change`() = runTest {
        // A collector has, by definition, just started: the provider's synchronous seed read is
        // the first thing a new subscription does, so it already knows the current state and
        // replaying it would only buy a duplicate read of every network the device holds.
        LocationPermissionStatus.report(granted = true)

        val changes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            LocationPermissionStatus.changes.toList(changes)
        }

        assertEquals(0, changes.size)
    }
}
