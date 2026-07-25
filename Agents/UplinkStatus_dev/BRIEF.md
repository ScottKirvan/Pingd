# UplinkStatus — Dev Agent Project Brief

## Mission
Implement the Uplink Status Indicator Android app described in
`notes/dev/uplink-status-indicator-spec.md`, in the staged increments
below, each landing on the persistent `dev` integration branch after
independent review. The end state is feature-complete per the spec, as
fully tested as is achievable without physical-device access,
documented for end users, and accompanied by a device-testing protocol
— ready for the project owner's final review and merge to `main`.

## Source of truth
`notes/dev/uplink-status-indicator-spec.md` is authoritative for every
product, design, and architecture decision already made, including the
Implementation Baseline section (language, SDK floor, UI toolkit,
preferences storage, test device). This brief governs *process*, not
product decisions — don't duplicate the spec here, read it before
starting each stage.

## Non-negotiable engineering standards
- **No scope creep.** Build exactly what the spec and the current
  stage's acceptance criteria describe. Extra polish or extra features
  are scope creep too — note good ideas for later instead of building
  them now.
- **No "known limitation" shortcuts.** If the clean solution to
  something in this stage seems to require reworking a decision from an
  earlier stage, or reworking part of the architecture, do that rework.
  A comment, a TODO, or a caveat in a PR description is not a
  substitute for solving it. A stage is done when its acceptance
  criteria are actually met — not "met except for X."
- **Every stage ships with unit tests covering its logic.** A stage
  without adequate tests is not complete, full stop.
- **Document real decisions.** Where the spec and this brief leave
  something to your judgment (module layout, naming, specific component
  structure), make the call and explain it in the PR description — not
  to ask permission, but so it gets a second, independent read.

## Autonomy and escalation
You will not be asked to pause for design questions, and you should not
wait for one. Where something is genuinely ambiguous at the product or
architecture level (not just an implementation detail), make the most
reasonable call consistent with the rest of the spec, document why you
made it, and keep going. If a later stage reveals that an earlier
stage's approach needs to change, change it — that's expected, not a
failure. The only things that stop progress are things that need a
human's own hands (see Out of Scope).

## Process
- Each stage is implemented in its own isolated branch/worktree, based
  on the current tip of `dev`.
- Commit and test locally within your worktree. **Do not push to
  `origin` and do not merge into `dev` yourself** — a separate reviewing
  agent (a different Claude Code session, operating with its own
  independent read of the spec) pulls your branch, reviews your
  approach as a second opinion, and performs the merge into `dev`. This
  is deliberate: two independent implementations of judgment calls are
  more informative than one.
- A stage isn't reported as complete until its acceptance criteria
  (below) are actually met.

## Platform baseline (see spec for full detail)
Kotlin, Jetpack Compose for in-app UI, `minSdk` 34 / `targetSdk` latest
stable, Jetpack DataStore for preferences. Probe and tracer/ack
state-machine logic must be plain Kotlin, no Android framework
dependency, unit-testable on the JVM.

## Out of scope for this handoff
- iOS / cross-platform (explicitly out of scope per the spec — this
  isn't a "later stage," it's a permanent boundary)
- Play Store listing, app signing, release management, or any
  submission to Google — stays a manual action by the project owner
- Multi-OEM device testing beyond the Pixel 6 Pro (Android 14+) — call
  out known risk areas (e.g. aggressive OEM battery management on other
  manufacturers) in the testing protocol doc rather than attempting to
  test them without the hardware

## Stages

### Stage 0 — Project scaffold + CI
**Goal:** a building, empty-but-real Android project (Kotlin, Compose,
`minSdk` 34) with CI enforcing unit tests on every change. No feature
logic yet.

**Acceptance criteria:**
- `./gradlew build` and `./gradlew test` succeed locally and in CI.
- A GitHub Actions workflow under `.github/workflows/` runs on pushes/
  PRs touching the Android project and fails the build on test failure.
- Manifest with placeholder application id, no permissions beyond what
  boot requires.
- A minimal Compose screen proves the UI toolkit is wired up (throwaway
  — Stage 3 replaces it with the real settings screen).
- A short note (README or docs) on how to build/run locally.

### Stage 1 — Core probe + tracer/ack state machine
**Goal:** the probe cycle (TCP connect-time probe, not ICMP), the
ack-driven tracer position state machine, and the enabled/disabled/
hidden decision logic, exactly as specified in the spec's Core
Mechanism and State Logic sections — pure Kotlin, no Android dependency.

