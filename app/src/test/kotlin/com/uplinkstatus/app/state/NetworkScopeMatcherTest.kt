package com.uplinkstatus.app.state

import com.uplinkstatus.app.connectivity.NetworkSnapshot
import com.uplinkstatus.app.prefs.NetworkScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of [NetworkScopeMatcher.isInScope] against simulated network states for
 * each of the spec's four [NetworkScope] modes -- Stage 4's core acceptance criterion. Plain
 * JUnit, no Robolectric: [NetworkScopeMatcher] touches no Android framework type.
 */
class NetworkScopeMatcherTest {

    private val wifiOnly = NetworkSnapshot(
        hasWifiTransport = true,
        hasCellularTransport = false,
        isValidated = true,
        ssid = "HomeNetwork",
    )
    private val cellularOnly = NetworkSnapshot(
        hasWifiTransport = false,
        hasCellularTransport = true,
        isValidated = true,
        ssid = null,
    )
    private val none = NetworkSnapshot.NONE

    // --- WIFI_ONLY ---

    @Test
    fun `WIFI_ONLY is in scope on a WiFi network`() {
        assertTrue(matches(NetworkScope.WIFI_ONLY, snapshot = wifiOnly))
    }

    @Test
    fun `WIFI_ONLY is out of scope on a cellular-only network`() {
        assertFalse(matches(NetworkScope.WIFI_ONLY, snapshot = cellularOnly))
    }

    @Test
    fun `WIFI_ONLY is out of scope with no active network`() {
        assertFalse(matches(NetworkScope.WIFI_ONLY, snapshot = none))
    }

    @Test
    fun `WIFI_ONLY is in scope on WiFi even when the OS hasn't validated internet yet`() {
        // A connected-but-not-yet-validated WiFi network (e.g. a captive portal, or a router
        // with no working WAN) must still count as "in scope" -- the probe cycle's own
        // freeze-on-failure behavior is what signals "connected but broken" to the user;
        // gating scope on validation would mask that behind DISABLED/HIDDEN instead.
        assertTrue(matches(NetworkScope.WIFI_ONLY, snapshot = wifiOnly.copy(isValidated = false)))
    }

    // --- ANY_CONNECTION ---

    @Test
    fun `ANY_CONNECTION is in scope on a validated WiFi network`() {
        assertTrue(matches(NetworkScope.ANY_CONNECTION, snapshot = wifiOnly))
    }

    @Test
    fun `ANY_CONNECTION is in scope on a validated cellular network`() {
        assertTrue(matches(NetworkScope.ANY_CONNECTION, snapshot = cellularOnly))
    }

    @Test
    fun `ANY_CONNECTION is out of scope with no active network`() {
        assertFalse(matches(NetworkScope.ANY_CONNECTION, snapshot = none))
    }

    @Test
    fun `ANY_CONNECTION is out of scope when the active network isn't validated`() {
        assertFalse(matches(NetworkScope.ANY_CONNECTION, snapshot = wifiOnly.copy(isValidated = false)))
    }

    // --- CELLULAR_ONLY ---

    @Test
    fun `CELLULAR_ONLY is in scope on a cellular network`() {
        assertTrue(matches(NetworkScope.CELLULAR_ONLY, snapshot = cellularOnly))
    }

    @Test
    fun `CELLULAR_ONLY is out of scope on a WiFi-only network`() {
        assertFalse(matches(NetworkScope.CELLULAR_ONLY, snapshot = wifiOnly))
    }

    @Test
    fun `CELLULAR_ONLY is out of scope with no active network`() {
        assertFalse(matches(NetworkScope.CELLULAR_ONLY, snapshot = none))
    }

    // --- SSID_WHITELIST ---

    @Test
    fun `SSID_WHITELIST is in scope on WiFi with a whitelisted SSID and permission granted`() {
        assertTrue(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork", "OfficeNetwork"),
                hasLocationPermission = true,
                snapshot = wifiOnly,
            ),
        )
    }

    @Test
    fun `SSID_WHITELIST is out of scope on WiFi with a non-whitelisted SSID`() {
        assertFalse(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("SomeoneElsesNetwork"),
                hasLocationPermission = true,
                snapshot = wifiOnly,
            ),
        )
    }

    @Test
    fun `SSID_WHITELIST is out of scope on cellular even if its name coincidentally matched`() {
        assertFalse(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork"),
                hasLocationPermission = true,
                snapshot = cellularOnly,
            ),
        )
    }

    @Test
    fun `SSID_WHITELIST is out of scope with no active network`() {
        assertFalse(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork"),
                hasLocationPermission = true,
                snapshot = none,
            ),
        )
    }

    @Test
    fun `SSID_WHITELIST without location permission is out of scope, not a crash, even with a matching SSID`() {
        // Per Stage 4's brief: a user can select SSID-whitelist scope and then deny the
        // permission prompt, or revoke it later from system settings. This must degrade to
        // "out of scope," never throw.
        assertFalse(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork"),
                hasLocationPermission = false,
                snapshot = wifiOnly,
            ),
        )
    }

    @Test
    fun `SSID_WHITELIST is out of scope when the OS reports no readable SSID even while on WiFi`() {
        // Mirrors what a real ConnectivityManagerNetworkSnapshotProvider produces without
        // location permission granted (WifiManager#UNKNOWN_SSID mapped to a null ssid) --
        // covered here as a snapshot-level case distinct from the permission-flag case above,
        // since either one alone must be enough to keep this mode out of scope.
        assertFalse(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork"),
                hasLocationPermission = true,
                snapshot = wifiOnly.copy(ssid = null),
            ),
        )
    }

    private fun matches(
        scope: NetworkScope,
        ssidWhitelist: Set<String> = emptySet(),
        hasLocationPermission: Boolean = true,
        snapshot: NetworkSnapshot,
    ): Boolean = NetworkScopeMatcher.isInScope(
        scope = scope,
        ssidWhitelist = ssidWhitelist,
        hasLocationPermission = hasLocationPermission,
        snapshot = snapshot,
    )
}
