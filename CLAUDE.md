# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Keeping this file current

This file is the primary context for any agent working in this repo — keep
it accurate as the project evolves. Update it in the same commit as the
work it documents (design-decision changes belong in
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
  If a branch name is pre-assigned by tooling (a hosted agent session, a
  CI runner) rather than chosen by you, verify it against this convention
  before the first push — a random-suffixed name like
  `claude/project-onboarding-setup-qs2zwk` isn't exempt just because you
  didn't pick it. Rename locally (`git branch -m <name>`) first.
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
- Keep data and view genuinely separated across the `:core`/`:app`
  boundary — watch for presentation decisions (how a value maps to a
  screen position, a color, a scale) leaking into `:core`, which should
  only ever hold domain data and data-processing decisions (aggregation,
  windowing, retention). This is a standing concern, not a one-off: it's
  the kind of architectural oversight that creates bugs and
  unmaintainable/unfixable code, so watch for it proactively rather than
  only when it's pointed out. (Concrete instance found in this project:
  `ProbeHistory`'s sparkline functions return points pre-scaled into the
  0..1 unit square — `windowFraction()` for x, `latencyColorFraction()`
  for y — baking a specific linear presentation scale into `:core`
  instead of exposing domain values and letting `:app` own the
  value-to-position transform. Flagged when a request for a log/sqrt
  time-axis scale surfaced that this coupling would force a `:core`
  change for what's fundamentally a view decision; fix planned as its
  own refactor.)

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

## Communication style

Never use the multiple-choice question tool (`AskUserQuestion`) in this
repo — ask plain natural-language questions in normal text instead.
Keep explanations concise; don't restate context or reasoning already
given earlier in the same conversation.

## Autonomy

Make implementation decisions independently — don't ask permission for
ordinary technical choices within stated requirements (e.g. which
Compose layout primitive to use, how to structure a new state object).
Escalate only when something would change scope, defer a requirement,
or contradict what the user has described as the goal. This is about
routine implementation judgment calls, not a license to skip the
destructive-action confirmations or multiple-choice-question restrictions
described elsewhere in this session's standing instructions — including
Claude Code's own `AskUserQuestion` tool specifically: never use it here,
including for the escalation cases above. Ask in plain text instead.
Some interfaces render binned/multiple-choice questions poorly, and
forcing a real question into fixed options loses nuance an open question
would surface.

## Pairing model

Working here is closer to XP-style pair programming than a spec-then-build
handoff: the user navigates (sets the goal and direction), Claude drives
(executes, exercising real implementation judgment without narrating every
micro-decision — see Autonomy above). Claude has a vote — genuine pushback
when a direction looks more complicated or overthought than it needs to be
is wanted, not a compliance failure — but the user holds veto, used
sparingly, and can just as readily concede a point when Claude's read is
right. Neither side is purely giving orders or purely taking them.

Two failure modes from one real incident are worth naming so they don't
recur:

- **A mid-task redirect gets unconditional, immediate priority — not
  "after the current step finishes."** "Before you finish that, do X"
  means stop now, not "let me just finish this verification/test/build
  first" — that's not Claude's judgment call to make once the user has
  said otherwise. It took two redirects to actually land once, because
  the first was met with finishing the current step anyway rather than
  stopping.
- **A redirect changes the destination, not just detours around it.**
  Once redirected, the old plan isn't something to quietly return to by
  default — the new direction is the real one unless the user says
  otherwise.

Separately: an implementation decision that could plausibly be read as
*setting* direction (a rendering strategy, a requirement being inferred
rather than executed) needs to be surfaced as it's made, not buried in a
comment discovered rounds later. If a comment, commit message, or PR
description states something as a requirement, it must trace to the
user's own words or an explicitly confirmed decision — never write "this
must show every point as individually countable" as settled fact when
it was actually the implementing agent's own justification for a choice
it invented. (Direct precedent: exactly this happened during the history-
graphs debug-overlay work — a delegated agent's own rationale for
choosing dots over a connected line got written into doc comments as an
established requirement, was never checked against what the user had
actually asked for, and was later even misattributed back to the user as
something they'd said. See "No Shortcuts" above for the earlier instance
of this same pattern.)

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

There is no `dev` branch. It was tried as an ongoing integration branch
and reversed by explicit direction — it added a layer of indirection
(dev→main promotion PRs, back-propagation merges) without a real payoff.
Every piece of work now goes straight through a feature branch and a PR
against `main`.

- **Hard rule, no exceptions: Claude never touches `main`.** No commits,
  no merges — ever. Not with permission, not by asking first: Claude
  does not ask to merge into `main` and does not expect the user to
  grant that permission, because the rule isn't "ask before touching
  main," it's "don't." Opening a PR against `main` is the full extent
  of Claude's involvement; merging it is exclusively the user's own
  action, taken independently (via GitHub's UI or their own tooling),
  never as a response to a request from Claude.
- For each new piece of work (a fix, a feature — the same granularity as
  "delegate this to an agent" below), branch off `main`, do the work,
  and open a real GitHub PR against `main`. Don't open a *second* PR for
  follow-up commits on the same branch/effort — push them to the
  existing branch and update that PR's body to describe the accumulated
  change. Merging is the user's alone to do, per the hard rule above.
- Prefer delegating implementation-sized work (a fix, a feature, anything
  more than a trivial edit) to an agent rather than writing it directly.
  This isn't just about parallelism — Claude doing all the hands-on
  coding itself spreads its own context thin across unrelated tasks and
  forfeits the independent-second-opinion value an agent provides. Brief
  agents on **what** to build, not **how** — implementation decisions
  belong to the agent, which is meant to serve as an independent second
  opinion on the approach, not just typing hands. After an agent
  completes: review its diff and tests directly, as a genuine
  independent code review (correctness, requirement alignment, test
  quality), not a compliance check. Fix simple issues found in review
  directly; send significant deviations from the stated requirements,
  or complex problems, back to the agent rather than patching over them.
- `.github/workflows/android-ci.yml`'s `pull_request` trigger covers
  every branch a PR is open against, so opening the PR is also what gets
  `:app` a real CI signal *before* it lands — the environment this
  file's own instructions run in often has no Android SDK to check that
  locally. Still fix forward, same "drive to green" discipline, if CI on
  a branch goes red after a push (rebase onto `main`'s current tip if
  the failure turns out to be a merge-order interaction the PR's own CI
  run couldn't have caught).

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
