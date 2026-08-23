# The status-bar icon

The icon is a small silhouette of 5 vertical bars, similar in shape to
a signal-strength indicator. Rather than a smooth animation, it's built
from 6 fixed pictures that get swapped in place — but the effect you
actually see is a single light "traveling" across the 5 bars, one
position at a time, for as long as your connection checks keep
succeeding.

## Why it moves

Ping'd checks your internet connection by briefly trying to open
a connection to a server (Cloudflare's by default — see
[Ping target host](/guide/settings#ping-target-host) for the other
options) and timing how long that takes, then immediately closing it
again without loading anything. It's a lightweight, near-instant check
— nothing is downloaded or displayed, it's purely a "can I reach
this?" test, repeated continuously while the icon is active.

Every time a check succeeds, the lit bar advances one position. There
is also a brief automatic half-step partway through each cycle that
also advances it, so under normal conditions the light keeps sweeping
across the 5 positions in a steady, repeating motion — that motion
itself *is* the "everything's fine, still checking" signal. A slower
response from the server shows up as a slightly longer pause before
the light moves — the speed isn't artificially adjusted, so a longer
pause on a given step reflects real latency to whatever server you've
set as the target.

## The 6 looks, plain-language

| What you see | What it means |
| --- | --- |
| One bar lit, in any of 5 positions, and the lit bar is advancing | Actively checking, and the connection is healthy — this is the normal, expected state. |
| One bar lit, but it's **stopped moving** | The most recent check failed (see [Frozen tracer](#a-frozen-tracer) below). |
| All 5 bars dim, none lit, and staying that way | Checking is **paused** — not because of a failure, but because the feature is currently disabled for your situation (for example: you're on a network outside your chosen scope, and you've left "hide icon when out of scope" turned off). See [Enabled, disabled, or hidden](#enabled-disabled-or-hidden) below. |

That's 5 "lit" positions plus the 1 "all dim, paused" look — 6 pictures
total. There's no 7th icon for "no connection at all" or "hidden" —
when the app decides the icon should be hidden, it simply removes the
icon from the status bar rather than showing a special picture for it.

## A frozen tracer

If a connectivity check doesn't succeed within about a second, the
light doesn't turn off, snap back to the start, or jump to the "all
dim" paused look — it simply **stops on whatever bar it was last on**
and stays there.

This is the normal, designed way Ping'd shows a failed check —
**it is not a crash or a stuck app.** The app is actively retrying in
the background, immediately and repeatedly, with no waiting period
between attempts. As soon as a check succeeds again, the light resumes
moving from right where it froze.

If you want more detail than the icon alone gives you, swipe down to
view the notification itself — its text distinguishes two different
kinds of failure:

- **"Ping'd: connection trouble, retrying…"** — the general case: the
  attempt to reach the target server didn't succeed in time.
- **"Ping'd: can't resolve target host, retrying…"** — specifically,
  the app couldn't even look up the address of the target server
  (a DNS problem). This is called out separately because it usually
  points to a different cause than a plain connectivity failure — for
  example, a custom target host that's misspelled or a DNS server
  that's misbehaving, rather than the network being down outright.

Neither condition changes which bar the icon is showing — the icon
itself only ever freezes in place; the distinction lives in the
notification's text.

## Enabled, disabled, or hidden

Whether the icon appears at all, and whether it's actively cycling or
paused, follows one fixed order of checks:

1. **Is the master toggle on?** If it's off, the icon is hidden,
   full stop — nothing else below matters.
2. If the master toggle is on, **is your current network within your
   chosen scope** (Wi-Fi only, any connection, cellular only, or a
   specific Wi-Fi network from your list — see
   [Network scope](/guide/settings#network-scope))?
   - If yes, the icon is fully active: checks run, the light cycles.
   - If no, whether the icon shows dimmed or disappears entirely
     depends on your **"Hide icon when out of scope"** setting:
     turned off (the default), the icon stays visible with all bars
     dim, as a reminder that monitoring is paused rather than gone;
     turned on, the icon disappears until you're back on an in-scope
     network.

See [Troubleshooting](/guide/troubleshooting#the-icon-isn-t-showing) for
a step-by-step version of this same logic when the icon isn't showing
up the way you expect.

## One more detail: it always starts from bar 1

The lit bar's position isn't saved. If the app restarts — for example
after your phone reboots, or if Android stops and restarts the
background service — the tracer always starts over from the first
position rather than resuming wherever it left off. This has no effect
on whether your connection is healthy; it's purely a cosmetic reset of
the animation's starting point.
