# UplinkStatus — Full Code Review

Scope: every `.kt` file under `app/src/main` and `core/src/main` (production code),
cross-checked against their test suites. This review is written from what the
code actually does, traced through the real state machine end to end
(DataStore → `VisibilityDecider` → `UplinkStatusService` → `ProbeCycleRunner` →
`UplinkNotificationController` → `SettingsScreen`), not from the spec. Where the
implementation disagrees with `notes/dev/uplink-status-indicator-spec.md`, that's
called out explicitly as a divergence rather than treated as a bug against this
document's own description.

Findings are ranked by how badly they can actually hurt the app in the hands of
a user, not by how big the diff to fix them is.

---

## 1. Critical: a sustained outage can wedge the probe cycle so `stop()` never runs

**`core/src/main/kotlin/com/uplinkstatus/core/tracer/ProbeCycleRunner.kt:76-96`**,
combined with **`app/src/main/kotlin/com/uplinkstatus/app/service/UplinkStatusService.kt:79-86, 242-257`**.

`ProbeCycleRunner.runProbeAttempts()` is a plain `while (running)` loop that calls
the (blocking) `prober.probe()` synchronously and, on `Failure`/`DnsResolutionFailure`,
loops straight back around with **no `scheduler.postDelayed` call and no `return`** —
that's the spec's "no back-off" requirement, correctly implemented in isolation.

The problem is *where* this loop runs. `UplinkStatusService` drives everything —
`startCycle()`'s `runner.start()`, `stopCycle()`'s `runner.stop()`, and every
`AndroidTracerScheduler.postDelayed` callback — through the **same single**
`workerHandler` (`UplinkStatusService.kt:86`, backed by one `HandlerThread`).
A `Handler`/`Looper` processes one posted `Runnable` to completion before it can
even look at the next one in its queue.

So: during a continuous outage, `runProbeAttempts()`'s `while` loop never returns —
it just keeps calling `prober.probe()` in a tight synchronous loop, forever, without
ever yielding back to the `Looper`. If, while that's happening, `UplinkStatusService`
decides to transition to `DISABLED` or `HIDDEN` (master toggle flipped off, network
scope changed, `stopSelf()` called), `stopCycle()` posts `runner.stop()` — but that
`Runnable` sits behind the still-running, never-returning failure loop **on the same
thread**, and can never be dispatched. `running` never becomes `false`. The loop
never exits.

