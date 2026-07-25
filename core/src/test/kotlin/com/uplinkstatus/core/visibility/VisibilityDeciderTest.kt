package com.uplinkstatus.core.visibility

import org.junit.Assert.assertEquals
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
}
