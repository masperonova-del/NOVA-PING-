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

    @Test fun partialMeasurementsStayInObservationEvenWhenTheyContainRepeatedFailures() {
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

        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.recommendations.any { it.title == "Résolution DNS à vérifier" })
        assertTrue(result.summary.contains("reste en observation"))
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
    }

    @Test fun recommendsOnlyWithTwoQualifiedFiveAttemptTargets() {
        val google = qualifiedAggregate("google.example")
        val cloudflare = qualifiedAggregate("cloudflare.example")
        val microsoft = RouteStatistics.aggregate(
            List(5) { RouteProbeOutcome("microsoft.example", failure = RouteProbeFailure.TIMEOUT, totalMs = 1000) }
        )

        val result = NovaBoostEngine.assess(
            readyWifi,
            listOf(google, cloudflare, microsoft),
            codm
        )

        assertEquals(BoostMode.RECOMMEND, result.mode)
        assertTrue(result.summary.contains("suffisamment complètes"))
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
    }

    @Test fun oneQualifiedTargetIsNotEnoughForRecommendationMode() {
        val result = NovaBoostEngine.assess(
            readyWifi,
            listOf(qualifiedAggregate("google.example")),
            codm
        )

        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.summary.contains("au moins deux destinations"))
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

    private fun qualifiedAggregate(host: String): RouteAggregate {
        val successful = List(4) { index ->
            RouteProbeOutcome(
                host,
                dnsMs = 8L + index,
                tcpConnectMs = 20L + index,
                tlsHandshakeMs = 30L + index,
                httpStatusLineMs = 40L + index,
                httpStatus = 204,
                totalMs = 100L + index
            )
        }
        val oneTimeout = RouteProbeOutcome(
            host,
            failure = RouteProbeFailure.TIMEOUT,
            totalMs = 1000
        )
        return RouteStatistics.aggregate(successful + oneTimeout)
    }
}