package com.bojustudio.pingd.app.prefs

/**
 * A pragmatic RFC 1123-style hostname check for the ping-target custom-override field (see
 * the spec's "Ping target host" preference). Deliberately not exhaustive DNS-name validation
 * — it exists to catch obviously-wrong input (blank, whitespace, a pasted URL with a scheme)
 * before it's persisted and handed to [com.bojustudio.pingd.core.probe.TcpConnectProber], not to
 * be a full RFC 1035/1123 label-length-per-component validator. A hostname that passes this
 * but doesn't actually resolve still surfaces correctly at probe time as
 * `ProbeResult.DnsResolutionFailure`, per the spec's distinct-DNS-failure requirement.
 */
private val HOSTNAME_REGEX = Regex(
    "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]{0,61}[a-zA-Z0-9])\\.)*([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]{0,61}[a-zA-Z0-9])$",
)

fun isValidHostname(host: String): Boolean {
    val trimmed = host.trim()
    if (trimmed.isEmpty() || trimmed.length > 253) return false
    if (trimmed.any { it.isWhitespace() } || trimmed.contains("://")) return false
    return HOSTNAME_REGEX.matches(trimmed)
}
