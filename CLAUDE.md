# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Keeping this file current

This file is the primary context for any agent working in this repo — keep
it accurate as the project evolves. As key files, build commands, and
architectural decisions emerge, record them here so future sessions start
with full context rather than re-deriving it. Update this file in the same
commit as the work it documents (design-decision changes belong in
`notes/dev/uplink-status-indicator-spec.md` instead — see below).

## Project state

`UplinkStatus` is an Android app (status-bar uplink health indicator)
with a working implementation: `:core` (pure-Kotlin probe/tracer/
visibility state machine) and `:app` (the `specialUse` foreground
service, notification wiring, and a Compose settings screen backed by
Jetpack DataStore). The staged Stage 0-7 build (scaffold through a
written device-testing protocol) is tracked in
`Agents/UplinkStatus_dev/STATUS.md`, and PR #6 (`dev` → `main`) carries
the same history. Since Stage 7, real device testing on a Pixel 6 Pro
has driven a further round of fixes and UX work directly against `dev`
(not through the staged agent process) — see `STATUS.md`'s most recent
entry for specifics (tracer sweep direction, immediate foreground
notification, settings-panel async-race lock, on-screen status line,
etc.). The repo was bootstrapped from `ScooterGitTemplate`; `README.md`
and `CONTRIBUTING.md` still carry some template-generic language (e.g.
CONTRIBUTING's "test by creating a new repo from your template" section
refers to the template repo, not this project) that hasn't been
rewritten for UplinkStatus specifically.

**Before changing app behavior, read
`notes/dev/uplink-status-indicator-spec.md` first.** It's the
authoritative design doc and encodes decisions that aren't obvious from
first principles, e.g.:
- The status-bar icon is 6 fixed vector-drawable frames (not an
  animation) swapped in place, advanced by an "ack" state machine driven
  by network probes — not a naive fixed-interval timer.
- "Ping" is a TCP connect-time probe, not ICMP — raw ICMP isn't available
  to unprivileged Android apps.
- The foreground service type must be `specialUse` (with a declared
  justification), not `dataSync` — `dataSync` is capped at 6 hours of
  runtime per day on Android 14+ and would kill an always-on indicator.
- Enabled/Disabled/Hidden visibility is a strict priority order (master
  toggle overrides everything else) — see the state diagram in the spec.
- iOS/cross-platform is explicitly out of scope: third-party iOS apps
  can't inject a status-bar icon, so this doesn't port as designed.

Update that spec doc (not this file) when design decisions change.
`notes/TODO.md` tracks known follow-ups (currently: designing a real
app launcher/shade icon — the placeholder white square is still in
place).

Every bug fix needs a red/green regression test: write or identify a
test that fails without the fix, confirm it fails for the right reason
(e.g. by temporarily reverting the fix), then restore the fix and
confirm it passes. This was a direct, standing instruction after a bug
recurred without one — don't skip it, and don't consider a fix done
until both halves are shown.

## Commands

```bash
./gradlew build   # compiles, assembles debug + release APKs, runs unit tests, lints
./gradlew test    # unit tests only (:app debug+release, :core)
```
Requires a local Android SDK (`compileSdk`/`targetSdk` 36, `minSdk` 34)
— set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or add a gitignored
`local.properties` with `sdk.dir=/path/to/sdk`. No device or emulator is
required for the test suite — the Compose UI tests run on the JVM via
Robolectric. `.github/workflows/android-ci.yml` runs the same
`./gradlew build` on every push/PR touching the Android project.

The docs site (VitePress, deployed to GitHub Pages from `docs/`):

```bash
cd docs
npm install
npm run docs:dev       # local dev server
npm run docs:build     # static build
npm run docs:preview   # preview the built output
```
The `docs.yml` workflow auto-deploys this site to Pages on pushes to
`main` that touch `docs/**`.

## Working conventions

- One concern per change. If work naturally splits into independent
  problems, keep them separate rather than bundling unrelated changes into
  one commit or PR.
- `feat:` is for genuinely new user-facing capability only; bug fixes and
  corrections use `fix:`, even when they close a tracked issue.
- Unit tests are written alongside all new code. Every bug fix needs a
  red/green regression test (see "Project state" above) — no exceptions.
- `./gradlew build` (compiles, tests, lints) must pass before committing.
  Don't assume another project's toolchain applies here — this repo's
  commands are listed under "Commands" below.
- Prefer narrow, localized changes. Favor modularity that contains the
  blast radius of an edit — a fix or feature shouldn't require touching
  unrelated parts of the codebase. If it does, that's a design signal
  worth surfacing to the user rather than working around silently.
- Refactoring is a first-class activity, not something to defer — improve
  structure as you go rather than accumulating debt for a later pass.
- In unfamiliar domain territory (Android platform behavior, network
  APIs), prefer primary sources — official docs, AOSP source, RFCs — over
  general knowledge, and flag domain uncertainty explicitly rather than
  proceeding on an assumption. This is why app-behavior changes start with
  `notes/dev/uplink-status-indicator-spec.md`, not first principles.
- Default to writing no comments. Add one only when the *why* is
  non-obvious — a hidden constraint, a subtle invariant, a workaround for
  a specific bug. If code is hard to understand, fix naming and structure
  rather than explaining around it with a comment.

## No shortcuts

Nothing is deferred without explicit permission from the user. A known
issue is still a bug — don't mark it "won't fix", "by design", or "out of
scope" unilaterally. If a library or package can't meet a stated
requirement, find an alternative or do the work from first principles
rather than deferring or watering down the requirement.

## Autonomy

Make implementation decisions independently — don't ask permission for
technical choices within stated requirements. Escalate only when
something would change scope, defer a requirement, or contradict what the
user has described as the goal.

## Git workflow

- `dev` is the ongoing integration branch — Claude owns it and commits/pushes
  directly to it.
- `main` is never touched (no commits, no merges) without explicit
  instruction from the user for that specific merge.
- Claude may launch agents to work on other branches for isolated pieces of
  work. Brief them on *what* to build, not *how* — implementation choices
  belong to the agent, which serves as an independent second opinion.
  Sub-agents don't land their own work: after an agent completes, review
  its diff and tests as a genuine code review (correctness, requirement
  alignment, test quality), not a rubber stamp. Fix small issues directly;
  send significant deviations from the stated requirements back to the
  agent. Only after review passes does the work get merged, with any
  attribution lines stripped from commit messages and PR bodies, before
  anything lands on `dev`.

## Commit / versioning conventions

Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `feat!:`/`fix!:` for breaking changes, `docs:`, `chore:`,
etc.) — see `CONTRIBUTING.md` for the full list. `notes/CHANGELOG.md` and
`notes/VERSION.md` are generated by Release Please
(`.github/workflows/release.yml`,
`.github/release-please/release-please-config.json`) from those commit
messages — don't hand-edit either file.
