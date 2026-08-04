# UplinkStatus [![starline](https://raw.githubusercontent.com/ScottKirvan/UplinkStatus/refs/heads/starlines/ScottKirvan/UplinkStatus/starline.svg)](https://github.com/qoomon/starlines)
<div align="center">

  <img src="assets/media/logo.jpg" alt="logo" width="200" height="auto" />
  <h1><a href="https://github.com/ScottKirvan/UplinkStatus">UplinkStatus</a></h1>
  <h3>A status-bar icon for your uplink health</h3>

<!-- Badges -->
<p>
  <a href="https://github.com/ScottKirvan/UplinkStatus/graphs/contributors">
    <img src="https://img.shields.io/github/contributors/ScottKirvan/UplinkStatus" alt="contributors" />
  </a>
  <a href="https://github.com/ScottKirvan/UplinkStatus/commits/main">
    <img src="https://img.shields.io/github/last-commit/ScottKirvan/UplinkStatus" alt="last update" />
  </a>
  <a href="https://github.com/ScottKirvan/UplinkStatus/network/members">
    <img src="https://img.shields.io/github/forks/ScottKirvan/UplinkStatus" alt="forks" />
  </a>
  <a href="https://github.com/ScottKirvan/UplinkStatus/stargazers">
    <img src="https://img.shields.io/github/stars/ScottKirvan/UplinkStatus" alt="stars" />
  </a>
  <a href="https://github.com/ScottKirvan/UplinkStatus/issues/">
    <img src="https://img.shields.io/github/issues/ScottKirvan/UplinkStatus" alt="open issues" />
  </a>
  <a href="https://github.com/ScottKirvan/UplinkStatus/blob/main/LICENSE.md">
    <img src="https://img.shields.io/github/license/ScottKirvan/UplinkStatus.svg" alt="license" />
  </a>
  <a href="https://discord.gg/TN6XJSNK5Y">
    <img src="https://img.shields.io/badge/discord-join-5865F2?logo=discord&logoColor=white" alt="discord" />
  </a>
</p>

<h4>
    <a href="https://ScottKirvan.github.io/UplinkStatus/">Docs</a>
  <span> · </span>
    <a href="https://github.com/ScottKirvan/UplinkStatus/issues/new?template=bug_report.md">Report Bug</a>
  <span> · </span>
    <a href="https://github.com/ScottKirvan/UplinkStatus/issues/new?template=feature_request.md">Request Feature</a>
  </h4>
</div>

**UplinkStatus** is a persistent Android status-bar icon that shows, at a
glance, whether your device currently has a working internet connection.
A small 5-bar icon sits alongside your signal and battery icons, actively
cycling while your connection checks out — and freezing in place the
moment a check fails.

Table of Contents
-----------------
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Development](#development)
- [Branches](#branches)
- [Repo Layout](#repo-layout)
- [Contributions / Contact](#contributions--contact)
- [Credits](#credits)

Features
--------
- **A glanceable connectivity indicator** — a small 5-bar icon sits alongside your signal and battery icons, actively cycling while your connection checks out, and freezing in place the moment a check fails.
- **Scoped to how you actually use your phone** — choose Wi-Fi only, any connection, cellular only, or a whitelist of specific Wi-Fi networks.
- **Built for always-on background use** — runs as a low-priority foreground service designed to stay up indefinitely, not to be opened and closed like a typical app.

Installation
------------
Requires **Android 14 or newer** and the notification permission granted
on first launch — the status-bar icon is delivered as part of an ongoing
notification, so Android won't show it without that permission.

- **Prebuilt APK:** download the latest from [Releases](https://github.com/ScottKirvan/UplinkStatus/releases) (unsigned — you'll need to allow installs from unknown sources).
- **Build from source:** see [Development](#development) below.

Usage
-----
On first launch, grant the notification permission and the status-bar
icon appears with sensible defaults (Wi-Fi only, checking against
`one.one.one.one`). Everything else — network scope, ping target, hiding
the icon when out of scope — is configurable from the app's settings
screen, reachable by reopening the app.

See the [full guide](https://ScottKirvan.github.io/UplinkStatus/guide/install)
for what the icon's animation states mean, all available settings, and
troubleshooting.

Development
-----------
- `app/` — the Android app (Kotlin, Jetpack Compose, `minSdk` 34 / `targetSdk` 36): a `specialUse` foreground service drives a 6-frame status-bar tracer icon from TCP connect-probe results, with a Compose settings screen backed by Jetpack DataStore.
- `core/` — the pure-Kotlin probe/tracer/visibility state machine `app/` builds on.

See [`notes/dev/uplink-status-indicator-spec.md`](notes/dev/uplink-status-indicator-spec.md) for the design spec this is built to.

```bash
./gradlew build   # compiles, assembles debug + release APKs, runs unit tests, lints
./gradlew test    # unit tests only
```

Requires a local Android SDK (`compileSdk`/`targetSdk` 36, `minSdk` 34) —
install it via Android Studio or the standalone command-line tools, then
either set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or add a `local.properties`
file at the repo root with `sdk.dir=/path/to/sdk` (gitignored — it's
local machine config, not project config). No device or emulator is
required — the Compose UI tests run on the JVM via Robolectric.
`.github/workflows/android-ci.yml` runs the same build on every push/PR
that touches the Android project.

Branches
--------
- `main` — the stable branch; the [docs site](https://ScottKirvan.github.io/UplinkStatus/) deploys from here.
- `dev` — ongoing development.

Repo Layout
-----------
```
UplinkStatus
├── app/                  # The Android app (Kotlin, Jetpack Compose)
├── core/                 # Pure-Kotlin probe/tracer/visibility logic, no Android deps
├── docs/                 # VitePress docs site, deployed to GitHub Pages
├── notes/                # Design spec, CHANGELOG, VERSION, TODO
├── .github/
│   ├── ISSUE_TEMPLATE/   # Bug report and feature request templates
│   ├── release-please/   # Release-Please configuration
│   ├── workflows/        # GitHub Actions (CI, release, docs)
│   └── PULL_REQUEST_TEMPLATE.md
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── LICENSE.md
└── README.md             # This file
```

Contributions / Contact
-----------------------
- Please [file an issue](https://github.com/ScottKirvan/UplinkStatus/issues/new), or [grab a fork](https://github.com/ScottKirvan/UplinkStatus/fork), hack away, and submit a [pull request](https://github.com/ScottKirvan/UplinkStatus/pulls). See [CONTRIBUTING.md](CONTRIBUTING.md).
- Contact me at [linkedin.com/in/scottkirvan/](https://www.linkedin.com/in/scottkirvan/)
- You can also find me on [Discord](https://discord.gg/TN6XJSNK5Y), I'm cptvideo.

Credits
-------
**UplinkStatus** © 2026 [Scott Kirvan](https://github.com/ScottKirvan), licensed under the [MIT License](LICENSE.md).
Bootstrapped from [ScooterGitTemplate](https://github.com/ScottKirvan/ScooterGitTemplate).

Project Link: [UplinkStatus](https://github.com/ScottKirvan/UplinkStatus)
[Docs](https://ScottKirvan.github.io/UplinkStatus/) ·
[CHANGELOG](notes/CHANGELOG.md) ·
[TODO](notes/TODO.md)
