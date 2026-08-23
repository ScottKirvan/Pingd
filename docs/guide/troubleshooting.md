# Troubleshooting

## The icon isn't showing

Work through this list in order — it follows the same priority order
the app itself uses to decide whether the icon should be visible, so
whichever step explains what you're seeing is the actual cause; you
don't need to check the ones after it.

1. **Was notification permission actually granted?** Without it,
   Android won't let the app show the icon at all, no matter what any
   other setting says. If you denied it during first launch, open your
   phone's system settings for Ping'd and check its notification
   permission there, then relaunch the app.
2. **Is "Enable Ping'd status icon" turned on?** Open the settings
   screen and check the switch at the top. If it's off, the icon is
   hidden unconditionally — turn it on.
3. **Is your current network within your chosen scope?** Check
   [Network scope](/guide/settings#network-scope) on the settings
   screen against what you're actually connected to right now. For
   example, the default scope is Wi-Fi only — if you're on cellular,
   that counts as out of scope.
4. **If you're out of scope, check "Hide icon when out of scope."** If
   it's turned on, the icon disappearing is expected behavior while
   you're out of scope — it will come back once you're on an in-scope
   network again. If it's turned off, the icon should still be showing
   (dimmed, not moving) rather than gone; if it's genuinely not there
   at all in that case, re-check step 1 first, since a revoked
   notification permission produces the same symptom.
5. **Check your notification shade, not just the status bar itself.**
   Android sometimes has more notification icons than it has room for
   in the status bar and quietly moves the overflow into the
   pull-down shade instead of dropping them. If Ping'd's
   notification is visible there but the small icon isn't in the
   status bar strip itself, that's an Android space limit, not an app
   problem.

## The tracer looks frozen

A frozen tracer — the lit bar stuck in one position instead of moving
— means the most recent connectivity check didn't succeed in time. It
does **not** mean the app has crashed or stopped working; it's actively
retrying in the background, immediately and repeatedly, and will
resume moving as soon as a check succeeds.

What to check:

- **Do you actually have a working internet connection right now?**
  Try loading a page in your browser. If that also fails, the freeze
  is accurately reflecting a real outage.
- **Look at the notification text for more detail.** Swipe down to see
  it. A message like "can't resolve target host" points at a DNS-style
  problem rather than a general outage — most often a misspelled
  [custom ping target host](/guide/settings#ping-target-host), or a
  DNS problem specific to the current network. A message like
  "connection trouble" is the more general case: the target server
  couldn't be reached in time.
- **If your internet clearly works but the tracer stays frozen,** the
  specific target server you've selected may be unreachable or blocked
  on this particular network, even though general internet access is
  fine. Try switching to a different
  [ping target host](/guide/settings#ping-target-host) (for example,
  from Cloudflare to Google, or vice versa) to see if that resolves it.
- **A brief freeze during a network switch is normal** — for instance,
  right as your phone hands off from Wi-Fi to cellular, or reconnects
  after being out of range. Give it a few seconds before treating it as
  a real problem.

## Why is it asking for location permission

This prompt only appears if you select **Specific Wi-Fi networks
(SSID whitelist)** as your [network scope](/guide/settings#network-scope)
— it never appears just from opening the app or using any other scope
option.

The reason is an Android platform restriction, not a choice
Ping'd made: reading the name of the Wi-Fi network your phone is
currently connected to is something Android only allows an app to do
if it holds a location permission. Ping'd needs to read that
network name for exactly one purpose — comparing it against the list
of networks you've whitelisted, so it knows whether to turn the icon
on. It does not track, store, or transmit your device's actual
location, and it doesn't request this permission at all unless you
choose this specific scope option.

If you'd rather not grant it, choose a different network scope (Wi-Fi
only, any connection, or cellular only) instead — none of those modes
need to identify a specific network by name, so none of them require
this permission.
