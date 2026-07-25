package com.uplinkstatus.core.visibility

/**
 * Implements the spec's "Enabled / Disabled / Hidden — State Logic" flowchart exactly:
 *
 * ```
 * Master toggle OFF          -> HIDDEN, unconditionally
 * Master toggle ON:
 *   On a network in scope    -> ENABLED
 *   Not on a network in scope:
 *     Hide when disabled ON  -> HIDDEN
 *     Hide when disabled OFF -> DISABLED
 * ```
 *
 * The critical rule, called out explicitly in the spec, is that [masterToggleEnabled]
 * being false always wins — [networkInScope] and [hideWhenDisabled] are never even
 * consulted in that case. The `if (!masterToggleEnabled) return HIDDEN` short-circuit
 * below is what guarantees that: scope/hide-when-disabled can't override the master
 * toggle because they're not reachable until after it's checked.
 */
object VisibilityDecider {

    fun decide(
        masterToggleEnabled: Boolean,
        networkInScope: Boolean,
        hideWhenDisabled: Boolean,
    ): UplinkVisibility {
        if (!masterToggleEnabled) {
            return UplinkVisibility.HIDDEN
        }

        if (networkInScope) {
            return UplinkVisibility.ENABLED
        }

        return if (hideWhenDisabled) UplinkVisibility.HIDDEN else UplinkVisibility.DISABLED
    }
}
