package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteComparisonEngineTest {
    private fun aggregate(host: String, phaseMs: Long, successful: Int = 5): RouteAggregate {
        val outcomes = (0 until 5).map { index ->
            if (index < successful) {
                RouteProbeOutcome(
                    host = host,
                    dnsMs = phaseMs,
                    tcpConnectMs = phaseMs,
                    tlsHandshakeMs = phaseMs,
                    httpStatusLineMs = phaseMs,
                    httpStatus = 204,
                    totalMs = phaseMs * 4,
                    tcpAttempts = listOf(TcpAddressAttempt("192.0.2.1", "IPv4", phaseMs))
                )
            } else {
                RouteProbeOutcome(
                    host = host,
                    failure = RouteProbeFailure.TCP_CONNECT,
                    totalMs = phaseMs
                )
            }
        }
        return RouteStatistics.aggregate(outcomes)
    }

    private fun batch(label: String, transport: NetworkTransport, ms: Long, successes: Int = 5) =
        RouteComparisonBatch(
            label = label,
            transport = transport,
            vpnLabel = if (label == "Direct") null else "Proton / serveur test",
            aggregates = listOf(aggregate("control.example", ms, successes))
        )

    @Test fun requiresThreeComparablePairs() {
        val result = RouteComparisonEngine.compare(
            listOf(batch("Direct", NetworkTransport.WIFI, 100)),
            listOf(batch("VPN", NetworkTransport.WIFI, 80))
        )
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(1, result.validPairs)
    }

    @Test fun rejectsPairsMeasuredOnDifferentTransports() {
        val direct = (1..3).map { batch("Direct", NetworkTransport.WIFI, 100) }
        val vpn = (1..3).map { batch("VPN", NetworkTransport.CELLULAR, 80) }
        val result = RouteComparisonEngine.compare(direct, vpn)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun identifiesConsistentControlEndpointImprovementOnlyAfterThreePairs() {
        val direct = (1..3).map { batch("Direct", NetworkTransport.WIFI, 100) }
        val vpn = (1..3).map { batch("VPN", NetworkTransport.WIFI, 60) }
        val result = RouteComparisonEngine.compare(direct, vpn)
        assertEquals(RouteComparisonVerdict.PROMISING, result.verdict)
        assertEquals(3, result.validPairs)
        assertTrue(result.limitations.any { it.contains("CODM") })
    }

    @Test fun identifiesReliabilityRegression() {
        val direct = (1..3).map { batch("Direct", NetworkTransport.WIFI, 100, 5) }
        val vpn = (1..3).map { batch("VPN", NetworkTransport.WIFI, 60, 3) }
        val result = RouteComparisonEngine.compare(direct, vpn)
        assertEquals(RouteComparisonVerdict.REGRESSION, result.verdict)
    }
}
