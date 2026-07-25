package com.uplinkstatus.core.probe

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * The real, production [Prober]: a plain TCP connect-time probe, per the spec.
 *
 * Unprivileged Android apps can't open raw ICMP sockets, so "ping" here is: resolve the
 * target hostname, open a [Socket] to it on the given port, time how long `connect()`
 * takes, then close it immediately without sending or receiving any payload. One round
 * trip, same shape as an ICMP echo, works under the ordinary `INTERNET` permission.
 *
 * DNS resolution is deliberately a separate step from the connect attempt (rather than
 * relying on [Socket.connect] to resolve implicitly) so an [UnknownHostException] there
 * can be reported as [ProbeResult.DnsResolutionFailure] — a distinct condition from a
 * generic connect [ProbeResult.Failure] per the spec.
 *
 * [resolveHost] and [connect] are constructor-injected (rather than hardcoded to
 * `InetAddress.getByName` / `Socket.connect`) specifically so this class's exception-to-
 * ProbeResult mapping can be unit-tested with fake functions that throw the same
 * exception types real I/O would — without ever touching a real socket or the system
 * resolver. See TcpConnectProberTest.
 */
class TcpConnectProber(
    private val resolveHost: (String) -> InetAddress = { host -> InetAddress.getByName(host) },
    private val connect: (InetAddress, Int, Int) -> Unit = { address, port, timeoutMs ->
        Socket().use { socket -> socket.connect(InetSocketAddress(address, port), timeoutMs) }
    },
    private val nanoTime: () -> Long = System::nanoTime,
) : Prober {

    override fun probe(target: ProbeTarget): ProbeResult {
        val address =
            try {
                resolveHost(target.host)
            } catch (e: UnknownHostException) {
                return ProbeResult.DnsResolutionFailure
            }

        return try {
            val start = nanoTime()
            connect(address, target.port, target.timeoutMs.toInt())
            val elapsedMs = (nanoTime() - start) / NANOS_PER_MILLI
            ProbeResult.Success(elapsedMs)
        } catch (e: IOException) {
            // Covers SocketTimeoutException (the 1000ms timeout firing), connection
            // refused, host unreachable, etc. — anything past DNS resolution.
            ProbeResult.Failure
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
