# Device Testing Protocol — Pixel 6 Pro

## Purpose and scope

This is a manual, human-executed test script for validating `UplinkStatus`
on real hardware. Every step here covers behavior that unit tests and
Robolectric structurally cannot settle: real battery/Doze management, real
status-bar rendering, real notification timing, real DNS resolution over
a real radio, and real foreground-service lifecycle calls on a real
Android runtime. If a behavior *can* be verified on the JVM or under
Robolectric, it belongs in the `:core`/`:app` test suites (Stages 0-5),
not here — nothing in this document should be read as a suggestion to
add automation; it is written to be run by a person holding a phone.

**Target device:** Google Pixel 6 Pro, Android 14+ (matching
`minSdk 34`). This is the only hardware available to this project — see
[Out of scope](#out-of-scope-oem-battery-management-variance) below.

**Build under test:** `com.uplinkstatus.app`, built from `dev` via
`./gradlew installDebug` (or a signed release build installed with
`adb install`). Record the commit hash / `versionName` (`0.1.0` at time
of writing) in the test run's notes.

**Before starting:** uninstall any prior installation of the app
(`adb uninstall com.uplinkstatus.app`) so Section A genuinely exercises a
first run — a leftover install skips the permission prompts this
protocol depends on.

Each step below has a concrete pass condition. If the actual result
doesn't match, stop, record exactly what happened (screenshot +
`adb logcat` excerpt where relevant), and file it rather than continuing
past a failure.

---

## Section A — Install and first run

**A1. Fresh install.**
Install the build on a device with no prior `UplinkStatus` data
(confirm via `adb shell pm list packages | grep uplinkstatus` returning
nothing beforehand). Launch the app from the launcher.
*Expected:* app opens directly to the notification-permission rationale
screen (not the settings screen) — the string "UplinkStatus shows its
connectivity status as a persistent status-bar icon. That requires
permission to post a notification — allow it so the icon can be shown."
is visible, with a single "Allow notifications" button and no denial
message yet.

**A2. No icon before permission is granted.**
Before tapping "Allow notifications," pull down the status bar / check
the icon tray.
*Expected:* no `UplinkStatus` icon is present anywhere (status bar or
notification shade) — the service has not started yet.

**A3. Grant notification permission.**
Tap "Allow notifications" and grant the permission in the system dialog.
*Expected:* the app immediately transitions to the real settings screen
("Uplink status settings" title, master toggle, hide-when-disabled,
network scope, ping target host all visible). Within a few seconds, the
status-bar icon appears (5-bar silhouette, one bar lit) — the foreground
service has started and the tracer is running.

**A4. Persistent notification present.**
Pull down the notification shade.
*Expected:* an ongoing (non-dismissable — no swipe-to-dismiss) "Uplink
status" notification is present with content text of the form "Uplink:
connected, `N`ms" (or "Uplink: connected" if latency isn't available
yet).

---

## Section B — Permission flows

### B1. Notification permission — deny path

1. Uninstall and reinstall the app (clean state).
2. Launch it, and on the rationale screen tap "Allow notifications," but
   **deny** in the system dialog.
   *Expected:* the app stays on the permission screen; a denial message
   appears — "Notifications permission was denied, so the status icon
   can't be shown. You can grant it from system settings at any time." —
   and the "Allow notifications" button remains, re-tappable.
3. Confirm no status-bar icon and no foreground service notification
   appear at all while permission is denied.
4. From system Settings → Apps → UplinkStatus → Notifications, manually
   enable the permission, then return to the app (no need to relaunch).
   *Expected:* app settings screen becomes reachable and the service
   starts, matching A3's result — the app recovers from an initial
   denial without needing a reinstall.

### B2. Location permission — gated behind SSID whitelist selection only

1. With notification permission already granted, open the settings
   screen and confirm the "Network scope" section is on **Wi-Fi only**
   (the default) with no location-permission prompt shown.
