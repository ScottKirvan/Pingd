package com.uplinkstatus.core.tracer

import org.junit.Assert.assertEquals
import org.junit.Test

class BarPositionTest {

    // Stepping/sequencing (including the ping-pong direction reversal at each end) is
    // AckTracer's responsibility, not this enum's — see AckTracerTest for that behavior.

    @Test
    fun `there are exactly 5 positions, in bar order`() {
        assertEquals(
            listOf(
                BarPosition.BAR_1,
                BarPosition.BAR_2,
                BarPosition.BAR_3,
                BarPosition.BAR_4,
                BarPosition.BAR_5,
            ),
            BarPosition.entries,
        )
    }

    @Test
    fun `start is bar 1`() {
        assertEquals(BarPosition.BAR_1, BarPosition.START)
    }
}
