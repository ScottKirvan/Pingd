package com.uplinkstatus.core.probe

import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [TcpConnectProber]'s exception-to-[ProbeResult] mapping with fake
 * `resolveHost`/`connect` functions injected via its constructor — no real socket or DNS
 * lookup ever happens here, per the brief's "no real network calls in unit tests"
 * requirement. Real socket/DNS behavior is validated manually on-device (see the Stage 7
 * device-testing protocol), since that's genuine OS-level I/O this pure-Kotlin module
 * can't (and per the brief, shouldn't) simulate in a unit test.
 */
class TcpConnectProberTest {

    private val target = ProbeTarget(host = "example.invalid", port = 443, timeoutMs = 1000)
    private val stubAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))

    @Test
    fun `successful connect reports Success with elapsed time`() {
        var callIndex = 0
        val nanoValues = listOf(0L, 5_000_000L) // 5ms elapsed

        val prober = TcpConnectProber(
            resolveHost = { stubAddress },
            connect = { _, _, _ -> /* succeeds without throwing */ },
            nanoTime = { nanoValues[callIndex++] },
        )

        val result = prober.probe(target)

        assertTrue(result is ProbeResult.Success)
        assertEquals(5L, (result as ProbeResult.Success).latencyMs)
    }

    @Test
    fun `unresolvable hostname reports DnsResolutionFailure`() {
        val prober = TcpConnectProber(
            resolveHost = { throw UnknownHostException("example.invalid") },
            connect = { _, _, _ -> error("connect should never be reached without a resolved address") },
        )

        assertEquals(ProbeResult.DnsResolutionFailure, prober.probe(target))
    }

    @Test
    fun `connect timeout reports generic Failure, not DnsResolutionFailure`() {
        val prober = TcpConnectProber(
            resolveHost = { stubAddress },
            connect = { _, _, _ -> throw SocketTimeoutException("timed out") },
        )

        assertEquals(ProbeResult.Failure, prober.probe(target))
    }

    @Test
    fun `connection refused also reports generic Failure`() {
        val prober = TcpConnectProber(
            resolveHost = { stubAddress },
            connect = { _, _, _ -> throw ConnectException("Connection refused") },
        )

        assertEquals(ProbeResult.Failure, prober.probe(target))
    }

    @Test
    fun `connect receives the resolved address, target port, and timeout`() {
        var receivedAddress: InetAddress? = null
        var receivedPort: Int? = null
        var receivedTimeout: Int? = null

        val prober = TcpConnectProber(
            resolveHost = { stubAddress },
            connect = { address, port, timeoutMs ->
                receivedAddress = address
                receivedPort = port
                receivedTimeout = timeoutMs
            },
        )

        prober.probe(ProbeTarget(host = "example.invalid", port = 443, timeoutMs = 1000))

        assertEquals(stubAddress, receivedAddress)
        assertEquals(443, receivedPort)
        assertEquals(1000, receivedTimeout)
    }
}
