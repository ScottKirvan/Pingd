package com.uplinkstatus.core.visibility

/** The three states the status-bar icon can be in. See [VisibilityDecider] for how these
 * are derived from user preferences and network scope. "Hidden" is not a 7th icon frame —
 * it's the absence of the icon, per spec. */
enum class UplinkVisibility {
    /** Icon shown, tracer cycling. */
    ENABLED,

    /** Icon shown, all bars dim, tracer paused (not cycling). */
    DISABLED,

    /** Icon not shown at all. */
    HIDDEN,
}
