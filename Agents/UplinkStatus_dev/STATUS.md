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
Status: **merged into `dev`** (commit `17f89e2`, fast-forwarded)

**Dev agent's approach:** replaced Stage 2's `VisibilityInputs` mutable
singleton entirely rather than bolting DataStore onto its shape — split
into `UplinkPreferencesRepository` (real, persisted, `Flow`-based: master
toggle, hide-when-disabled, network scope + SSID whitelist, ping target
host) and a much smaller `NetworkScopeStatus` (still just Stage 4's
manual stand-in for live network-in-scope detection, clearly TODO-marked).
`UplinkStatusService` now runs a `combine()` of the preferences flow and
the network-scope stand-in, re-deriving `VisibilityDecider`'s result on
every emission — so a settings change reaches an already-running service
without a restart, not just a one-time read at start. Real Compose
settings screen covers every preference in the spec, with
`ACCESS_FINE_LOCATION` (+ required `ACCESS_COARSE_LOCATION`) requested
only at the point the user selects SSID-whitelist scope, never up front.

**Reviewing agent's independent read:** rebuilt clean — 98 test
executions across `:app` (both variants) and `:core`, 0 failures, lint
clean. Specifically checked the reactive-flow rework for soundness (the
`combine().collect()` shape, and that the one test proving a live
preference change reaches a running service without a second
`onStartCommand` call actually exercises that path, not just the
persistence layer in isolation). Confirmed the location-permission
request is deferred to point-of-use and the hostname validator is
appropriately lightweight (obviously-wrong input only; a
plausible-but-unresolvable host still correctly surfaces as `:core`'s
`DnsResolutionFailure` at probe time, not a false sense of validation).
No scope creep — no `ConnectivityManager` code touched. Approved without
changes.

**Minor note for later stages, not a blocker:** `applyVisibility()`
(which calls `startForeground`/`stopForeground`/`stopSelf`) now runs
from a `Dispatchers.Default` coroutine rather than the main thread,
extending a pattern already established in Stage 2 (notification calls
already happened off the main thread there). This is expected to be
fine — these particular Android APIs proxy to system services over
Binder and aren't documented as main-thread-only — but it's exactly the
kind of thing worth keeping an eye on during Stage 7's real-device
testing rather than assuming from a Robolectric pass alone.

## Stage 4 — Live connectivity integration
Status: **merged into `dev`** (commit `9ad5064`, fast-forwarded)

**Dev agent's approach:** `ConnectivityManagerNetworkSnapshotProvider`
uses `registerDefaultNetworkCallback` (not a broad `NetworkRequest`) so
"the network in scope" maps directly to "the OS's actual default
network," rather than reimplementing that judgment over every network
the device happens to be holding onto. `NetworkScopeMatcher` is a pure,
Android-free function implementing all four scope modes; deliberately
asymmetric on `NET_CAPABILITY_VALIDATED` — WiFi/Cellular/SSID-whitelist
modes check transport only, `ANY_CONNECTION` requires validation — so a
connected-but-broken network still shows the probe cycle's own
frozen-tracer signal instead of collapsing into DISABLED/HIDDEN and
hiding it. SSID whitelist mode checks `hasLocationPermission` as an
explicit input rather than inferring it from a null SSID. Restructured
Stage 3's `NetworkScopeStatus` from a bare mutable stand-in into an
interface + real `combine()`-based class, matching the same pattern
already established for preferences.

**Reviewing agent's independent read:** rebuilt clean — 158 total test
executions (127 `:app`, 31 `:core`, `:core` untouched by this stage as
expected), 0 failures, lint clean. Verified the validation-asymmetry
reasoning holds up against the spec's freeze-on-failure design, and that
new service-level tests prove connectivity-only changes (no preference
change) correctly drive HIDDEN/DISABLED/ENABLED transitions on an
already-running service. Test fixtures use reflection to build
`NetworkCapabilities` in one test file, worked around a compile-time SDK
stub gap in this sandbox (the mutator methods used are genuine public
platform API); isolated to test code, doesn't affect production
correctness. One inaccuracy in the agent's self-report: it stated "5
`:core` executions," which didn't match the actual, unchanged count of
31 — a tally mistake in the summary, not a defect in the code or a real
test regression. Approved.

## Stage 5 — Edge-case and accessibility hardening pass
Status: **merged into `dev`** (commit `5795233`, fast-forwarded)

**Dev agent's approach:** Fixed the confirmed gap first: `UplinkNotificationController.onEvent()`
treated every `CycleEvent.Frozen` as a blanket no-op, so a DNS-resolution failure and a
generic probe failure were indistinguishable, and a real outage looked identical to
"everything's fine" — nothing about the notification ever changed on any freeze. The icon
still never gets a distinct "lost" frame (freezing in place is correct and required per
spec), but the accessibility text now updates per `FreezeReason`, with genuinely distinct
strings for `PROBE_FAILURE` vs `DNS_RESOLUTION_FAILURE`
(`notification_text_probe_failure`/`notification_text_dns_failure`). This had to be more
than "just call notify() on every Frozen," though: `ProbeCycleRunner`'s immediate
no-back-off retry loop emits one `Frozen` per failed attempt, potentially many per second
during a sustained outage, and posting on every one would itself violate the spec's "not on
every internal timer tick" rule — the exact concern the old no-op implementation's doc
comment raised. Added `lastNotifiedState` tracking (connected, or frozen-for-a-specific-
reason) so a repeat `Frozen` with an *unchanged* reason is suppressed, while a transition
into a freeze or a change in *why* it's frozen still posts. Added `notifyCallCount`
(internal, test-only) as an observability seam proving the suppression actually happens,
not just that the visible end state looks right.