Concretely, this means:
- Turning the master toggle off (or leaving the in-scope network) during an outage
  correctly tears down the foreground service/notification from the app's point of
  view (`applyVisibility`'s `HIDDEN`/`DISABLED` branches run synchronously on
  `Dispatchers.Default` and don't wait on the worker thread at all) — **but the old
  `ProbeCycleRunner` keeps running underneath**, still calling
  `listener.onEvent(...)` (i.e. `UplinkNotificationController`) on every failed
  retry, forever, on a thread nothing can stop.
- The moment the network recovers and a probe finally succeeds,
  `onProbeSucceeded()` returns control to the `Looper` — which then finally runs
  the long-queued `stop()`. But by then the stale cycle has already called
  `notify()` again via the ack, **resurrecting a status-bar notification the user
  explicitly turned off** (or showing stale "connected" content after a `DISABLED`
  transition that should have shown "all bars dim").
- `UplinkStatusService.onDestroy()` (`:147-154`) calls `workerThread.quitSafely()`,
  but `quitSafely()` only stops the queue from accepting *new* messages — it does
  not interrupt the currently-executing one. The `HandlerThread` (and its blocking
  socket I/O) survives the service object it belongs to for as long as the outage
  lasts.
- Any *new* `ENABLED` transition during the same outage (`startCycle()` posting a
  fresh `runner.start()`) also queues up behind the stuck old runnable and can't
  start until the network recovers, silently.

This is exactly the kind of bug that only shows up on a real device against a real
flaky network, never in the test suite — every test overrides
`UplinkStatusService.runOnWorker` to run synchronously (`{ it.run() }`,
`UplinkStatusServiceTest.kt:90`) and the `TracerScheduler` fakes
(`FakeTracerScheduler`, `FakeScheduler`) never touch a real thread. There is no
test anywhere that can even express "two different threads contending for the
same Handler queue," because nothing in the suite uses a real `Handler`/`Looper`.

**Fix direction:** don't route `stop()` through the same queue the failing loop is
spinning on. Either check a cooperative cancellation flag *inside* the failure
loop's `while` condition more aggressively won't help (it's already checked every
iteration — the problem is the checking thread never gets to run), or give
`ProbeCycleRunner` its own dedicated interrupt path (e.g. `stop()` sets `running`
directly as it already does, but `runProbeAttempts()` needs an actual mechanism to
be interrupted out of a blocking `prober.probe()` call — e.g. socket timeouts
already bound the worst case to ~1s per attempt, so the real gap is that `stop()`
can't even get *scheduled*, not that any single attempt takes too long).
The simplest structural fix: give `stop()` its own thread/mechanism that doesn't
share a FIFO queue with the thing it's trying to interrupt (e.g. an
`AtomicBoolean` checked directly by the loop, set from the calling thread with no
`Handler.post` indirection at all).

---

## 2. Critical: re-enabling the master toggle races the service restart against the write it depends on

**`app/src/main/kotlin/com/uplinkstatus/app/ui/SettingsScreen.kt:128-131, 191-194`**,
combined with **`UplinkStatusService.kt:104-116, 168-188`**.

Every settings mutation on screen follows the same pattern:

```kotlin
fun markChangePending() {
    pendingBaselineSequence = runtimeReport.sequence
    ensureServiceRunning(context)     // <-- synchronous, fires first
}
...
onCheckedChange = { enabled ->
    markChangePending()
    coroutineScope.launch { repository.setMasterToggleEnabled(enabled) }   // <-- async, fires second
},
```

`ensureServiceRunning()` calls `ContextCompat.startForegroundService(...)`
*before* the coroutine that actually persists the new value has even been
launched, let alone completed writing to disk.

For most preference changes this is harmless, because the service is already
running with an active `combine()` collector (`startObservingPreferencesIfNeeded`,
`UplinkStatusService.kt:168`) subscribed to the real `DataStore` flow — the
restart call is a no-op past the `observingPreferences` guard, and the real
recompute only ever happens when the write actually lands and the *already
subscribed* flow emits it. No race there.

But the master toggle is the one control that's actionable while the service is
**not** running (`HIDDEN` calls `stopSelf()`, per `applyVisibility`'s `HIDDEN`
branch, `UplinkStatusService.kt:225-237` — every other control is dimmed and
disabled while `masterToggleEnabled` is `false`, per `controlsEnabled` at
`SettingsScreen.kt:133`). So flipping it back on is exactly the one case where
`ensureServiceRunning()` has to actually *start a brand-new service instance*,
whose `onCreate`/`onStartCommand` will subscribe to `preferencesFlow` for the
first time and read whatever is on disk **right now**.

If that first read wins the race against the still-in-flight
`repository.setMasterToggleEnabled(true)` write (a real possibility: one is a
Binder round-trip through `system_server` and back, the other is a coroutine
dispatch plus an actual DataStore file write — neither has any ordering
guarantee relative to the other), the brand-new service reads the **stale**
`masterToggleEnabled = false`, `VisibilityDecider` correctly returns `HIDDEN`
again, and the service immediately calls `stopSelf()` a second time — tearing
down the collector it just subscribed. When the real write finally lands a
moment later, there is no one left listening: the DataStore flow emits into a
service instance that no longer exists. **The icon just doesn't come back**,
silently, until something else (another settings change, or the next full app
launch) happens to call `startForegroundService()` again.

The existing regression test
(`SettingsScreenTest.kt:118` — "turning the master toggle off then back on
restarts the service") only asserts that a `startForegroundService()` **Intent
was sent**; it can't catch this because `FakeUplinkPreferencesRepository`'s
writes (`FakeUplinkPreferencesRepository.kt:30-32`) are synchronous, in-memory
`MutableStateFlow` assignments, and `UplinkStatusServiceTest` runs
`visibilityScope` as `Dispatchers.Unconfined` — there is no possible ordering
in the test double where the restart wins the race, because there's no real
asynchrony left to race with.

**Fix direction:** persist the change before restarting the service, not after —
e.g. `coroutineScope.launch { repository.setMasterToggleEnabled(enabled); ensureServiceRunning(context) }`,
so the service is only ever kicked once the value it's about to read is
guaranteed to already be on disk.

---

## 3. Medium: the settings-panel lock doesn't survive rotation

**`SettingsScreen.kt:126`**

```kotlin
var pendingBaselineSequence by remember { mutableStateOf<Int?>(null) }
```

This is the state backing `isPending` — the whole reason the screen locks every
control after a change until `UplinkStatusService` confirms it actually applied
(the class doc at `:116-124` explains why this matters: the write, the collector
seeing it, and the service acting on it are three genuinely separate async
steps). It's declared with plain `remember`, not `rememberSaveable`.

A configuration change — a screen rotation being the obvious one, since
`MainActivity` declares no `android:configChanges` override and nothing else in
the manifest suggests this was considered — recreates the composition from
scratch and drops this value back to `null`. `isPending` immediately becomes
`false`, and every control re-enables, **even though the service has not
actually confirmed anything** — the exact race this mechanism exists to close.
A user who rotates their phone a beat after flipping a toggle can end up
issuing a second write while the first is still in flight, with no lock to stop
it. Every other piece of screen-local edit state that needs to survive this
(`newSsidText`, `customHostText`, `customHostError`, `showCustomHostInput`) is
correctly `rememberSaveable`; this one field was missed.

**Fix direction:** `rememberSaveable` (an `Int?` needs an explicit saver, or
store it as e.g. `-1` sentinel/two separate `Boolean`+`Int` saveable values).

---

## 4. Medium: every settings change (and every activity recreation) flickers the live notification to "paused"

**`UplinkStatusService.kt:125-143, 204-215`**

`onStartCommand` unconditionally posts a placeholder immediately:

```kotlin
startForeground(
    UplinkNotificationController.NOTIFICATION_ID,
    notificationController.notificationForDisabled(currentNetworkScope),
)
```

This exists for a real reason (documented in the comment above it): Android
requires `startForeground()` shortly after `startForegroundService()`
regardless of what the app's own logic later decides, and posting a safe
placeholder immediately avoids `ForegroundServiceDidNotStartInTimeException`.
That part is sound.

The problem is what happens next when the *real* state turns out to already be
`ENABLED` with the cycle already running — which is the common case, since
`SettingsScreen.ensureServiceRunning()` (`SettingsScreen.kt:567`) calls
`startForegroundService()` on **every single preference write**, not just ones
that could plausibly change visibility (adding an SSID to a whitelist that
isn't even the active scope mode still does this), and `MainActivity.onCreate`
(`MainActivity.kt:67-69`) does the same on every activity (re)creation — app
reopen, rotation, anything.

`applyVisibility`'s `ENABLED` branch guards against re-running:

```kotlin
UplinkVisibility.ENABLED -> {
    if (cycleRunner?.isRunning != true) {
        ...
        startForeground(..., notificationForEnabled(BarPosition.START))
        startCycle()
    }
}
```

If the cycle is already running (it is, in this scenario), **this branch does
nothing** — it never re-posts the live tracer content. So the immediate
`notificationForDisabled(...)` placeholder from `onStartCommand` sticks,
visibly showing "all bars dim / paused" over a connection that's actually
fine, until the next real `CycleEvent` (an ack or a freeze) happens to fire and
`UplinkNotificationController` calls `notify()` again on its own. In good
conditions that's up to ~1 second later; it's not a crash, but it's a real,
user-visible flicker on an icon whose entire job is to be a trustworthy
at-a-glance status indicator — triggered by things as unrelated as rotating the
phone or typing a custom hostname.

As a smaller related bug: that placeholder call reads `currentNetworkScope`
(`:139`), which is only updated inside the preferences collector
(`:178`) — so on the very first `onStartCommand` of a settings-change-triggered
restart, it's reading whatever scope was current *before* this change, not the
one that triggered the flicker. Low-impact (it only affects which flavor of
"disabled" text momentarily flashes), but worth knowing about since it's the
same root cause.

**Fix direction:** either don't call `ensureServiceRunning()`/
`startForegroundService()` for changes that provably can't affect a currently-
running `ENABLED` state, or have the `ENABLED` branch's already-running case
re-assert the live content instead of silently no-op'ing (cheap: it already
knows `cycleRunner.currentPosition`).

---

## 5. Medium: shared mutable state crosses threads with no real synchronization

Two instances of the same underlying mistake — "the field is touched from more
than one thread, so it's marked in a way that *looks* thread-safe but isn't
actually correct":

- **`UplinkStatusService.kt:97`** — `currentNetworkScope` is a plain `var`,
  written inside the preferences collector (`visibilityScope`, i.e.
  `Dispatchers.Default`, `:178`) and read from `onStartCommand` (`:139`, main
  thread) and `applyVisibility`'s `DISABLED` branch. It has no `@Volatile` and
  no other happens-before edge tying the writer to the main-thread reader. In
  practice this will usually work on current Android/ART, but it's relying on
  incidental behavior, not a guarantee — a genuinely stale read here is
  possible per the JMM.
- **`UplinkNotificationController.kt:87-89`** —
  ```kotlin
  @Volatile
  internal var notifyCallCount: Int = 0
      private set
  ```
  `@Volatile` only fixes *visibility*, not atomicity — `notifyCallCount++` is
  still a non-atomic read-modify-write. This happens to be safe today only
  because every call to `onEvent()` is serialized through the single worker
  `HandlerThread`; nothing enforces that invariant, and the `@Volatile`
  annotation actively suggests to a future reader that this field is already
  safe for concurrent increments, which it is not.

Neither of these has a demonstrated failure mode today (both rely on the
current single-worker-thread design), but both are latent — the moment either
assumption changes (e.g. as part of fixing #1 above), these become real bugs
rather than coincidentally-correct code.

---

## Spec divergence: bar position/latency reset is scoped to "cycle start," not "process lifetime"

The spec is explicit: **"Position persists only for the lifetime of the running
process. An app restart resets to bar 1 — it is not restored from
preferences."** That phrasing describes the reset as tied to the *process*
restarting.

The implementation resets on every transition **into** `ENABLED` from a
not-running state, not only on an actual process restart:

```kotlin
// UplinkStatusService.kt
UplinkVisibility.ENABLED -> {
    if (cycleRunner?.isRunning != true) {
        notificationController.resetSession()      // clears remembered latency/freeze-state
        startForeground(..., notificationForEnabled(BarPosition.START))  // bar 1, always
        startCycle()                                 // fresh AckTracer(), phase 0
    }
}
```

`startCycle()` always constructs a brand-new `ProbeCycleRunner` with the
default `tracer = AckTracer()` (`ProbeCycleRunner.kt:36`), which always starts
at `BarPosition.START`/phase 0. So within a single continuous process/service
lifetime, going `ENABLED` → `DISABLED` (leaving an in-scope network) →
`ENABLED` (coming back into scope) resets the sweep back to bar 1, rather than
resuming from wherever it was. The same reset hits `lastLatencyMs` and
`lastNotifiedState` in `UplinkNotificationController.resetSession()`.

The original author was aware of this and evidently read the spec as meaning
"per session" (see `resetSession()`'s own doc: *"matching bar position's own
per-process reset per spec"*, and `UplinkStatusService`'s class doc calling a
fresh `ENABLED` transition "a fresh cycle"). That's a defensible reading, but
it's a real interpretive choice, not something the spec states directly — and
it has a concrete, visible consequence: walking in and out of a whitelisted
SSID's range repeatedly (a completely normal, expected real-world usage
pattern for that scope mode) makes the tracer visibly jump back to bar 1 every
time, rather than continuing its sweep, even though the *service process*
never restarted. Worth a product-owner decision on which behavior is actually
intended, since the current code and the spec's literal wording don't obviously
agree.

---

## Naming and structure

- **`UplinkActivityStatus` vs. `UplinkRuntimeStatus`**
  (`app/src/main/kotlin/com/uplinkstatus/app/state/`) — two process-wide
  singletons with genuinely different jobs (one is "what text to show right
  now," the other is "has the service caught up with the last decision yet,"
  per their own doc comments) but names similar enough to swap by accident when
  skimming a diff. Worth distinguishing more sharply, e.g.
  `UplinkStatusText`/`UplinkVisibilityAckTracker` or similar — names that say
  *what each one is for* rather than both leaning on generic
  "Activity"/"Runtime" adjectives.

- **`SettingsScreen.kt` (569 lines) is trending toward a god file.** It
  currently owns: the screen's own composition, three separate reusable
  widgets (`SettingsToggleRow`, `NetworkScopeDropdown`, `PingTargetDropdown`,
  `SsidWhitelistEditor`), the pending-lock state machine described in findings
  #2/#3 above, hostname-preset helpers, and the service-restart side effect
  (`ensureServiceRunning`). None of this is wrong in isolation, but it means a
  change to, say, the SSID whitelist UI and a change to the pending-lock
  timing logic both touch the same 569-line file with no enforced boundary
  between them. Splitting the four private composables out (they take plain
  data + callbacks already, so this is close to a pure file move) and pulling
  the pending-lock bookkeeping into its own small class would make each piece
  reviewable and testable on its own.

- **Silent no-op on duplicate SSID.**
  `SettingsScreen.kt:245-254` — clicking "Add" with text that's already in the
  whitelist (or blank) does precisely nothing: no error text, no visual
  feedback, `markChangePending()` isn't even called. It's correct not to add a
  duplicate, but a user clicking "Add" and having the button appear to do
  nothing is indistinguishable from the button being broken.

- **`MainActivity.kt:43, 67`** checks
  `hasNotificationPermission()` twice in `onCreate` — once inside the
  `remember` initializer that picks which screen to show, once again right
  after `setContent()` to decide whether to start the service. Both
  necessarily agree at that point in time, so this isn't a bug, just needless
  duplication that a future edit could accidentally desync (e.g. if one call
  site's permission list ever needs to change and the other is missed).

---

## Test coverage gaps (why none of the above showed up already)

- Every service test replaces `runOnWorker` with a same-thread, synchronous
  lambda (`UplinkStatusServiceTest.kt:90`) and every scheduler fake
  (`FakeTracerScheduler`, `FakeScheduler`) captures callbacks for the test to
  fire manually — there is no test anywhere in the suite that exercises a real
  `Handler`/`HandlerThread`, so finding #1 (the thread-starvation deadlock)
  is structurally invisible to the current suite; it can only be reproduced
  against a real device/emulator with a real flaky network.
- `FakeUplinkPreferencesRepository`'s writes are synchronous in-memory
  `MutableStateFlow` assignments (`FakeUplinkPreferencesRepository.kt:30-32`),
  and `UplinkStatusServiceTest` runs `visibilityScope` as
  `Dispatchers.Unconfined` — both remove exactly the asynchrony that finding
  #2 (the master-toggle restart race) depends on to manifest. A test against
  this would need a real `DataStore<Preferences>` (as
  `UplinkPreferencesRepositoryTest` already sets up) wired into a real
  `UplinkStatusService`/`ServiceController`, with an artificial delay inserted
  on one side to force the ordering both ways.
- No test recreates the Compose host (the way a real configuration change
  would) mid-`isPending` window, so finding #3 (the rotation-drops-the-lock
  bug) has no regression coverage either.

---

## What's solid

Worth saying plainly: `:core` (`AckTracer`, `ProbeCycleRunner`'s sequencing
logic in isolation, `VisibilityDecider`, `TcpConnectProber`'s exception
mapping) is clean, well-factored, and thoroughly unit-tested for the behavior
it actually claims to have — the ping-pong sweep math, the DNS-vs-generic
failure distinction, and the master-toggle-always-wins rule all check out
exactly against the spec with no surprises. Every bug above is either a
threading/lifecycle issue in how `:app` drives `:core` (findings #1, #2, #5)
or a UI-state-management issue local to `SettingsScreen` (#3, #4) — the pure
domain logic itself held up well under scrutiny.