2. Check Settings → Apps → UplinkStatus → Permissions.
   *Expected:* Location is **not** listed as granted yet — selecting any
   scope other than SSID whitelist never triggers the location request,
   confirming the "request only at point of use" behavior in the spec.
3. In the app, select "Specific Wi-Fi networks (SSID whitelist)".
   *Expected:* the system location-permission dialog appears immediately
   (requesting both precise/fine and approximate/coarse together, since
   Android bundles them in one dialog when an app asks for FINE).
4. Grant the permission ("While using the app" or "Only this time" —
   either satisfies `ACCESS_FINE_LOCATION` for the current session).
   *Expected:* the dialog dismisses, the SSID-whitelist editor
   (an "Add" field + list of whitelisted networks) becomes visible under
   the scope selector, and no crash or stuck state occurs.

### B3. Location permission — deny path

1. Uninstall and reinstall (clean permission state).
2. Grant notification permission (Section A), then go to network scope
   and select "Specific Wi-Fi networks (SSID whitelist)".
3. **Deny** the location permission dialog.
   *Expected:* the SSID-whitelist scope option is still selected in the
   UI (the radio button reflects the choice), and the whitelist editor
   still renders (add/remove UI is visible), but no SSID can actually be
   matched against the live network without the permission the app
   doesn't have.
4. Since no SSIDs are configured yet, confirm the icon's actual state.
   *Expected:* with an empty whitelist and SSID-whitelist scope
   selected, the current network never matches (nothing is whitelisted),
   so visibility falls to whichever of `HIDDEN`/`DISABLED` the
   "hide when disabled" toggle currently selects — not `ENABLED`. This
   is the expected consequence of denying location, not a bug: without
   permission the app can't read the SSID at all, so no network can ever
   be "in scope" under this scope mode.
5. From system Settings, grant location access after the fact, then
   return to the app and add the current network's SSID to the
   whitelist (see B4).
   *Expected:* the app functions normally afterward — no residual bad
   state from the earlier denial.

### B4. SSID whitelist end-to-end

1. With location permission granted (B2 or B3-step-5) and SSID-whitelist
   scope selected, note the currently connected Wi-Fi network's SSID
   (Settings → Network & internet → Internet → the connected network's
   name).
2. In the app, type that exact SSID into the "SSID" field and tap "Add".
   *Expected:* the SSID appears in the "Whitelisted networks" list with
   a "Remove" button, and the status-bar icon becomes `ENABLED`
   (tracer cycling) within a few seconds.
3. Disconnect from Wi-Fi (toggle Wi-Fi off, or move out of range).
   *Expected:* visibility changes to `DISABLED` or `HIDDEN` per the
   "hide when disabled" toggle (network no longer in the whitelist scope
   — no Wi-Fi at all means no whitelisted SSID is active).
4. Reconnect to the whitelisted Wi-Fi network.
   *Expected:* visibility returns to `ENABLED` without needing to reopen
   the app or restart the service.

---

## Section C — State transitions and priority rules

Use the settings screen's master toggle, hide-when-disabled toggle, and
network scope controls for all of these. Between each step, wait at
least 5 seconds before checking the icon/notification, since the
preferences→visibility pipeline is reactive but not instantaneous.

**C1. Enabled → Disabled (out-of-scope, hide-when-disabled OFF).**
Master toggle ON, hide-when-disabled OFF, scope "Wi-Fi only," connected
to Wi-Fi (`ENABLED`, tracer cycling). Turn off Wi-Fi (or switch to
cellular-only connectivity).
*Expected:* icon changes to `ic_scan_disabled` (all 5 bars dim, no
lit bar), notification text updates to "Uplink: paused (network out of
scope)", and the icon **stops advancing** — it does not keep cycling.

**C2. Disabled → Hidden (toggling hide-when-disabled while still out of scope).**
Continuing from C1 (still out of scope, `DISABLED`), turn ON
hide-when-disabled without changing anything else.
*Expected:* the icon disappears from the status bar entirely and the
notification is removed — not merely dimmed. Confirm via the
notification shade that no ongoing UplinkStatus notification remains.