Went on to self-audit every "Explicitly Out of Scope" and "Technical Notes" bullet against
the actual code (not just re-reading prior stages' summaries). Everything else checked out
already correct and already adequately tested (no new tests added for these, per the
brief's "don't add redundant tests" guidance) — see the dev agent's full audit walkthrough
in the PR description / final report. One real spec defect found and fixed (in the spec
doc, not the code): the "User Preferences" section's "Ping target host" bullet said the
default was the bare IP literal `1.1.1.1` (alternate `8.8.8.8`), directly contradicting the
"Core Mechanism" section two pages earlier, which explains at length why the default must
be a *hostname* (`one.one.one.one`/`dns.google`) so the OS can pick the right address family
per network — and which the code has always correctly matched
(`ProbeTarget.DEFAULT_HOST`/`ALTERNATE_HOST`). The User Preferences bullet was simply stale
text never updated after that reasoning was written; corrected in place with the
contradiction explained inline, matching how Stage 2 documented its Handler-thread
correction.

Added end-to-end test coverage (not just at `UplinkNotificationControllerTest`'s or
`:core`'s `ProbeCycleRunnerTest`'s unit level) by making `UplinkStatusService`'s
`notificationController` an injectable seam (matching the existing
`prober`/`schedulerFactory`/`preferencesRepository`/`networkScopeStatus` pattern) and adding
a `RecordingNotificationController` test double that delegates to the real implementation
while recording every `CycleEvent` it receives. New service-level tests drive a real
`ProbeCycleRunner`, created and started by the real, running `UplinkStatusService`, through
a scripted sequence of generic failure -> DNS failure -> success, and separately confirm the
no-back-off retry behavior (no delay ever scheduled for a failed attempt) holds at the
service level, not just in `:core`'s existing bounded-sequence unit tests.

**Tests:** all existing tests still pass; net +7 new/rewritten tests across
`UplinkNotificationControllerTest` (14, was 10) and `UplinkStatusServiceTest` (15, was 12).
Full `./gradlew build` (assemble debug+release, all unit tests, lint) passes clean.

**Reviewing agent's independent read:** verified the confirmed-gap fix
directly — read `onEvent`'s new dedup logic line by line, confirmed
`lastNotifiedState` genuinely distinguishes reason-changes from repeats
rather than just widening what counts as "connected," and confirmed the
spec-defect fix (stale `1.1.1.1`/`8.8.8.8` bullet) against
`ProbeTarget.DEFAULT_HOST`/`ALTERNATE_HOST` in `:core` before accepting
it. Rebuilt clean independently: 172 total test executions (141 `:app`,
31 `:core`), 0 failures, lint clean. Spot-checked the new end-to-end
service-level tests (`a real cycle run inside the service reports
generic and DNS failures as distinct CycleEvents in order`, `repeated
generic failures inside a real running cycle only post the failure
notification once`) and confirmed they exercise a real running
`ProbeCycleRunner`/`UplinkStatusService`, not a shortcut back to
unit-level fakes. Approved without changes.

**Process note:** this stage's dev agent wrote its own entry in this
log ahead of review (marked "awaiting independent review," not claiming
an approval that hadn't happened) and worked on a self-named branch
(`stage5-hardening`) rather than the default worktree branch, which the
reviewing session had to notice before merging. Harmless this time, but
future stage prompts should say explicitly: don't touch `STATUS.md`
(that's the reviewing session's record) and commit to the worktree's
default branch unless there's a reason not to.

## Stage 6 — VitePress user documentation
Status: not started

## Stage 7 — Device testing protocol
Status: **merged into `dev`** (commit `8d5b505`, fast-forwarded)

**Dev agent's approach:** `notes/dev/device-testing-protocol.md`, a
500-line human-executed script organized into Install/First-run,
Permission flows (including deliberate deny paths for both notification
and location), State transitions (with the master-toggle-always-wins
rule as its own explicit numbered sequence, C5/C6, not folded into a
generic "verify enabled/disabled/hidden work"), Doze/screen-off, an
extended-run section, Settings persistence (app-restart *and* full
device reboot, plus a contrast case proving bar position deliberately
does *not* survive), a dedicated "Named real-device-only risks" section
turning all four STATUS.md-flagged items (dim-bar alpha, no-back-off
retry against a fast-refusing host, off-main-thread service lifecycle
calls, hostname resolution across IPv4/IPv6/dual-stack) into concrete
numbered steps with real pass conditions, and an explicit out-of-scope
statement for multi-OEM battery-management variance.

**Reviewing agent's independent read:** spot-checked every quoted
UI string in the document (the notification-permission rationale text,
the denial message, the disabled/probe-failure/DNS-failure notification
text) against the actual `strings.xml` — all matched exactly, word for
word, confirming the protocol was written against the real app rather
than plausible-sounding invented text. Confirmed no code was touched
(a pure `.md` addition) and that `STATUS.md` was correctly left alone
this time, unlike Stage 5. Approved without changes.
