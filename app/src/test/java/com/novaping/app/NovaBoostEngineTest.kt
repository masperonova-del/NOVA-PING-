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

    private fun aggregate(host: String, completeSamples: Int = 5): RouteAggregate {
        val outcomes = (0 until MeasurementPlan.ROUTE_SAMPLES_PER_TARGET).map { index ->
            if (index < completeSamples) {
                RouteProbeOutcome(
                    host = host,
                    dnsMs = 8L + index,
                    tcpConnectMs = 20L + index,
                    tlsHandshakeMs = 30L + index,
                    httpStatusLineMs = 40L + index,
                    httpStatus = 204,
                    totalMs = 100L + index
                )
            } else {
                RouteProbeOutcome(
                    host = host,
                    dnsMs = 8L,
                    failure = RouteProbeFailure.TCP_CONNECT,
                    totalMs = 10L
                )
            }
        }
        return RouteStatistics.aggregate(outcomes)
    }

    @Test
    fun observesWhenNoMeasurementEvidenceExists() {
        val result = NovaBoostEngine.assess(readyWifi, emptyList(), codm)
        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.evidenceSummary.contains("0/0"))
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
    }

    @Test
    fun oneDestinationCannotPromoteBoostToRecommend() {
        val samples = listOf(
            RouteProbeOutcome("control.example", failure = RouteProbeFailure.DNS, totalMs = 10),
            RouteProbeOutcome("control.example", failure = RouteProbeFailure.DNS, totalMs = 12),
            RouteProbeOutcome(
                "control.example", dnsMs = 8, tcpConnectMs = 20,
                tlsHandshakeMs = 30, httpStatusLineMs = 40,
                httpStatus = 204, totalMs = 100
            )
        )
        val result = NovaBoostEngine.assess(
            readyWifi, listOf(RouteStatistics.aggregate(samples)), codm
        )

        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.evidenceSummary.contains("0/1"))
        assertTrue(result.recommendations.any { it.title == "Résolution DNS à vérifier" })
        assertFalse(result.routingAvailable)
    }

    @Test
    fun requiresTwoTargetsWithAtLeastThreeCompleteSamplesEach() {
        val result = NovaBoostEngine.assess(
            readyWifi,
            listOf(
                aggregate("google-control.example", completeSamples = 5),
                aggregate("cloudflare-control.example", completeSamples = 3),
                aggregate("microsoft-control.example", completeSamples = 2)
            ),
            codm
        )

        assertEquals(BoostMode.RECOMMEND, result.mode)
        assertTrue(result.evidenceSummary.contains("2/3"))
        assertTrue(result.summary.contains("niveau de preuve minimal"))
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
    }

    @Test
    fun oneStrongEndpointAndOneWeakEndpointRemainInObserveMode() {
        val result = NovaBoostEngine.assess(
            readyWifi,
            listOf(
                aggregate("google-control.example", completeSamples = 5),
                aggregate("cloudflare-control.example", completeSamples = 2)
            ),
            codm
        )

        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.evidenceSummary.contains("1/2"))
    }

    @Test
    fun unvalidatedInternetProducesAnActionableConnectivityRecommendation() {
        val unvalidatedWifi = NetworkContext.from(
            NetworkTransport.WIFI, true, false, false, null, null, null
        )
        val result = NovaBoostEngine.assess(unvalidatedWifi, emptyList(), codm)

        assertEquals(BoostMode.RECOMMEND, result.mode)
        assertTrue(result.recommendations.any { it.title == "Connexion non validée" })
        assertTrue(result.summary.contains("ne valide pas"))
    }

    @Test
    fun neverClaimsCodmServerPingIsVerifiedWithoutEvidence() {
        val result = NovaBoostEngine.assess(readyWifi, emptyList(), codm)
        assertTrue(result.limitations.any { it.contains("ping réel") })
        assertTrue(result.limitations.any { it.contains("destination de match vérifiée") })
    }

    @Test
    fun doesNotClaimRoutingIsActive() {
        val result = NovaBoostEngine.assess(readyWifi, emptyList(), codm)
        assertFalse(result.routingAvailable)
        assertFalse(result.vpnConsentRequired)
        assertTrue(result.limitations.any { it.contains("VPN") || it.contains("routage") })
    }

    @Test
    fun duplicateHostAggregatesDoNotInflateEvidenceCount() {
        val duplicated = aggregate("google-control.example")
        val result = NovaBoostEngine.assess(
            readyWifi,
            listOf(duplicated, duplicated, aggregate("cloudflare-control.example", 5)),
            codm
        )

        assertEquals(BoostMode.OBSERVE, result.mode)
        assertTrue(result.evidenceSummary.contains("1/2"))
    }
}
