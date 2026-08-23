package com.bojustudio.pingd.core.visibility

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
 *
 * [decide] deliberately takes a non-null [networkInScope]: it answers "given that we *know*
 * whether the network is in scope, what should be shown." Callers driven by an asynchronous
 * connectivity signal don't always know that yet, and must use [decideOrNull] instead —
 * see its doc for why "not known yet" must never be collapsed into `networkInScope = false`.
 */
object VisibilityDecider {

    fun decide(
        masterToggleEnabled: Boolean,
        networkInScope: Boolean,
        hideWhenDisabled: Boolean,
    ): PingdVisibility {
        if (!masterToggleEnabled) {
            return PingdVisibility.HIDDEN
        }

        if (networkInScope) {
            return PingdVisibility.ENABLED
        }

        return if (hideWhenDisabled) PingdVisibility.HIDDEN else PingdVisibility.DISABLED
    }

    /**
     * The same state machine as [decide], for callers whose network-in-scope signal is
     * asynchronous and may not have reported anything yet — `networkInScope = null` means
     * **"not known yet,"** which is emphatically *not* the same fact as "confirmed not on a
     * network in scope." Returns `null` for "no decision can be made yet; keep whatever is
     * currently shown and wait," rather than inventing an answer.
     *
     * This distinction is the whole substance of the fresh-install bug it was added for: the
     * connectivity layer used to hand this decision a placeholder "no network" value on every
     * service start, before the platform had actually reported anything, so the first
     * user-visible decision the service ever made was a deterministic (not racy) `DISABLED` /
     * `HIDDEN` — the paused tracer a fresh install got stuck on. A decision derived from the
     * *absence* of a report is not a decision; it is a guess that happens to be wrong whenever
     * the device is in fact on a network in scope, which on this app's happy path is always.
     *
     * [masterToggleEnabled] being false still short-circuits to [PingdVisibility.HIDDEN]
     * without waiting, because the spec's master-toggle-wins rule never consults the network
     * at all — there is nothing to wait *for* in that branch, and making the user's explicit
     * "off" wait on connectivity would be a second, opposite bug.
     */
    fun decideOrNull(
        masterToggleEnabled: Boolean,
        networkInScope: Boolean?,
        hideWhenDisabled: Boolean,
    ): PingdVisibility? {
        if (!masterToggleEnabled) {
            return PingdVisibility.HIDDEN
        }

        if (networkInScope == null) {
            return null
        }

        return decide(
            masterToggleEnabled = true,
            networkInScope = networkInScope,
            hideWhenDisabled = hideWhenDisabled,
        )
    }
}
