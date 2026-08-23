# Install and setup

## What Ping'd does

Ping'd adds a small icon to your phone's status bar — the same
strip along the top of the screen where you see signal strength,
Wi-Fi, and battery icons — that shows whether your device currently
has a working internet connection.

Instead of a single static "connected / not connected" symbol, the
icon actively animates while it's checking, so a glance tells you two
things at once: that the app is still checking (the icon is moving),
and whether the last check succeeded (it keeps moving) or failed (it
stops and holds still). See [The status-bar icon](/guide/status-icon)
for exactly what each look means.

The app runs in the background as an ongoing, low-priority
notification — it's designed to sit there indefinitely rather than to
be opened and closed like a typical app. You'll only need to open it
again to change a setting.

## Requirements

- **Android 14 or newer.** Ping'd doesn't support older Android
  versions.
- **A notification permission grant.** The status-bar icon is
  technically part of an ongoing notification, so Android requires you
  to allow notifications before it can be shown at all — this isn't
  optional bookkeeping, without it there is no icon.

No other setup is required to get the icon showing with its default
settings.

## First launch

1. Open the app. You'll see a short explanation of why it needs
   notification permission, with an **Allow notifications** button.
2. Tap it and grant the permission when Android's system prompt
   appears.
   - If you deny it, the app tells you the icon can't be shown without
     it, and that you can turn it on later from the phone's system
     notification settings for Ping'd — you're not stuck if you
     change your mind.
3. Once granted, the status-bar icon appears and the settings screen
   opens, where you can review or change how Ping'd behaves
   (see [The settings screen](/guide/settings)).

## Defaults out of the box

A fresh install starts with:

- The feature turned **on** (master toggle enabled).
- Network scope set to **Wi-Fi only** — the icon is only active while
  you're connected to Wi-Fi, to avoid using cellular data or battery
  in the background.
- **Hide icon when out of scope** turned **off** — so when you're on
  cellular (out of scope for the Wi-Fi-only default), the icon stays
  visible but dimmed, rather than disappearing.
- Connectivity checks aimed at Cloudflare's public server
  (`one.one.one.one`).

All of these can be changed from the settings screen at any time — see
[The settings screen](/guide/settings) for what each control does and
when you'd want to change it.
