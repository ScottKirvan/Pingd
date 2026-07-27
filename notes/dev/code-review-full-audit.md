# UplinkStatus — Formal Code Review

**Scope:** every `.kt` file under `app/src/main` and `core/src/main` (production
code), cross-checked against the full test suite (`app/src/test`,
`app/src/testDebug`, `core/src/test`) and the Android manifest/resources.

**Method:** the codebase was read as a system, not against a checklist. The
review traces the actual state machine end to end — preference storage →
visibility decision → service lifecycle → probe cycle → notification → settings
UI → back to preference storage — following control flow and thread ownership
through every hop, then separately verified each hop's test coverage to see
what the suite can and cannot actually prove. The description of what the app
does (Section 2) is derived solely from reading the implementation; it is not
copied from, or checked against, `notes/dev/uplink-status-indicator-spec.md`.
Section 8 then compares the two explicitly, since that comparison is only
meaningful once each side has been established independently.

---

## 1. Executive Summary

UplinkStatus is a small, well-separated Android system with a clean pure-Kotlin
core and a thinner Android integration layer. The domain logic — the tracer's
ping-pong sweep, the ENABLED/DISABLED/HIDDEN decision, DNS-vs-generic failure
classification — is correct, well-tested, and holds up under scrutiny with no
surprises. The problems in this codebase are not in what it computes; they are
in how the Android layer sequences and synchronizes that computation against
real-world asynchrony (background threads, DataStore writes, Activity
recreation).

Two of the issues found are critical: a network-outage retry loop that can
permanently prevent the service from being able to stop the probe cycle it
owns (Finding W1), and a race between restarting the service and the
preference write that restart depends on, which can make re-enabling the
feature silently fail (Finding W2). Both are invisible to the existing test
suite by construction — every test fake in the project removes the exact
asynchrony these bugs live in — which is consistent with the user-reported
history of UI-interaction bugs surfacing only on a real device. Three further
medium-severity issues (a rotation bug in the settings screen's edit lock, a
notification flicker on unrelated setting changes, and two instances of
cross-thread state with no real synchronization) round out the confirmed
defects. The review also surfaces one genuine divergence between the code and
its own spec document, and a short list of structural/naming fragilities worth
addressing before they compound.

None of this reflects poorly on the core architecture. It reflects a codebase
that got its pure logic right first and has, since real-device testing began,
been patched reactively at the seams where that logic meets Android's
lifecycle and threading model — exactly where this review focused.

---

## 2. System Overview (as implemented)

This section describes what the code does, built up from the implementation
alone.

### 2.1 Module split

- **`:core`** — plain Kotlin, no Android dependency, fully unit-testable on
  the JVM. Contains three sub-packages:
  - `probe` — `Prober` (interface), `TcpConnectProber` (real implementation:
    resolves a hostname, opens a `Socket`, times `connect()`, closes without
    sending/receiving payload), `ProbeTarget`, `ProbeResult` (`Success`,
    `Failure`, `DnsResolutionFailure` as distinct outcomes).
  - `tracer` — `AckTracer` (an 8-phase triangle-wave position counter over 5
    bar positions), `ProbeCycleRunner` (drives probe → ack → scheduled ack →
    scheduled gap → repeat, via an injected `Prober` and `TracerScheduler`),
    `CycleEvent`/`CycleListener` (the event contract consumers react to).
  - `visibility` — `UplinkVisibility` (three-state enum) and
    `VisibilityDecider` (a pure function of three booleans to one of the three
    states).