**Acceptance criteria:**
- Unit tests cover: ack cycle timing/sequencing (probe-success ack,
  automatic 500ms ack, the non-ack 500ms gap), freeze-on-failure and
  resume-on-success behavior, immediate no-back-off retry, bar position
  being session-only (never persisted), and the full enabled/disabled/
  hidden truth table — including that the master toggle always wins
  regardless of network scope.
- Probe timeout, probe failure, and DNS-resolution failure (as a
  *distinct* condition from generic probe failure, per spec) are all
  covered by tests using fakes/mocks — no real network calls in unit
  tests.
- No notification, service, or UI code in this stage — logic only.

### Stage 2 — Foreground service + notification wiring
**Goal:** wire Stage 1's state machine into a live `specialUse`
foreground service driving the six icon frames via a low-priority
ongoing notification.

**Acceptance criteria:**
- `specialUse` foreground service type with a declared justification in
  the manifest (per spec — not `dataSync`).
- `POST_NOTIFICATIONS` runtime permission requested with a real
  rationale flow, not just a manifest entry.
- Notification updates fire only on an ack (tracer advance) or a state
  transition — never on a bare timer tick (per spec).
- Notification carries real accessibility text (e.g. "Uplink:
  connected, 42ms"), not just the icon.
- The six icon drawables move from `assets/media/icons/` into proper
  `res/drawable` vector resources.
- Tests (Robolectric or equivalent) verify the service's reaction to
  state-machine events — not manual-only verification.

### Stage 3 — Settings UI + preferences
**Goal:** the real settings screen — master toggle, hide-when-disabled,
network scope selector, SSID whitelist management, ping target host
(defaults + custom override).

**Acceptance criteria:**
- All preferences persisted via DataStore and survive process death.
- SSID whitelist UI requests `ACCESS_FINE_LOCATION` only when the user
  actually turns on SSID whitelisting, never up front (per spec).
- Ping target defaults (`one.one.one.one`, `dns.google`) are selectable
  and a custom hostname entry is supported and validated.
- Unit tests for preference read/write logic; Compose UI tests for the
  settings screen's core interactions.

### Stage 4 — Live connectivity integration
**Goal:** wire `ConnectivityManager.NetworkCallback` to drive real state
transitions from actual network scope matching (WiFi, cellular, or SSID
whitelist, per the user's chosen scope setting).

**Acceptance criteria:**
- Network transitions (WiFi connect/disconnect, SSID change, cellular
  fallback per scope setting) correctly drive the enabled/disabled/
  hidden state per the spec's flowchart, verified with tests using fake
  `NetworkCallback` events — not just manual reasoning.
- SSID reading is correctly gated behind the Stage 3 location
  permission flow.

### Stage 5 — Edge-case and accessibility hardening pass
**Goal:** close every gap raised during spec review, and self-audit the
whole `dev` branch against the "no known-limitation shortcuts" rule.

**Acceptance criteria:**
- DNS resolution failure is confirmed distinct from generic probe
  failure end-to-end (not just in Stage 1's unit tests).
- Notification accessibility text is present and meaningful in all
  reachable states.
- Hostname-based probe targets resolve correctly on both IPv4 and IPv6.
- A written self-audit in the PR description walks through every
  "Explicitly Out of Scope" and "Technical Notes" bullet in the spec,
  confirming each is actually implemented as specified — not
  approximated. Anything found short gets fixed in this stage, not
  deferred to a future one.

### Stage 6 — VitePress user documentation
**Goal:** real user-facing documentation in `docs/`, replacing the
current template placeholder content.

**Acceptance criteria:**
- Covers install, the settings screen, what each icon state means, and
  troubleshooting (e.g. "icon isn't showing," "tracer looks frozen").
- Builds cleanly with `npm run docs:build`.

### Stage 7 — Device testing protocol
**Goal:** a written, step-by-step manual testing protocol for a human to
run on the Pixel 6 Pro (Android 14+).

**Acceptance criteria:**
- Delivered as `notes/dev/device-testing-protocol.md` — a document, not
  code or an automated test.
- Covers: install, every permission flow, all state transitions
  (enabled/disabled/hidden and the transitions between them), Doze/
  screen-off behavior, notification visibility/update behavior over an
  extended run, settings persistence across app restart and device
  reboot, and explicitly flags OEM battery-management variance as an
  untested risk category outside this protocol's device scope.

## Definition of done
`dev` contains all 7 stages merged, CI green on `dev`, and a final PR
from `dev` to `main` is open and ready for the project owner's review.
