package com.bojustudio.pingd.app.state

import com.bojustudio.pingd.app.connectivity.ConnectivitySnapshot
import com.bojustudio.pingd.app.connectivity.NetworkSnapshot
import com.bojustudio.pingd.app.prefs.NetworkScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of [NetworkScopeMatcher.isInScope] against simulated network states for
 * each of the spec's four [NetworkScope] modes -- Stage 4's core acceptance criterion. Plain
 * JUnit, no Robolectric: [NetworkScopeMatcher] touches no Android framework type.
 */
class NetworkScopeMatcherTest {

    private val wifiNetwork = NetworkSnapshot(
        hasWifiTransport = true,
        hasCellularTransport = false,
        isValidated = true,
        ssid = "HomeNetwork",
    )
    private val cellularNetwork = NetworkSnapshot(
        hasWifiTransport = false,
        hasCellularTransport = true,
        isValidated = true,
        ssid = null,
    )

    private val wifiOnly = ConnectivitySnapshot(
        networks = listOf(wifiNetwork),
        defaultNetwork = wifiNetwork,
    )
    private val cellularOnly = ConnectivitySnapshot(
        networks = listOf(cellularNetwork),
        defaultNetwork = cellularNetwork,
    )
    private val none = ConnectivitySnapshot.NONE

    /**
     * The ordinary state of essentially every smartphone: associated with WiFi *and* holding a
     * cellular data connection, with Android naming validated WiFi the default route for
     * general traffic. Both networks are genuinely connected; only one of them is the default.
     */
    private val wifiAndCellularWifiDefault = ConnectivitySnapshot(
        networks = listOf(wifiNetwork, cellularNetwork),
        defaultNetwork = wifiNetwork,
    )

    /** The mirror image: both connected, but the OS is routing over cellular (WiFi associated
     * and not yet validated, a metered-network preference, a hand-off in progress). */
    private val wifiAndCellularCellularDefault = ConnectivitySnapshot(
        networks = listOf(wifiNetwork, cellularNetwork),
        defaultNetwork = cellularNetwork,
    )

    // --- Multiple networks connected at once (issue #27) ---

    @Test
    fun `CELLULAR_ONLY is in scope when cellular is connected but WiFi is the default route`() {
        // The reported failure, verbatim: cellular scope reported "out of scope" on a device
        // with working cellular service, purely because WiFi was also on and therefore was the
        // network the OS had picked as the default route. Turning WiFi off "fixed" it. The
        // question this mode asks is "is cellular connected," not "is cellular the route."
        assertTrue(matches(NetworkScope.CELLULAR_ONLY, snapshot = wifiAndCellularWifiDefault))
    }

    @Test
    fun `WIFI_ONLY and CELLULAR_ONLY are both in scope when both transports are connected`() {
        // The core proof that scope now reads "is X connected" rather than "is X the default":
        // the default network can only ever be one of these, so under the old default-only
        // shape it was structurally impossible for both to be true at once no matter which one
        // the OS had chosen. Both networks are genuinely connected, so both must be in scope.
        assertTrue(matches(NetworkScope.WIFI_ONLY, snapshot = wifiAndCellularWifiDefault))
        assertTrue(matches(NetworkScope.CELLULAR_ONLY, snapshot = wifiAndCellularWifiDefault))
    }

    @Test
    fun `WIFI_ONLY is in scope when WiFi is connected but cellular is the default route`() {
        // The mirror-image failure of the reported one -- same root cause, opposite mode.
        assertTrue(matches(NetworkScope.WIFI_ONLY, snapshot = wifiAndCellularCellularDefault))
        assertTrue(matches(NetworkScope.CELLULAR_ONLY, snapshot = wifiAndCellularCellularDefault))
    }

    @Test
    fun `SSID_WHITELIST matches a connected WiFi network that is not the default route`() {
        // Same defect, third mode: the whitelisted network is connected and its SSID is
        // readable, but the OS is routing over cellular.
        assertTrue(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork"),
                hasLocationPermission = true,
                snapshot = wifiAndCellularCellularDefault,
            ),
        )
    }

    @Test
    fun `losing one network leaves the other still in scope`() {
        // Bookkeeping check at the matcher level: a snapshot describing "cellular went away,
        // WiFi is still up" must not read as "nothing is connected."
        val wifiRemains = wifiAndCellularWifiDefault.copy(networks = listOf(wifiNetwork))

        assertTrue(matches(NetworkScope.WIFI_ONLY, snapshot = wifiRemains))
        assertFalse(matches(NetworkScope.CELLULAR_ONLY, snapshot = wifiRemains))
    }

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
        val unvalidatedWifi = wifiNetwork.copy(isValidated = false)
        assertTrue(
            matches(
                NetworkScope.WIFI_ONLY,
                snapshot = ConnectivitySnapshot(listOf(unvalidatedWifi), unvalidatedWifi),
            ),
        )
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
        val unvalidatedWifi = wifiNetwork.copy(isValidated = false)
        assertFalse(
            matches(
                NetworkScope.ANY_CONNECTION,
                snapshot = ConnectivitySnapshot(listOf(unvalidatedWifi), unvalidatedWifi),
            ),
        )
    }

    @Test
    fun `ANY_CONNECTION asks about the route traffic takes, not about every connected network`() {
        // Deliberate asymmetry with the transport modes above, and the reason this mode was
        // left on the default network while the others moved off it: "any connection" is not
        // asking whether some transport is present, it is asking whether the device's internet
        // works -- and the probe that answers that opens an unbound socket, so it measures the
        // default network and nothing else. A validated cellular network sitting behind an
        // unvalidated default is not a connection this app is in a position to report on.
        val unvalidatedWifi = wifiNetwork.copy(isValidated = false)
        assertFalse(
            matches(
                NetworkScope.ANY_CONNECTION,
                snapshot = ConnectivitySnapshot(
                    networks = listOf(unvalidatedWifi, cellularNetwork),
                    defaultNetwork = unvalidatedWifi,
                ),
            ),
        )
    }

    @Test
    fun `ANY_CONNECTION is out of scope when networks are connected but none is the default route`() {
        // No default route means no path for general traffic, whatever else is associated.
        assertFalse(
            matches(
                NetworkScope.ANY_CONNECTION,
                snapshot = ConnectivitySnapshot(listOf(wifiNetwork), defaultNetwork = null),
            ),
        )
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
        val namelessWifi = wifiNetwork.copy(ssid = null)
        assertFalse(
            matches(
                NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("HomeNetwork"),
                hasLocationPermission = true,
                snapshot = ConnectivitySnapshot(listOf(namelessWifi), namelessWifi),
            ),
        )
    }

    private fun matches(
        scope: NetworkScope,
        ssidWhitelist: Set<String> = emptySet(),
        hasLocationPermission: Boolean = true,
        snapshot: ConnectivitySnapshot,
    ): Boolean = NetworkScopeMatcher.isInScope(
        scope = scope,
        ssidWhitelist = ssidWhitelist,
        hasLocationPermission = hasLocationPermission,
        snapshot = snapshot,
    )
}
