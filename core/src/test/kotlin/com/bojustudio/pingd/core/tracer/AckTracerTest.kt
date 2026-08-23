package com.bojustudio.pingd.core.tracer

import org.junit.Assert.assertEquals
import org.junit.Test

class AckTracerTest {

    @Test
    fun `a fresh tracer starts at bar 1`() {
        assertEquals(BarPosition.BAR_1, AckTracer().position)
    }

    @Test
    fun `ack advances one bar at a time through all 5 positions`() {
        val tracer = AckTracer()

        assertEquals(BarPosition.BAR_2, tracer.ack())
        assertEquals(BarPosition.BAR_3, tracer.ack())
        assertEquals(BarPosition.BAR_4, tracer.ack())
        assertEquals(BarPosition.BAR_5, tracer.ack())
    }

    @Test
    fun `ack reverses direction at bar 5 instead of wrapping to bar 1`() {
        val tracer = AckTracer(BarPosition.BAR_5)

        assertEquals(BarPosition.BAR_4, tracer.ack())
    }

    @Test
    fun `ack reverses direction at bar 1 after having moved forward`() {
        val tracer = AckTracer(BarPosition.BAR_1)
        repeat(4) { tracer.ack() } // 1 -> 2 -> 3 -> 4 -> 5
        assertEquals(BarPosition.BAR_5, tracer.position)

        assertEquals(BarPosition.BAR_4, tracer.ack()) // reverses: 5 -> 4
        assertEquals(BarPosition.BAR_3, tracer.ack())
        assertEquals(BarPosition.BAR_2, tracer.ack())
        assertEquals(BarPosition.BAR_1, tracer.ack()) // reaches the other end
        assertEquals(BarPosition.BAR_2, tracer.ack()) // reverses again: back toward 5
    }

    @Test
    fun `a full sweep is a ping-pong, not a wrap - each end appears once per direction change`() {
        val tracer = AckTracer()
        val positions = generateSequence { tracer.ack() }.take(8).toList()

        assertEquals(
            listOf(
                BarPosition.BAR_2,
                BarPosition.BAR_3,
                BarPosition.BAR_4,
                BarPosition.BAR_5,
                BarPosition.BAR_4,
                BarPosition.BAR_3,
                BarPosition.BAR_2,
                BarPosition.BAR_1,
            ),
            positions,
        )
    }

    @Test
    fun `bar position is session-only - a new instance never remembers a previous one`() {
        val first = AckTracer()
        repeat(3) { first.ack() }
        assertEquals(BarPosition.BAR_4, first.position)

        // A brand-new instance (standing in for "a fresh process") always starts over at
        // BAR_1, regardless of anything that happened to a previous instance — there is
        // no persistence path (no save/load method exists on AckTracer at all) for it to
        // have picked up `first`'s position from.
        val second = AckTracer()
        assertEquals(BarPosition.BAR_1, second.position)
    }
}
