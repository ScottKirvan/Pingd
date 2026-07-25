# The settings screen

Opening the app (once notification permission has been granted) shows
the settings screen directly — there's no separate "home" screen to
navigate through. Every control on it is described below, in the order
it appears.

## Enable uplink status icon

The master on/off switch for the entire feature. Turning this off
removes the status-bar icon immediately and unconditionally — no other
setting on this screen matters while it's off. Turning it back on
restores the icon (subject to the network-scope check below).

You don't need to uninstall the app to stop seeing the icon; this
switch is the intended way to pause it.

## Hide icon when out of scope

This only has an effect once the master toggle above is on, and only
in the specific situation where your current network falls outside
whatever [network scope](#network-scope) you've chosen below (for
example: scope is set to Wi-Fi only, and you're currently on cellular).

- **Off (the default):** the icon stays visible in that situation, but
  shown with all bars dim and not moving — a visible reminder that
  monitoring is paused rather than the icon just vanishing.
- **On:** the icon disappears entirely while you're out of scope, and
  reappears automatically once you're back on a network that qualifies.

This toggle never affects the master toggle above — turning the master
toggle off always hides the icon regardless of this setting.

## Network scope

Controls which networks UplinkStatus is allowed to actively monitor.
Pick one:

- **Wi-Fi only (default).** The icon is only active while connected to
  any Wi-Fi network. Good for most people — it avoids running
  connectivity checks over cellular, which would otherwise use a small
  amount of mobile data and battery in the background even when you're
  out and about.
- **Any connection (Wi-Fi + cellular).** The icon is active on either
  Wi-Fi or cellular. Choose this if you want a connectivity indicator
  available at all times, and don't mind the small cellular data/battery
  cost of checks continuing while you're off Wi-Fi.
- **Cellular only.** The reverse of the Wi-Fi-only default — useful if
  you specifically care about your mobile connection (for example,
  troubleshooting cellular data issues) and don't need the icon while
  on Wi-Fi.
- **Specific Wi-Fi networks (SSID whitelist).** The icon is only active
  while connected to one of the Wi-Fi networks you've explicitly
  listed by name — not just any Wi-Fi network. Useful if you only care
  about, say, your home and work networks, and don't want the icon
  turning on for every coffee-shop or guest Wi-Fi you happen to join.

### Choosing specific Wi-Fi networks

Selecting this option reveals a small list editor: type a network name
(SSID) and tap **Add** to add it, or tap **Remove** next to an entry to
take it off the list. The icon only becomes active while you're
connected to a network whose name matches one in this list.

The first time you select this option, Android will ask you to grant a
**location permission**. This isn't a mistake and UplinkStatus isn't
tracking your location — see
[Why is it asking for location permission](/guide/troubleshooting#why-is-it-asking-for-location-permission)
for the full explanation. In short: reading the name of the Wi-Fi
network you're currently connected to is treated by Android as a
location-adjacent capability, so there is no way to check "am I on one
of my listed networks?" without that permission. The prompt only
appears when you choose this scope option — not when you first open
the app.

## Ping target host

Choose which server UplinkStatus tries to reach when it checks your
connection:

- **Cloudflare (`one.one.one.one`)** — the default.
- **Google (`dns.google`)** — an alternate quick-pick, in case you'd
  rather point checks at a different provider.
- **Custom** — type any hostname of your own into the field and tap
  **Save**. If what you enter isn't a validly-formed hostname, the
  field shows an error ("Enter a valid hostname (e.g.
  probe.example.com).") and nothing is saved until it's corrected.

This setting only changes what UplinkStatus itself uses internally to
test connectivity — it has no effect on any other app, browser, or DNS
setting on your device.

If you enter a custom host that's spelled correctly but doesn't
actually exist or can't be looked up, you won't see an error on this
screen (a hostname can look perfectly valid and still fail to resolve)
— instead the icon will show a frozen tracer with a distinct
"can't resolve target host" message once it starts checking. See
[The status-bar icon](/guide/status-icon#a-frozen-tracer) and
[Troubleshooting](/guide/troubleshooting#the-tracer-looks-frozen) if
that happens.