- **`:app`** — the Android integration layer:
  - `service` — `UplinkStatusService` (a `specialUse` foreground service that
    owns the `ProbeCycleRunner`'s lifecycle and reacts to visibility
    transitions), `UplinkNotificationController` (builds/posts the
    notification, implements `CycleListener`), `AndroidTracerScheduler`
    (`Handler.postDelayed` binding), `IconMapping` (bar position → drawable
    resource).
  - `prefs` — `UplinkPreferences` (an immutable snapshot: master toggle,
    hide-when-disabled, network scope, SSID whitelist, ping target host) and
    `UplinkPreferencesRepository`, backed by Jetpack DataStore.
  - `state` — `UplinkActivityStatus` (a process-wide "what to display right
    now" string), `UplinkRuntimeStatus` (a process-wide "what the service most
    recently, actually applied" report with a sequence counter),
    `NetworkScopeStatus`/`NetworkScopeMatcher` (reactive "is the current
    network in scope" derivation).
  - `connectivity` — `NetworkSnapshot`/`NetworkSnapshotProvider`, and the real
    `ConnectivityManager`-backed implementation.
  - `ui` — `SettingsScreen` (Compose) and `NotificationPermissionScreen`.
  - `MainActivity` — the permission gate that decides which of those two
    screens to show and whether to start the service.

### 2.2 Runtime behavior

At a running-system level, the code implements: an always-on foreground
service that repeatedly performs a TCP connect-time probe against a
user-selected host on port 443; drives a small state machine (`AckTracer`)
that advances one of 5 "bar lit" positions on every successful probe and again
on a fixed timer partway through each cycle, producing a scanner-style sweep
back and forth across the 5 positions; freezes that sweep in place (without a
distinct failure icon, but with distinguishable accessibility/status text)
whenever a probe fails, resuming from the same position once one succeeds;
renders the current position as one of six vector-icon frames swapped into a
persistent, low-priority status-bar notification; and derives whether that
notification should exist at all, be shown dimmed-and-paused, or be actively
cycling from three persisted preferences (master on/off, current network's
membership in a configurable scope, and a "hide vs. dim" preference for the
out-of-scope case) recombined on every live change to either the preferences
or the device's actual network state. A Compose settings screen edits those
preferences directly against the same DataStore file the service reads,
locking its own controls while a change is in flight so the user can't issue a
second edit before the first has been confirmed applied.

This is a coherent, sensible design for the stated goal. The findings below
are about where the *implementation* of that design has gaps, not about the
design itself.

---

## 3. Summary of Findings

| # | Severity | Finding | Location |
|---|----------|---------|----------|
| W1 | Critical | Outage-driven retry loop can permanently starve `stop()`, leaking the cycle and the notification past shutdown | `core/.../ProbeCycleRunner.kt`, `app/.../UplinkStatusService.kt` |
| W2 | Critical | Re-enabling the master toggle races service restart against the DataStore write it depends on | `app/.../SettingsScreen.kt`, `UplinkStatusService.kt` |
| W3 | Medium | Settings-panel edit lock does not survive configuration change (rotation) | `SettingsScreen.kt:126` |
| W4 | Medium | Live notification flickers to "paused" on any unrelated settings change or Activity recreation | `UplinkStatusService.kt`, `MainActivity.kt` |
| W5 | Medium | Two instances of cross-thread mutable state without real synchronization | `UplinkStatusService.kt:97`, `UplinkNotificationController.kt:87-89` |
| D1 | Divergence | Bar-position/latency reset is scoped to "cycle start," not "process lifetime" as the spec states | `UplinkStatusService.kt`, `UplinkNotificationController.kt` |
| F1 | Fragility | `SettingsScreen.kt` is trending toward a god file (569 lines, five responsibilities) | `SettingsScreen.kt` |
| F2 | Fragility | `UplinkActivityStatus` / `UplinkRuntimeStatus` naming invites confusion | `state/` package |
| F3 | Fragility | Silent no-op UI affordances (duplicate SSID add) | `SettingsScreen.kt:245-254` |
| F4 | Fragility | Duplicate permission check in `MainActivity` | `MainActivity.kt:43, 67` |

Full detail for each item follows in Sections 5–8.

---

## 4. Strengths

- **The pure domain layer (`:core`) is genuinely solid.** `VisibilityDecider`
  implements exactly the three-boolean truth table its own doc describes, with
  the master-toggle short-circuit structured so it's *impossible* for scope or
  hide-when-disabled to override it — the guarantee is enforced by control
  flow, not by convention. `AckTracer`'s triangle-wave phase math
  (`phase % 8`, mapped through `if phase <= 4 then phase else 8 - phase`)
  correctly produces the 1-2-3-4-5-4-3-2-1 ping-pong sweep with no direction
  flag and no off-by-one at the turnarounds, and this is confirmed by direct
  tests rather than just asserted in comments.
- **The seams are genuinely testable.** `Prober`, `TracerScheduler`,
  `UplinkPreferencesRepository`, `NetworkScopeStatus`, and
  `NetworkSnapshotProvider` are all interfaces specifically so production
  wiring (real sockets, real `Handler`, real DataStore, real
  `ConnectivityManager`) can be swapped for deterministic fakes. This is not
  cargo-culted DI — every one of these seams is actually exercised by a fake
  in the test suite, and the fakes are simple enough to audit by inspection.
- **DNS failure vs. generic failure is treated as a first-class distinction**
  throughout the whole stack (`ProbeResult.DnsResolutionFailure` →
  `FreezeReason.DNS_RESOLUTION_FAILURE` → a distinct string resource), not
  collapsed into a generic "failed" bucket at any layer — a level of care that
  often gets lost between a spec and its implementation.
- **The immediate-`startForeground()`-placeholder pattern**
  (`UplinkStatusService.onStartCommand`) correctly anticipates and avoids
  `ForegroundServiceDidNotStartInTimeException`, a real Android 12+ failure
  mode that's easy to hit accidentally with a service whose own logic can
  resolve to "don't show anything." The fact that this same mechanism is also
  the source of Finding W4 doesn't make the underlying instinct wrong — it
  makes the follow-through incomplete.
- **Test-to-code correspondence is unusually good for a project this size.**
  Nearly every behavioral claim made in a doc comment (immediate retry with no
  back-off, freeze-not-a-new-icon, dedup of repeated freeze notifications,
  master-toggle-always-wins) has a test that would fail if the claim were
  false, not just a test that happens to pass.

---

## 5. Weaknesses (Confirmed Defects)

### W1 — Critical: an outage can permanently starve the cycle's own `stop()`

**`core/src/main/kotlin/com/uplinkstatus/core/tracer/ProbeCycleRunner.kt:76-96`**
combined with
**`app/src/main/kotlin/com/uplinkstatus/app/service/UplinkStatusService.kt:79-86, 242-257`**.

`ProbeCycleRunner.runProbeAttempts()` is a `while (running)` loop that calls
the blocking `prober.probe()` synchronously; on `Failure` or
`DnsResolutionFailure` it loops straight back around with no scheduled delay
and no `return` — the correct implementation, in isolation, of "retry
immediately, no back-off."

The defect is where this loop executes. `UplinkStatusService` routes
`startCycle()`'s `runner.start()`, `stopCycle()`'s `runner.stop()`, and every
`AndroidTracerScheduler.postDelayed` callback through the same single
`workerHandler`, backed by one `HandlerThread`. A `Handler`/`Looper` runs one
posted `Runnable` to completion before it can dispatch the next one in its
queue.

During a continuous outage, `runProbeAttempts()`'s loop never returns — it
keeps calling `prober.probe()` synchronously, forever, without ever yielding
back to the `Looper`. If, during that time, the service decides to leave
`ENABLED` (master toggle off, scope change, `stopSelf()`), `stopCycle()` posts
`runner.stop()` onto that same queue — behind the still-executing, never-
returning failure loop. It cannot run. `running` never flips to `false`. The
loop cannot exit.

Consequences:
- The app-visible state (foreground service, notification) tears down
  correctly, since `applyVisibility`'s `HIDDEN`/`DISABLED` branches run
  synchronously on `Dispatchers.Default` and don't wait on the worker thread —
  but the old `ProbeCycleRunner` keeps running underneath, still calling
  `listener.onEvent(...)` on every retry.
- The moment a probe finally succeeds, control returns to the `Looper`, which
  then finally runs the long-queued `stop()` — but not before the stale cycle
  has already called `notify()` again, **resurrecting a notification the user
  explicitly turned off**, or showing stale "connected" content over what
  should be a dimmed `DISABLED` icon.
- `UplinkStatusService.onDestroy()`'s `workerThread.quitSafely()` only stops
  the queue from accepting *new* work; it cannot interrupt the runnable
  currently executing. The thread — and its blocking socket I/O — outlives the
  service object for as long as the outage lasts.
- Any *new* `ENABLED` transition during the same outage queues up behind the
  stuck runnable and silently cannot start until the network recovers.

This is invisible to the test suite by construction: every test overrides
`runOnWorker` to run synchronously on the calling thread
(`UplinkStatusServiceTest.kt:90`), and every scheduler fake captures callbacks
for the test to fire manually. There is no test anywhere that uses a real
`Handler`/`Looper`, so there is no way for the suite to express "two pieces of
work contending for one thread" at all.

**Consideration:** the fix is not "return to the looper more often" (a single
probe attempt is already bounded by its own timeout); it's that `stop()`
cannot even be *scheduled* while this loop runs. Any mechanism that lets the
calling thread flip `running` directly — bypassing the shared `Handler` queue
entirely for this one signal — closes the gap.

### W2 — Critical: re-enabling the master toggle races the restart against its own write

**`app/src/main/kotlin/com/uplinkstatus/app/ui/SettingsScreen.kt:128-131, 191-194`**
combined with **`UplinkStatusService.kt:104-116, 168-188`**.

Every control on the settings screen follows the same pattern:

```kotlin
fun markChangePending() {
    pendingBaselineSequence = runtimeReport.sequence
    ensureServiceRunning(context)     // synchronous, fires first
}
...
onCheckedChange = { enabled ->
    markChangePending()
    coroutineScope.launch { repository.setMasterToggleEnabled(enabled) }   // async, fires second
},
```

`ensureServiceRunning()` calls `startForegroundService()` before the coroutine
that persists the new value has even been launched. For most controls this is
harmless: the service is already running with an active preferences
collector, so the restart call is a no-op past the `observingPreferences`
guard, and the real recompute happens only when the write lands on the
*already subscribed* flow.

The master toggle is the exception, because it is the one control still
actionable while the service is not running at all (`HIDDEN` calls
`stopSelf()`; every other control is dimmed while `masterToggleEnabled` is
`false`). Turning it back on is exactly the case where `ensureServiceRunning()`
must start a genuinely new service instance, whose first `preferencesFlow`
subscription reads whatever is on disk **at that moment** — which may still be
the pre-write, stale `false`, since a Binder round-trip and a coroutine-
dispatched DataStore write have no ordering guarantee relative to each other.

If the stale read wins, the new service correctly derives `HIDDEN` again and
calls `stopSelf()` a second time, tearing down the collector it just
subscribed. When the real write lands moments later, nothing is listening —
the icon does not come back, silently, until some unrelated event happens to
call `startForegroundService()` again.

The existing regression test
(`SettingsScreenTest.kt:118`, *"turning the master toggle off then back on
restarts the service"*) cannot catch this: it only asserts that a
`startForegroundService()` Intent was sent, and its `FakeUplinkPreferencesRepository`
writes are synchronous in-memory assignments with `visibilityScope` running as
`Dispatchers.Unconfined` — there is no asynchrony left in the test double for
either ordering to race against.

**Consideration:** persist before restarting —
`coroutineScope.launch { repository.setMasterToggleEnabled(enabled); ensureServiceRunning(context) }`
— so the service is only ever kicked once the value it's about to read is
guaranteed to already be committed.

### W3 — Medium: the settings-panel edit lock does not survive rotation

**`SettingsScreen.kt:126`**: `var pendingBaselineSequence by remember { mutableStateOf<Int?>(null) }`.

This backs `isPending`, the mechanism that locks every control on screen until
`UplinkStatusService` confirms a prior change actually applied — necessary
because the write, the collector observing it, and the service acting on it
are three genuinely separate asynchronous steps (a fact this same codebase
discovered on a real device, per its own comments). It is declared with plain
`remember`, not `rememberSaveable`.

A configuration change (a rotation being the obvious trigger, since neither
the manifest nor `MainActivity` declares any `configChanges` handling)
recreates the composition and resets this to `null`. `isPending` becomes
`false` immediately, unlocking every control **before the service has
confirmed anything** — precisely the race this mechanism exists to prevent.
Every other piece of screen-local edit state that needs to survive this
(`newSsidText`, `customHostText`, `customHostError`, `showCustomHostInput`) is
correctly `rememberSaveable`; this field was missed.

**Consideration:** `rememberSaveable` (an `Int?` needs an explicit saver, or
restructure as a sentinel-backed primitive).

### W4 — Medium: the live notification flickers to "paused" on unrelated changes

**`UplinkStatusService.kt:125-143, 204-215`**.

`onStartCommand` unconditionally posts a placeholder
(`notificationForDisabled(currentNetworkScope)`) immediately, for a sound
reason: Android requires `startForeground()` shortly after
`startForegroundService()` regardless of what the service's own logic later
decides, and this avoids `ForegroundServiceDidNotStartInTimeException`.

The gap is what happens when the real state is already `ENABLED` with the
cycle running — the common case, since `SettingsScreen.ensureServiceRunning()`
calls `startForegroundService()` on **every** preference write (including ones
that cannot affect the currently active scope mode) and `MainActivity.onCreate`
does the same on every Activity recreation (rotation, app reopen). In that
case, `applyVisibility`'s `ENABLED` branch guards against re-running when
`cycleRunner?.isRunning == true` and does nothing — it never re-asserts the
live tracer content. The placeholder sticks, visibly showing "all bars dim /
paused" over a perfectly good connection, until the next real `CycleEvent`
happens to fire (up to roughly a second later in good conditions).

A related, lower-impact detail: that placeholder reads `currentNetworkScope`,
which is only updated inside the preferences collector — so the very first
flicker after a scope-changing edit briefly shows text for the *previous*
scope, not the one just selected.

**Consideration:** either suppress the restart call for edits that provably
cannot affect a currently running `ENABLED` state, or have the already-running
branch re-assert the live content (it already knows
`cycleRunner.currentPosition`) instead of silently no-op'ing.

### W5 — Medium: cross-thread mutable state without real synchronization

Two instances of the same underlying issue: a field touched from more than
one thread, annotated in a way that looks correct but is not.

- **`UplinkStatusService.kt:97`**, `currentNetworkScope` — a plain `var`,
  written inside the preferences collector (`Dispatchers.Default`) and read
  from `onStartCommand` (main thread) and `applyVisibility`'s `DISABLED`
  branch. No `@Volatile`, no other happens-before edge. Likely benign on
  current ART in practice, but not a guarantee — a genuinely stale read here
  is possible under the JMM.
- **`UplinkNotificationController.kt:87-89`**, `notifyCallCount` —
  `@Volatile var ... private set` incremented via `notifyCallCount++`.
  `@Volatile` fixes visibility, not atomicity; the increment is still a
  non-atomic read-modify-write. It happens to be safe today only because
  every `onEvent()` call is serialized through the single worker
  `HandlerThread` — an invariant nothing enforces, and one that Finding W1's
  fix would directly touch.

Neither has a demonstrated failure today; both are latent, and both would
become live bugs the moment the single-worker-thread assumption they quietly
depend on changes.

---

## 6. Fragilities (Not Yet Defects, Worth Addressing)

These are structural or stylistic issues that increase the odds of the next
bug, rather than being bugs themselves.

**F1 — `SettingsScreen.kt` is trending toward a god file.** At 569 lines it
owns: the screen's composition, four reusable widgets
(`SettingsToggleRow`, `NetworkScopeDropdown`, `PingTargetDropdown`,
`SsidWhitelistEditor`), the pending-lock state machine (Findings W2/W3),
hostname-preset helpers, and the service-restart side effect. A change to the
SSID whitelist UI and a change to the pending-lock timing both touch the same
file with no enforced boundary between them. The four widgets already take
plain data and callbacks, so extracting them is close to a pure file move; the
pending-lock bookkeeping would benefit from its own small, independently
testable type.

**F2 — `UplinkActivityStatus` vs. `UplinkRuntimeStatus` naming.** Two
process-wide singletons with genuinely different jobs — one is "what text to
show right now," the other is "has the service caught up with the last
decision" — named closely enough on generic "Activity"/"Runtime" adjectives to
be swapped by accident when skimming a diff or a stack trace.

**F3 — Silent no-op UI affordances.** Clicking "Add" on the SSID whitelist
editor with blank or already-listed text
(`SettingsScreen.kt:245-254`) does nothing at all — no error text, no
`markChangePending()` call, no signal of any kind. Correct behavior
(don't add a duplicate), indistinguishable presentation (a button that
appears broken).

**F4 — Duplicate permission check.** `MainActivity.kt:43` and `:67` both call
`hasNotificationPermission()` independently in `onCreate`. They necessarily
agree today; nothing enforces that a future edit to one site's logic is
mirrored in the other.

---

## 7. Spec Divergence

### D1 — Bar-position/latency reset is scoped to "cycle start," not "process lifetime"

The spec states: *"Position persists only for the lifetime of the running
process. An app restart resets to bar 1 — it is not restored from
preferences."* — tying the reset explicitly to the process restarting.

The implementation resets on every transition **into** `ENABLED` from a
not-running state, not only on an actual process restart:

```kotlin
UplinkVisibility.ENABLED -> {
    if (cycleRunner?.isRunning != true) {
        notificationController.resetSession()       // clears remembered latency/freeze-state
        startForeground(..., notificationForEnabled(BarPosition.START))  // bar 1, always
        startCycle()                                  // fresh AckTracer(), phase 0
    }
}
```

Within a single continuous service lifetime, `ENABLED → DISABLED` (leaving an
in-scope network) `→ ENABLED` (returning to it) resets the sweep to bar 1 and
clears the remembered latency/freeze state, rather than resuming — even though
the process never restarted. The original author appears to have read the
spec as "per session" rather than "per process"
(`resetSession()`'s own doc calls this "per-process reset per spec"), which is
a defensible reading but is not what the spec's text says, and it has a
concrete visible consequence: routine in/out-of-range movement under an SSID
whitelist makes the tracer visibly restart its sweep every time. This is a
product decision to confirm, not a clear-cut defect, which is why it is
recorded separately from Section 5.

---

## 8. Considerations and Recommendations

1. **Prioritize W1 and W2 above everything else in this document.** Both are
   silent-failure modes with no error surfaced anywhere — the app simply stops
   doing its one job (showing an accurate icon) until an unrelated event
   happens to correct it. Given the standing project rule that every bug fix
   needs a red/green regression test, both will require *new* test
   infrastructure to prove: W1 needs a test that drives a real
   `Handler`/`HandlerThread` (or an equivalent that models queue contention,
   since the current synchronous `runOnWorker` fakes cannot express the
   failure by design); W2 needs a real (or realistically delayed) DataStore
   write racing a real service restart, which `UplinkPreferencesRepositoryTest`'s
   existing real-DataStore setup is the closest existing scaffold for.
2. **Treat W3/W4 as a pair** — both stem from the same underlying tension
   (the settings screen's optimistic-lock UX assumes a cleaner
   request/confirm cycle than the service's restart-on-every-write pattern
   actually provides). Fixing W2's ordering and W4's over-broad restart
   trigger together would likely simplify both fixes.
3. **D1 needs a product decision, not just a code fix.** Confirm whether
   "resume from where the sweep froze" or "restart the sweep" is the intended
   behavior for a `DISABLED`⇄`ENABLED` cycle within one running process, then
   update whichever of the code or the spec is wrong.
4. **F1–F4 are low-urgency but cheap.** None require design changes; all four
   are reasonable candidates for a single cleanup pass once W1–W5 are
   resolved, so the cleanup doesn't complicate reviewing the actual bug fixes.
5. **No findings were made against `:core`.** The pure domain logic does not
   need defensive rework; effort is better spent entirely on the `:app`-layer
   threading and lifecycle issues this review identified.

---

## 9. Test Coverage Assessment

- Every service-level test replaces `runOnWorker` with a same-thread,
  synchronous lambda (`UplinkStatusServiceTest.kt:90`), and every scheduler
  fake (`FakeTracerScheduler`, `FakeScheduler`) captures callbacks for manual
  firing. No test in the suite exercises a real `Handler`/`HandlerThread`;
  Finding W1 is structurally unreachable by the current suite regardless of
  how thoroughly it's otherwise exercised.
- `FakeUplinkPreferencesRepository`'s writes are synchronous, in-memory
  `MutableStateFlow` assignments, and `UplinkStatusServiceTest` runs
  `visibilityScope` as `Dispatchers.Unconfined` — both remove exactly the
  asynchrony Finding W2 depends on. A test that could actually fail against
  W2 needs a real `DataStore<Preferences>` wired to a real
  `UplinkStatusService`/`ServiceController`, with a deliberate delay on one
  side to force each ordering in turn.
- No test recreates the Compose host mid-`isPending` window the way a real
  configuration change would, so Finding W3 has no regression coverage.
- Everything else in the suite — `:core` in particular — is thorough and
  well-targeted: the ping-pong sweep, the DNS-vs-generic distinction, the
  master-toggle-always-wins rule, and the no-back-off retry timing are all
  proven by tests that would fail if the claim were false, not merely
  asserted in a comment.
