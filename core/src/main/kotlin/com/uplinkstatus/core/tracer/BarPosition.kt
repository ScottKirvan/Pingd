package com.uplinkstatus.core.tracer

/**
 * One of the 5 lit-bar tracer positions (the 6th icon state, "all bars dim," isn't a bar
 * position at all — it's rendered separately when the tracer is DISABLED/HIDDEN; see
 * [com.uplinkstatus.core.visibility.UplinkVisibility]).
 *
 * Deliberately a plain enum with no serialization support anywhere in this class or its
 * usage — per spec, bar position is session-only and must never be persisted. A fresh
 * process always starts a fresh [com.uplinkstatus.core.tracer.AckTracer] at [START].
 */
enum class BarPosition {
    BAR_1,
    BAR_2,
    BAR_3,
    BAR_4,
    BAR_5,
    ;

    /** The next position, wrapping from BAR_5 back to BAR_1. */
    fun next(): BarPosition = entries[(ordinal + 1) % entries.size]

    companion object {
        val START: BarPosition = BAR_1
    }
}
