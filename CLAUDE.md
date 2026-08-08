# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
and `CONTRIBUTING.md` have since been rewritten for UplinkStatus
specifically (public-release cleanup), but keep an eye out for any
remaining template-generic language if you're touching either file.

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

## Working Conventions

- Branch names must describe the work (`fix/network-scope-multi-network`,
  `feat/scanner-preview`) — no random characters, UUIDs, or generated
  suffixes for uniqueness; if a name is taken, pick a more specific one.
- One concern per branch and PR. If work naturally splits into independent
  problems, split the branches too rather than bundling unrelated changes.
- `feat:` is for genuinely new user-facing capabilities only. Bug fixes
  and corrections use `fix:`, even when they close a tracked issue.
- Tests are written alongside all new code, not only bug fixes — see the
  red/green rule above for the (stricter) bug-fix case specifically.
- Run `./gradlew build` (compiles, tests, lints) before considering any
  commit or PR done — don't rely on `./gradlew test` alone to call
  something finished.
- Prefer narrow, localized changes that contain the blast radius of
  future edits. If a fix or feature can't avoid touching unrelated parts
  of the codebase, that's a design signal worth surfacing, not just
  pushing through.
- Refactoring is a first-class activity, not something to defer —
  improve structure as you go rather than accumulating debt for later.
- In unfamiliar domain territory (Android platform behavior especially),
  prefer primary sources — AOSP source, official docs — over general
  knowledge, and flag domain uncertainty explicitly rather than
  proceeding on an assumption. (See e.g. the `ConnectivityManager`
  callback-threading reasoning in
  `ConnectivityManagerNetworkSnapshotProvider.kt`, verified against AOSP
  source directly rather than assumed.)

## No Shortcuts

Nothing is deferred without explicit permission from the user. A known
issue is still a bug — don't mark it "won't fix," "by design," or "out
of scope" unilaterally. (This project has a direct precedent: a
dual-WiFi/cellular connectivity bug was once described as "a documented
design choice, not an oversight" — it wasn't; that was a previous
agent's self-justifying code comment being mistaken for a real product
decision. Don't repeat that mistake in either direction: don't invent a
design rationale to excuse an issue, and don't treat an existing
comment's rationale as authoritative without checking whether it holds.)

## Autonomy

Make implementation decisions independently — don't ask permission for
ordinary technical choices within stated requirements (e.g. which
Compose layout primitive to use, how to structure a new state object).
Escalate only when something would change scope, defer a requirement,
or contradict what the user has described as the goal. This is about
routine implementation judgment calls, not a license to skip the
destructive-action confirmations or multiple-choice-question restrictions
described elsewhere in this session's standing instructions.

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

## Git workflow

- `dev` is the ongoing integration branch — Claude owns it and commits/pushes
  directly to it.
- `main` is never touched (no commits, no merges) without explicit
  instruction from the user for that specific merge. When a `dev` → `main`
  PR merges, back-propagate `main` into `dev` afterward (a plain merge,
  not a rebase) so `dev` picks up anything that landed only on `main`
  (e.g. a Release Please version/changelog commit) — otherwise the next
  `dev` → `main` PR's diff will incorrectly show reverting that content.
- Claude may launch agents to work on other branches. Brief them on
  **what** to build, not **how** — implementation decisions belong to the
  agent, which is meant to serve as an independent second opinion on the
  approach, not just typing hands. After an agent completes: review its
  diff and tests before creating a PR, as a genuine independent code
  review (correctness, requirement alignment, test quality), not a
  compliance check. Fix simple issues found in review directly; send
  significant deviations from the stated requirements, or complex
  problems, back to the agent rather than patching over them. Agents
  don't create PRs themselves — Claude does, only after review passes.

### Attribution

No attribution of any kind in commit messages, PR bodies, or issue
text — no "Generated with," "Co-Authored-By," "Created by Claude," or
any AI/tool credit line. This applies to Claude's own direct commits/PRs
just as much as agent-authored ones — verify by reading the repo, not
from memory, since tooling (GitHub's PR-body auto-append in particular)
can inject attribution without any commit or PR body text ever having
asked for it:
- Read the actual commit messages (`git log`), not just what was typed
  in the commit command.
- Read the actual PR body text back after creating it (`pull_request_read`
  or equivalent) — the platform has repeatedly auto-appended a
  `_Generated by Claude Code_` footer to PR bodies in this project even
  when the body text itself never included one.
- Remove any attribution found, regardless of source, before considering
  the commit or PR finished.

## GitHub Issues

Check for duplicates before filing a new issue. Only file issues when
explicitly asked to — don't preemptively file future work just because
it was noticed along the way.

## Commit / versioning conventions

Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `feat!:`/`fix!:` for breaking changes, `docs:`, `chore:`,
etc.) — see `CONTRIBUTING.md` for the full list. `notes/CHANGELOG.md` and
`notes/VERSION.md` are generated by Release Please
(`.github/workflows/release.yml`,
`.github/release-please/release-please-config.json`) from those commit
messages — don't hand-edit either file.
