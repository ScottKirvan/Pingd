package com.uplinkstatus.core.visibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibilityDeciderTest {

    @Test
    fun `master toggle off is always HIDDEN regardless of scope or hide-when-disabled`() {
        for (networkInScope in listOf(false, true)) {
            for (hideWhenDisabled in listOf(false, true)) {
                assertEquals(
                    "masterToggleEnabled=false, networkInScope=$networkInScope, " +
                        "hideWhenDisabled=$hideWhenDisabled",
                    UplinkVisibility.HIDDEN,
                    VisibilityDecider.decide(
                        masterToggleEnabled = false,
                        networkInScope = networkInScope,
                        hideWhenDisabled = hideWhenDisabled,
                    ),
                )
            }
        }
    }

    @Test
    fun `master toggle on and in scope is ENABLED regardless of hide-when-disabled`() {
        for (hideWhenDisabled in listOf(false, true)) {
            assertEquals(
                UplinkVisibility.ENABLED,
                VisibilityDecider.decide(
                    masterToggleEnabled = true,
                    networkInScope = true,
                    hideWhenDisabled = hideWhenDisabled,
                ),
            )
        }
    }

    @Test
    fun `master toggle on, out of scope, hide-when-disabled on is HIDDEN`() {
        assertEquals(
            UplinkVisibility.HIDDEN,
            VisibilityDecider.decide(
                masterToggleEnabled = true,
                networkInScope = false,
                hideWhenDisabled = true,
            ),
        )
    }

    @Test
    fun `master toggle on, out of scope, hide-when-disabled off is DISABLED`() {
        assertEquals(
            UplinkVisibility.DISABLED,
            VisibilityDecider.decide(
                masterToggleEnabled = true,
                networkInScope = false,
                hideWhenDisabled = false,
            ),
        )
    }

    @Test
    fun `full truth table over all 8 boolean combinations`() {
        val expected = mapOf(
            Triple(false, false, false) to UplinkVisibility.HIDDEN,
            Triple(false, false, true) to UplinkVisibility.HIDDEN,
            Triple(false, true, false) to UplinkVisibility.HIDDEN,
            Triple(false, true, true) to UplinkVisibility.HIDDEN,
            Triple(true, false, false) to UplinkVisibility.DISABLED,
            Triple(true, false, true) to UplinkVisibility.HIDDEN,
            Triple(true, true, false) to UplinkVisibility.ENABLED,
            Triple(true, true, true) to UplinkVisibility.ENABLED,
        )

        for ((inputs, expectedVisibility) in expected) {
            val (masterToggleEnabled, networkInScope, hideWhenDisabled) = inputs
            assertEquals(
                "master=$masterToggleEnabled scope=$networkInScope hideWhenDisabled=$hideWhenDisabled",
                expectedVisibility,
                VisibilityDecider.decide(masterToggleEnabled, networkInScope, hideWhenDisabled),
            )
        }
    }

    // --- decideOrNull: "not known yet" is not an input value, it is the absence of one ------

    @Test
    fun `an unknown network yields no decision at all, rather than falling through to DISABLED`() {
        assertNull(
            VisibilityDecider.decideOrNull(
                masterToggleEnabled = true,
                networkInScope = null,
                hideWhenDisabled = false,
            ),
        )
    }

    @Test
    fun `an unknown network yields no decision even when hide-when-disabled would make it HIDDEN`() {
        // The more damaging half of the same mistake: with hide-when-disabled on, treating
        // "nothing reported yet" as "out of scope" doesn't just dim the icon, it resolves to
        // HIDDEN -- which the service implements by stopping itself outright.
        assertNull(
            VisibilityDecider.decideOrNull(
                masterToggleEnabled = true,
                networkInScope = null,
                hideWhenDisabled = true,
            ),
        )
    }

    @Test
    fun `master toggle off still wins immediately, without waiting on an unknown network`() {
        // The spec's master-toggle-wins rule never consults the network, so there is nothing
        // to wait for -- deferring here would strand the user's explicit "off" behind a
        // connectivity report it does not depend on.
        assertEquals(
            UplinkVisibility.HIDDEN,
            VisibilityDecider.decideOrNull(
                masterToggleEnabled = false,
                networkInScope = null,
                hideWhenDisabled = false,
            ),
        )
    }

    @Test
    fun `a known network decides identically through decideOrNull and decide`() {
        for (masterToggleEnabled in listOf(false, true)) {
            for (networkInScope in listOf(false, true)) {
                for (hideWhenDisabled in listOf(false, true)) {
                    assertEquals(
                        "master=$masterToggleEnabled scope=$networkInScope hide=$hideWhenDisabled",
                        VisibilityDecider.decide(masterToggleEnabled, networkInScope, hideWhenDisabled),
                        VisibilityDecider.decideOrNull(masterToggleEnabled, networkInScope, hideWhenDisabled),
                    )
                }
            }
        }
    }
}
