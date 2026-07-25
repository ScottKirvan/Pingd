package com.uplinkstatus.core.tracer

/**
 * Holds the tracer's current bar position in memory only. There is no save/restore path
 * here by design — per spec, "position persists only for the lifetime of the running
 * process," so the only way to satisfy that honestly is to have nothing that could
 * persist it: a new instance always starts at [BarPosition.START] (or whatever [initial]
 * the caller passes for testing), and there is no method here that reads from or writes
 * to any storage.
 */
class AckTracer(initial: BarPosition = BarPosition.START) {

    var position: BarPosition = initial
        private set

    /** Advances the tracer one step and returns the new position. This is the only way
     * position ever changes — call it once per ack, never on a bare timer tick. */
    fun ack(): BarPosition {
        position = position.next()
        return position
    }
}