**C3. Hidden → Disabled (reverse of C2).**
Continuing from C2, turn hide-when-disabled back OFF (still out of
scope).
*Expected:* the dim, all-bars icon and "paused" notification reappear
(back to the C1 state) — toggling hide-when-disabled doesn't require
reconnecting to a network to take effect.

**C4. Disabled/Hidden → Enabled (back in scope).**
Continuing from C3, reconnect to a Wi-Fi network in scope.
*Expected:* icon resumes cycling from bar 1, notification text returns
to "Uplink: connected, `N`ms".

**C5. Master toggle always wins — the priority test.**
This is the specific rule from the spec's state diagram: the master
toggle is checked *before* network scope, unconditionally. With the
device connected to Wi-Fi and scope set to "Wi-Fi only" (currently
`ENABLED`), turn the **master toggle OFF**.
*Expected:* icon and notification disappear immediately — `HIDDEN` —
regardless of the fact that the network is in scope. This must happen
even though nothing about the network or the hide-when-disabled setting
changed; only the master toggle did.

**C6. Master toggle off overrides hide-when-disabled too.**
Continuing from C5 (master OFF, still `HIDDEN`), turn hide-when-disabled
ON, then OFF, several times while master stays OFF.
*Expected:* the icon stays hidden throughout — hide-when-disabled has no
observable effect while the master toggle is off. This proves the
priority order structurally (master short-circuits everything else),
not just "OFF happens to equal hidden by coincidence of current
settings."

**C7. Master toggle off, then back on, restores the pre-toggle state.**
Continuing from C6, turn the master toggle back ON (leave scope and
hide-when-disabled at whatever C6 left them).
*Expected:* the app re-evaluates network scope from scratch and lands on
`ENABLED` or `DISABLED` per the actual current network + hide-when-
disabled combination — i.e., turning master back on doesn't "remember"
a stale visibility, it re-derives it live.

**C8. Network scope change while enabled.**
With the master toggle ON and currently `ENABLED` on Wi-Fi, switch scope
from "Wi-Fi only" to "Cellular only" without touching the master toggle.
*Expected:* since the device is now out of the new scope (connected via
Wi-Fi, not cellular), the icon transitions to `DISABLED`/`HIDDEN`
immediately, without needing to reconnect to any network — confirms the
settings-change-reaches-a-running-service behavior, not just
connectivity-driven transitions.

---

## Section D — Doze and screen-off behavior

**D1. Screen-off, short duration.**
With the app `ENABLED` and cycling, turn the screen off for 2 minutes
(power button), then turn it back on.
*Expected:* on waking, the icon is present and either mid-cycle or has
advanced during the screen-off period — the foreground service is not
supposed to be killed by a brief screen-off, since foreground services
are exempt from most Doze restrictions.

