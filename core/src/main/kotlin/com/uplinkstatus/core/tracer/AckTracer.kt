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

    /** Where we are in the 8-state ping-pong cycle (0..7): 0,1,2,3,4,3,2,1, then repeats.
     * Seeded from [initial]'s ordinal so a tracer constructed mid-sweep (as several tests
     * do) continues in the same direction a fresh one would have arrived from, rather than
     * needing a separate direction flag. Kept bounded to 0..7 (via `% CYCLE_LENGTH` on every
     * update, never left to grow unbounded) so there's no overflow concern no matter how
     * long a single process runs. */
    private var phase: Int = initial.ordinal

    /** Advances the tracer one step and returns the new position — a ping-pong sweep across
     * the 5 bars (1,2,3,4,5,4,3,2,1,2,3,...), matching the KITT/scanner motion the icon is
     * meant to show, not a wrap straight from bar 5 back to bar 1.
     *
     * There are only 8 distinct states in a full sweep (0,1,2,3,4,3,2,1 — bar 5 and bar 1
     * each appear once per direction change, not twice), so this is a closed-form triangle-
     * wave mapping from a cyclic phase counter to a bar ordinal, rather than a mutable
     * direction flag with explicit flip conditions to get subtly wrong at the endpoints. */
    fun ack(): BarPosition {
        phase = (phase + 1) % CYCLE_LENGTH
        val ordinal = if (phase <= HALF_CYCLE) phase else CYCLE_LENGTH - phase
        position = BarPosition.entries[ordinal]
        return position
    }

    private companion object {
        private val HALF_CYCLE = BarPosition.entries.size - 1 // 4: the BAR_5 turnaround point
        private val CYCLE_LENGTH = 2 * HALF_CYCLE // 8: a full forward+backward sweep
    }
}
