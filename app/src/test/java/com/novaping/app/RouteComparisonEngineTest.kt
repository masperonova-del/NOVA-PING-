package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        assertEquals(0, result.validPairs)
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
    @Test fun rejectsUnequalSeriesCountsInsteadOfSilentlyZipping() {
        val direct = (1..4).map { batch("Direct", NetworkTransport.WIFI, 100) }
        val candidate = (1..3).map { batch("VPN", NetworkTransport.WIFI, 80) }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsDuplicateHostsInsideOneBatch() {
        val base = batch("Direct", NetworkTransport.WIFI, 100)
        val candidate = batch("VPN", NetworkTransport.WIFI, 80)
        val direct = (1..3).map {
            base.copy(aggregates = listOf(
                aggregate("control.example", 100),
                aggregate("control.example", 110)
            ))
        }
        val vpn = (1..3).map { candidate }
        val result = RouteComparisonEngine.compare(direct, vpn)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsSeriesWhenCandidateIdentityChangesBetweenPairs() {
        val direct = (1..3).map { batch("Direct", NetworkTransport.WIFI, 100) }
        val candidate = listOf(
            batch("VPN A", NetworkTransport.WIFI, 80),
            batch("VPN A", NetworkTransport.WIFI, 80),
            batch("VPN B", NetworkTransport.WIFI, 80)
        )
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsIncompletePhaseEvidenceEvenWhenFiveSamplesWereAttempted() {
        val direct = (1..3).map {
            batch("Direct", NetworkTransport.WIFI, 100, successes = 2)
        }
        val candidate = (1..3).map {
            batch("VPN", NetworkTransport.WIFI, 80, successes = 5)
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsCandidateWhenVpnLabelChangesBetweenPairs() {
        val direct = (1..3).map { batch("Direct", NetworkTransport.WIFI, 100) }
        val candidate = (1..3).mapIndexed { index, _ ->
            batch("VPN", NetworkTransport.WIFI, 80).copy(
                vpnLabel = if (index == 2) "Autre serveur" else "Proton / serveur test"
            )
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }

}
