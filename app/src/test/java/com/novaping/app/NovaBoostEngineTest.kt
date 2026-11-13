package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaBoostEngineTest {
    private val readyWifi = NetworkContext.from(
        NetworkTransport.WIFI, true, true, false, null, 50000, 10000
    )
    private val codm = GameCatalog.findByDisplayName("Call of Duty: Mobile")

    @Test fun observesWhenNoMeasurementEvidenceExists() {
        val result = NovaBoostEngine.assess(readyWifi, emptyList(), codm)
        assertEquals(BoostMode.OBSERVE, result.mode)
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
        assertTrue(result.summary.contains("Lance un diagnostic"))
    }

    @Test fun producesAdvisoryRecommendationsFromExistingMeasurements() {
        val samples = listOf(
            RouteProbeOutcome("control.example", failure = RouteProbeFailure.DNS, totalMs = 10),
            RouteProbeOutcome("control.example", failure = RouteProbeFailure.DNS, totalMs = 12),
            RouteProbeOutcome(
                "control.example", dnsMs = 8, tcpConnectMs = 20,
                tlsHandshakeMs = 30, httpStatusLineMs = 40,
                httpStatus = 204, totalMs = 100
            )
        )
        val aggregate = RouteStatistics.aggregate(samples)
        val result = NovaBoostEngine.assess(readyWifi, listOf(aggregate), codm)

        assertEquals(BoostMode.RECOMMEND, result.mode)
        assertTrue(result.recommendations.any { it.title == "Résolution DNS à vérifier" })
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
    }

    @Test fun neverClaimsCodmServerPingIsVerifiedWithoutEvidence() {
        val result = NovaBoostEngine.assess(readyWifi, emptyList(), codm)
        assertTrue(result.limitations.any { it.contains("ping réel") })
        assertTrue(result.limitations.any { it.contains("destination de match vérifiée") })
    }

    @Test fun doesNotClaimRoutingIsActive() {
        val result = NovaBoostEngine.assess(readyWifi, emptyList(), codm)
        assertFalse(result.routingAvailable)
        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.limitations.any { it.contains("VPN") || it.contains("routage") })
    }
}