**D2. Screen-off, extended duration (Doze-eligible).**
With the app `ENABLED`, turn the screen off, leave the device stationary
and unplugged for at least 30 minutes (long enough for the device to
plausibly enter deep Doze — stationary + unplugged are Doze's own entry
conditions). Do not touch the device during this window.
*Expected on waking:*
- The foreground service notification is still present (foreground
  services aren't torn down by Doze).
- The icon reflects a real state, not a frozen stale one from before
  the screen turned off — i.e., check the notification's latency value
  is plausible (not obviously ancient) or the icon has continued
  cycling, confirming probes kept running through Doze rather than
  silently stalling.
- No crash, no "app not responding" dialog, and `adb logcat` (captured
  right after waking) shows no ANR trace for `com.uplinkstatus.app`.

**D3. Screen-off does not need to keep the icon "live" for the user, but must not misbehave.**
The spec's own reasoning ("no wake lock — it's acceptable for Doze to
throttle timers while the screen is off, since the icon only matters
when the user can see it") means slower cycling while the screen is off
is expected and correct, not a bug. What this step actually checks: on
waking from D2, confirm the app did **not** silently stop the foreground
service, did **not** drop into `HIDDEN`/`DISABLED` on its own (network
and settings unchanged), and did **not** need to be manually relaunched
to resume — the throttling is about probe cadence, not the service being
killed.

**D4. Doze whitelist not required.**
Confirm the app has **not** been added to the Doze/battery-optimization
allowlist (Settings → Apps → UplinkStatus → Battery → should read
"Optimized" / default, not "Unrestricted") before running D1-D3. The
point of these steps is to prove the app survives *default* Doze
behavior via the foreground-service exemption alone, not that it works
once given special dispensation.

---

## Section E — Extended run: notification timing and icon rendering

**E1. Tracer keeps advancing over an extended run.**
With the app `ENABLED` on a healthy Wi-Fi network, watch the status-bar
icon (or record it) for **15 continuous minutes** with the screen on.
*Expected:* the lit bar visibly moves through positions 1-5 repeatedly
throughout the entire window — no point where it appears to freeze for
more than a few seconds (a healthy network's probes should keep
succeeding at roughly the 1000ms-probe + 500ms + 500ms cadence described
in the spec). Spot-check the notification's latency text at least 5
times during the window and confirm it updates to different plausible
values (not stuck on one number the whole time).

**E2. Dim-vs-lit alpha reads correctly on real hardware (spec item flagged for
Stage 7).**
The spec commits to achieving the duotone look via alpha only — dim bars
at `0.3`, the lit bar at `1.0` — specifically because it says a real
device's status-bar icon slot flattens color and only respects alpha.
This must be checked on the actual device, not just a rendered
Android Studio preview:
1. With the app `ENABLED`, look directly at the physical status bar
   (not a screenshot, not the notification shade) in normal indoor
   lighting.
   *Expected:* the lit bar is clearly, visibly brighter/more opaque than
   the other 4 bars — a person glancing at the status bar without
   knowing which bar "should" be lit can correctly identify it.
2. Repeat the same glance outdoors or in bright light if convenient.
   *Expected:* the contrast between lit and dim bars still holds up —
   this is the scenario most likely to wash out a small alpha
   difference.
3. Trigger `DISABLED` (Section C1) and look at the icon.
   *Expected:* all 5 bars appear uniformly dim (`0.3` alpha) — no bar
   reads as "more lit" than any other; the eye should register this as
   visually distinct from any `ENABLED` frame.
4. Record a pass/fail verdict explicitly — this was flagged in
   `STATUS.md` as worth a real-device sanity check precisely because a
   rendered preview cannot prove how the OS-applied tint + alpha
   actually looks once composited into the live status bar.

**E3. No distinct "lost ping" frame during a real outage.**
While `ENABLED` and cycling normally, disable Wi-Fi and cellular data
simultaneously (Airplane Mode is the simplest way, but note that also
changes network scope — instead, if possible, leave Wi-Fi associated to
an access point that itself has no internet uplink, or turn off the
Wi-Fi router's WAN connection, so the device stays "connected" to a
network that's actually dead). Watch the icon.
*Expected:* the tracer freezes on whichever bar was lit at the moment of
failure — it does not jump to a distinct "disconnected" icon, and it
does not go blank. The notification text changes to "Uplink: connection
trouble, retrying…" (confirms Stage 5's freeze-reason text reaches the
real notification, not just its unit tests).

---

## Section F — Settings persistence

**F1. Persistence across app restart (not reboot).**
Set several non-default preferences: turn hide-when-disabled ON, set
scope to "Cellular only," and set ping target host to the "Google
(`dns.google`)" alternate. Force-stop the app (Settings → Apps →
UplinkStatus → Force stop), then relaunch it from the launcher.
*Expected:* the settings screen shows all three non-default values
still selected — force-stopping and relaunching does not reset
DataStore-backed preferences.

