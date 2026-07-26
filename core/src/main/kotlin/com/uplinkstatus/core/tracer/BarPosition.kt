package com.uplinkstatus.core.tracer

/**
 * One of the 5 lit-bar tracer positions (the 6th icon state, "all bars dim," isn't a bar
 * position at all — it's rendered separately when the tracer is DISABLED/HIDDEN; see
 * [com.uplinkstatus.core.visibility.UplinkVisibility]).
 *
 * Deliberately a plain enum with no serialization support anywhere in this class or its
 * usage — per spec, bar position is session-only and must never be persisted. A fresh
 * process always starts a fresh [com.uplinkstatus.core.tracer.AckTracer] at [START].
 *
 * This enum holds no stepping/sequencing logic of its own (no `next()`) — the tracer's
 * motion is a ping-pong bounce (1→2→3→4→5→4→3→2→1→2→...), not a simple wrap, which means
 * "what comes next" depends on which direction the tracer is currently moving, not just
 * the current position. That direction is session state, so it lives in [AckTracer], the
 * class that already owns the tracer's mutable state.
 */
enum class BarPosition {
    BAR_1,
    BAR_2,
    BAR_3,
    BAR_4,
    BAR_5,
    ;

    companion object {
        val START: BarPosition = BAR_1
    }
}
