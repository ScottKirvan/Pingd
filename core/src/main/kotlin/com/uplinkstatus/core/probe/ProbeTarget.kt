package com.uplinkstatus.core.probe

/**
 * What to probe and how. Per the spec, the default target is a *hostname* (not a bare IP
 * literal) so the OS can pick whichever address family (IPv4/IPv6) the network actually
 * supports, port 443, with a fixed 1000ms timeout and no adaptive back-off.
 */
data class ProbeTarget(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_PORT: Int = 443
        const val DEFAULT_TIMEOUT_MS: Long = 1000L

        /** Cloudflare — the spec's default probe target. */
        const val DEFAULT_HOST: String = "one.one.one.one"

        /** Google — the spec's alternate quick-pick. */
        const val ALTERNATE_HOST: String = "dns.google"
    }
}