**F2. Persistence across a full device reboot.**
With the same non-default preferences from F1 still in place, reboot
the device fully (`adb reboot` or the power-menu Restart — a real
reboot, not just closing the app). After the device finishes booting,
open the app.
*Expected:* the same three preferences are still in place. This is the
step that actually proves DataStore is durably persisted to disk rather
than merely surviving process death by coincidence of Android not
having killed the process yet — a full reboot guarantees a completely
fresh process with no possibility of in-memory carryover.

**F3. Bar position is correctly NOT persisted (contrast case).**
With the app `ENABLED` and the tracer at, say, bar 3 or 4, force-stop the
app, then relaunch it (or reboot, either demonstrates this).
*Expected:* the tracer resumes at bar 1, not wherever it was — per spec,
bar position is explicitly process-lifetime-only and must reset on
restart. If it resumes anywhere other than bar 1, that's a persistence
bug (the opposite direction from F1/F2 — this preference must *not*
survive).

---

## Section G — Named real-device-only risks (from Stage 0-5 review notes)

These four items were explicitly flagged during earlier stages' reviews
as things Robolectric/unit tests cannot settle and that specifically
needed a real-device pass here. Each gets its own concrete step (not
folded into a generic scenario above), though some reuse setup from
earlier sections.

**G1. Dim-bar alpha (`0.3`) real-device sanity check.**
This is Section E2 above, called out again here for traceability to the
spec's note ("still worth a real-device sanity check... tracked for
Stage 7's device-testing protocol") and `STATUS.md`'s Stage 2 entry. Run
E2 and record its explicit pass/fail verdict as this item's result.

**G2. No-back-off immediate-retry loop against a host that actively
refuses connections — battery/CPU check.**
Flagged in `STATUS.md`'s Stage 1 note: the retry loop has "no artificial
floor between synchronous connect attempts" if a target actively refuses
the TCP connection (fast RST) rather than timing out — a real
battery/CPU consideration on real hardware, not just theoretical.
1. Set the ping target host to a custom host that is reachable on the
   network but has **nothing listening on port 443** — e.g., another
   device on the same LAN by its IP address (a phone hotspot's gateway,
   a printer, a smart-home device — anything that will send a TCP RST
   immediately rather than silently dropping the packet). Confirm with
   `adb shell` or a laptop's `nc -zv <host> 443` that the connection is
   refused quickly (near-instant "Connection refused"), not that it
   times out.
2. Set this as the custom ping target host in the settings screen and
   confirm the app is `ENABLED`.
3. Let it run for 5 minutes. During this window, check
   Settings → Apps → UplinkStatus → Battery ("App battery usage since
   last full charge" or the equivalent live stats), and/or run
   `adb shell top -n 1 | grep uplinkstatus` a few times to sample CPU%.
   *Expected:* the retry loop does spin faster than the normal
   1000ms-probe cadence (since a refused connection returns near-
   instantly instead of waiting out the 1000ms timeout), so higher CPU
   usage than a healthy-network run is expected and not itself a
   failure. What to actually check: the device does not become
   noticeably hot to the touch, the battery stats do not show a runaway
   spike disproportionate to 5 minutes of foreground-service activity,
   and the app does not ANR or get killed by the system for excessive
   background CPU. Record the observed CPU% range and battery-stats
   entry as this item's result — this step is explicitly about
   characterizing real-world severity, not pass/fail against a
   threshold the spec doesn't define (the spec intentionally chose "no
   back-off" and isn't asking this test to overturn that call, only to
   confirm it isn't dangerous in practice on the one device available).
4. Revert the ping target host to the default before continuing to
   other sections.

**G3. Foreground-service lifecycle calls off the main thread — stability
check.**
Flagged in `STATUS.md`'s Stage 3 note: `applyVisibility()` (which calls
`startForeground`/`stopForeground`/`stopSelf`) runs from a
`Dispatchers.Default` coroutine rather than the main thread. Expected to
be fine since these APIs proxy over Binder, but worth a specific
real-device eye rather than trusting Robolectric alone.
1. Trigger a rapid sequence of visibility transitions on the real
   device: toggle the master switch off/on/off/on quickly (a few times
   within a couple of seconds), then let it settle on `ENABLED`.
   *Expected:* no crash, no `ForegroundServiceStartNotAllowedException`
   or similar exception in `adb logcat`, and the app ends up in a
   consistent final state matching the final toggle position — no stuck
   "half-transitioned" state (e.g., icon showing while the notification
   shade shows nothing, or vice versa).
