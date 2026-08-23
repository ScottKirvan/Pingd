package com.bojustudio.pingd.app.prefs

/**
 * The four network-scope modes from the spec's "User Preferences" section. [ANY_CONNECTION] is
 * the documented default -- WiFi or cellular, whichever is actually carrying traffic.
 * [SSID_WHITELIST] is the one mode that
 * needs `ACCESS_FINE_LOCATION` to actually read the connected SSID — that permission
 * request happens at the point the user selects this mode (see `SettingsScreen`), not
 * unconditionally.
 *
 * Persisted as [name] in DataStore (see [PingdPreferencesRepository]) so adding a mode
 * later is a non-breaking change; renaming an existing constant would not be, since it
 * would silently fall back to the default on the next read for anyone who had it selected.
 */
enum class NetworkScope {
    WIFI_ONLY,
    ANY_CONNECTION,
    CELLULAR_ONLY,
    SSID_WHITELIST,
}
