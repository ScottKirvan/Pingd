# Privacy Policy

**App**: Ping'd  
**Developer**: Scott Kirvan  
**Effective date**: 2026-08-23

---

## The short version

Ping'd collects no personal data. There are no accounts, no servers, no analytics, and no tracking of any kind.

---

## What data the app stores

Ping'd stores only the configuration you enter — probe hosts, ports, and display preferences — in local storage on your device. That data never leaves your device.

Nothing is uploaded in the background. There is no cloud backend.

## What the app does on the network

Ping'd makes outbound TCP probe connections to the hosts you configure, solely to measure reachability. These connections go to **your** chosen targets, not to any server operated by the developer. No data from those probes is transmitted to or stored by the developer.

## Analytics, crash reporting, and telemetry

None. Not now, not in a future release. This is a permanent design decision, not a deferred feature.

## Permissions

| Permission | Why it's needed |
|---|---|
| `INTERNET` | Makes TCP probe connections to the hosts you configure |
| `ACCESS_NETWORK_STATE` | Detects network availability changes to pause/resume probing |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the status indicator running while the app is in the background |
| `POST_NOTIFICATIONS` | Shows the persistent status notification in the system tray |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Required by Android 10+ to read the connected Wi-Fi network name (SSID); no location data is stored or transmitted |

Ping'd does not request camera, microphone, contacts, or storage permissions.

## Third parties

Ping'd does not share data with any third party. There are no advertising SDKs, analytics SDKs, or crash-reporting SDKs in the app.

## Children

Ping'd does not knowingly collect information from anyone. It collects no information from anyone, regardless of age.

## Changes to this policy

If anything here ever changes, the updated policy will be posted at this URL with a new effective date. The core commitment — no data collection — is not subject to change.

## Contact

Questions? Open an issue on [GitHub](https://github.com/ScottKirvan/Pingd/issues) or reach out on [Discord](https://discord.gg/TN6XJSNK5Y).
