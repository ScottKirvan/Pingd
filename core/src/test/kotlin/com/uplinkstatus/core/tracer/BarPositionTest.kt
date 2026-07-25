package com.uplinkstatus.core.tracer

import org.junit.Assert.assertEquals
import org.junit.Test

class BarPositionTest {

    @Test
    fun `next steps through all 5 positions in order`() {
        assertEquals(BarPosition.BAR_2, BarPosition.BAR_1.next())
        assertEquals(BarPosition.BAR_3, BarPosition.BAR_2.next())
        assertEquals(BarPosition.BAR_4, BarPosition.BAR_3.next())
        assertEquals(BarPosition.BAR_5, BarPosition.BAR_4.next())
    }

    @Test
    fun `next wraps from bar 5 to bar 1`() {
        assertEquals(BarPosition.BAR_1, BarPosition.BAR_5.next())
    }

    @Test
    fun `there are exactly 5 positions`() {
        assertEquals(5, BarPosition.entries.size)
    }

    @Test
    fun `start is bar 1`() {
        assertEquals(BarPosition.BAR_1, BarPosition.START)
    }
}