2. Repeat the same rapid-toggle sequence, but this time also flip
   airplane mode on/off in the middle of it, so a connectivity-driven
   transition and a preference-driven transition can race each other
   for the same `applyVisibility()` call.
   *Expected:* still no crash and no stuck state; check
   `adb logcat -s ActivityManager:* AndroidRuntime:*` for any warning
   about a service call from a non-main thread being rejected or
   delayed (there shouldn't be one, since these calls are documented as
   Binder-proxied rather than main-thread-only, but this is exactly what
   this step exists to confirm on real Android rather than assume).
3. Record explicitly whether any anomaly appeared — this is a
   confirm-or-deny check on a specific, named risk, not a generic
   "seems fine" pass.

**G4. Hostname-based probe resolution on real IPv4/IPv6/dual-stack
networks.**
Flagged as a real-device-only concern: the probe resolves a hostname
(`one.one.one.one` default) rather than a bare IP literal specifically so
the OS can pick whichever address family the actual network supports —
this can only be verified against real network stacks.
1. On the home/office Wi-Fi network (confirm via a laptop or
   `adb shell ip route` on the device whether the network is
   dual-stack — i.e., has both IPv4 and globally-routable IPv6, which
   most modern home routers provide), confirm the app is `ENABLED` and
   cycling normally with the default host. Note whether `adb shell` (if
   accessible) shows the resolved address as IPv4 or IPv6 for a manual
   `getent hosts one.one.one.one`-equivalent check, or just confirm
   functionally that probes are succeeding.
2. Switch to cellular data only (Wi-Fi off, scope set to "Cellular
   only" or "Any connection"). Many US carriers (T-Mobile in particular)
   run IPv6-only/NAT64 cellular networks with no real IPv4 path.
   *Expected:* the tracer continues advancing normally on cellular —
   confirms hostname resolution correctly picks up whatever address
   family the cellular network actually offers, rather than silently
   failing the way a hardcoded IPv4 literal would on an IPv6-only
   network.
3. Set the custom ping target host to something that is a syntactically
   plausible hostname but does not actually resolve (e.g.
   `this-host-does-not-exist.invalid`).
   *Expected:* the notification text shows "Uplink: can't resolve target
   host, retrying…" (the distinct DNS-failure text, not the generic
   "connection trouble" text) — confirms the spec's requirement that an
   unresolvable custom host is treated as a distinct condition from a
   generic probe failure, verified against real DNS resolution rather
   than a test double.
4. Revert to the default ping target host before continuing.

---

## Out of scope: OEM battery-management variance

This protocol runs exclusively on a Google Pixel 6 Pro because that is
the only real hardware available to this project (see the spec's
Implementation Baseline section). Non-Pixel OEM skins — Samsung's
battery/App power management, Xiaomi's MIUI battery saver, and similar
aggressive background-kill behavior on other manufacturers' Android
builds — are a known, real risk category for any always-on foreground
service, and this protocol explicitly does **not** cover them. This is a
scope limit driven by hardware availability, not an oversight or a claim
that Pixel behavior generalizes to other OEMs. Any future device
acquisition (a Samsung or Xiaomi test unit, for example) should prompt a
follow-up protocol addressing that device's specific battery-management
UI and kill behavior, separate from this document.

---

## Result recording

For each numbered step (A1, A2, ... G4), record a pass/fail and, for any
fail, attach a screenshot and the relevant `adb logcat` excerpt. Report
the completed run (device, Android version/build number, app commit
hash, date, and pass/fail table) alongside this document rather than
inside it — this file is the reusable protocol, not a specific run's
results log.
