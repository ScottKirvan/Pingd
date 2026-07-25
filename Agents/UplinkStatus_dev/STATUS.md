# UplinkStatus — Dev Progress Log

Living record of each stage's outcome: what the dev agent built, the
reviewing agent's independent read, and what merged into `dev`. Updated
after every stage.

## Stage 0 — Project scaffold + CI
Status: **merged into `dev`** (commit `2c142b7`, merged `1da0ea7`)

**Dev agent's approach:** Kotlin + Jetpack Compose, minSdk 34 / targetSdk
36 (confirmed latest stable at build time, independent of the AGP/Gradle
choice), AGP 8.13.0 / Gradle 8.13 / Kotlin 2.0.21 (deliberately not the
brand-new AGP 9 / Gradle 9 line — reasoned as unnecessary risk for a
scaffold stage). Single `:app` module, deferring a `:core` split until
Stage 1's logic actually needs the separation. Zero manifest permissions.
One throwaway Compose placeholder screen. Two genuine tests: a
`BuildConfig` sanity check and a Robolectric-based Compose UI test — both
would fail on a real regression, not just pass trivially. New
`android-ci.yml` workflow (separate from the existing `docs.yml`),
triggering on `main` and `dev`, running `./gradlew build` including
lint.

**Reviewing agent's independent read:** Re-ran `./gradlew clean test`
from scratch rather than trusting the report — reproduced the same 5/5
passing tests independently. Traced the Compose test's assertion back to
the actual rendered string constant to confirm it's a real check, not a
tautology. Manifest, permission deferral reasoning, and module-structure
call all independently reasonable; no scope creep found — the agent
stayed inside Stage 0's boundary and didn't reach into probe/state-
machine/notification territory. Approved without changes.

**Process note:** the agent's isolated worktree was based on `main`
(pre-PR-#5) rather than `dev`, because worktree isolation clones from
`origin`'s default branch rather than honoring local checkout — it
didn't have `BRIEF.md`/`STATUS.md`/the finalized spec on disk. Harmless
this time because the reviewing session inlined the essential Stage 0
requirements directly into the agent's prompt, but future stage prompts
will have the agent explicitly `git fetch origin dev` and check it out
as its first step, so this isn't relied on again.

## Stage 1 — Core probe + tracer/ack state machine
Status: **merged into `dev`** (commit `f0196d0`, fast-forwarded)

**Dev agent's approach:** new `:core` Gradle module on the plain
`kotlin.jvm` plugin (not `kotlin.android`) — a build-enforced boundary
against Android framework dependencies, not just a convention. TCP
connect probe (`TcpConnectProber`) with DNS resolution as an explicit
separate step so `UnknownHostException` maps to a distinct
`ProbeResult.DnsResolutionFailure`, not folded into generic `Failure`.
The 5-step ack cycle (`ProbeCycleRunner`) is driven entirely by an
injected `TracerScheduler` and `Prober` — no real sleeping, no real
sockets in unit tests. Failure retries are a `while` loop, not
recursion, specifically so a long real-world outage can't grow the call
stack. `VisibilityDecider.decide()` short-circuits to `HIDDEN` before
scope/hide-when-disabled are even reachable when the master toggle is
off, which is what makes "master toggle always wins" a structural
guarantee rather than a convention someone could accidentally violate
later. Correctly self-corrected the worktree-base process gap from
Stage 0 by fetching and resetting onto `origin/dev` as its first step.

**Reviewing agent's independent read:** read the cycle implementation
line by line against the spec's 5-step description, specifically
checking the one place a subtle bug was most likely — whether the
second 500ms gap produces an ack (it must not) — and confirmed the code
and its test both get this right. Confirmed the visibility truth table
test is exhaustive (all 8 boolean combinations, not just the "obvious"
cases). Rebuilt `:core` from scratch independently (`rm -rf core/build
&& ./gradlew :core:test`) rather than trusting the report: 31/31 tests
passing. No scope creep found — no notification/service/UI code leaked
into this stage. Approved without changes, fast-forward merge (no
conflicts).

**Note for later stages:** the immediate no-back-off retry loop, as
built, has no artificial floor between synchronous connect attempts —
if a target host actively refuses connections fast (as opposed to
timing out), retries could fire back-to-back with no gap at all. This
is what the spec explicitly asks for ("no adaptive back-off," "retries
immediately") and isn't a defect, but it's worth keeping in mind during
Stage 7's device testing as a real-world battery/CPU consideration on a
persistently-refusing host, not just a theoretical one.

## Stage 2 — Foreground service + notification wiring
Status: **merged into `dev`** (commit `ba2512b`, fast-forwarded)

**Dev agent's approach:** `UplinkStatusService` (`specialUse`, correctly
justified in the manifest) owns only lifecycle (`startForeground`/
`stopForeground`/`stopSelf`) and driving `:core`'s `ProbeCycleRunner`;
`UplinkNotificationController` is the single `notify()` call site,
implementing `CycleListener` directly so `Frozen` events are a
structural no-op (satisfies "never notify on a bare tick") and
`Advanced` events carry real latency text. `HIDDEN` tears the whole
service down (`stopForeground` + `stopSelf`), matching "hidden is not a
7th icon, it's absence." `VisibilityInputs` is an explicit, documented
stand-in for Stage 3/4's real preferences/connectivity — in-memory only,
not wired to anything persistent yet, by design.

**Notable proactive correction:** caught that running the probe cycle
on the literal main looper (as the original spec's Technical Notes said)
would risk ANRs, since `Prober.probe()` blocks synchronously and retries
immediately with no back-off during an outage. Moved the cycle to a
dedicated background `HandlerThread` and updated the spec's Technical
Notes section itself to reflect the correction, transparently marked as
"revised in Stage 2" with the reasoning inline — exactly the "rework the
core system, don't shortcut around it" behavior the brief asks for.
Also added a `latencyMs` field to `:core`'s `CycleEvent.Advanced`
(default `null`, non-breaking) so notification text can show real
latency without duplicating probe-timing logic in `:app`.

**Reviewing agent's independent read:** rebuilt clean (`rm -rf app/build
core/build && ./gradlew build`) rather than trusting the report — 70
test executions (across debug+release variants plus `:core`), 0
failures. Specifically traced the `Frozen`-is-a-no-op path and the
`HIDDEN`-stops-service path by hand against the spec, and confirmed the
service-level test suite proves the master-toggle-always-wins rule
holds end-to-end (not just at the `VisibilityDecider` unit level from
Stage 1). No scope creep — no settings UI, no DataStore, no real
`ConnectivityManager` wiring leaked into this stage. Approved without
changes.

## Stage 3 — Settings UI + preferences
Status: not started

## Stage 4 — Live connectivity integration
Status: not started

## Stage 5 — Edge-case and accessibility hardening pass
Status: not started

## Stage 6 — VitePress user documentation
Status: not started

## Stage 7 — Device testing protocol
Status: not started
