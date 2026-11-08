package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {
    private val readyWifi = NetworkContext.from(
        NetworkTransport.WIFI, true, true, false, null, 50000, 10000
    )

    private fun aggregate(samples: List<RouteProbeOutcome>) = RouteStatistics.aggregate(samples)

    @Test fun recommendsCheckingDnsAfterRepeatedFailures() {
        val result = DecisionEngine.recommend(readyWifi, listOf(aggregate(listOf(
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.DNS, totalMs = 10),
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.DNS, totalMs = 12),
            RouteProbeOutcome("example.com", dnsMs = 8, tcpConnectMs = 20, tlsHandshakeMs = 30,
                httpStatusLineMs = 40, httpStatus = 204, totalMs = 100)
        ))))
        assertTrue(result.any { it.title == "Résolution DNS à vérifier" })
    }

    @Test fun warnsWhenInternetIsNotValidated() {
        val context = NetworkContext.from(
            NetworkTransport.CELLULAR, true, false, true, null, null, null
        )
        val result = DecisionEngine.recommend(context, emptyList())
        assertTrue(result.any { it.title == "Connexion non validée" })
    }

    @Test fun cleanResultRequiresRepeatedCompleteResponses() {
        val result = DecisionEngine.recommend(readyWifi, listOf(
            aggregate(List(5) { i ->
                RouteProbeOutcome("google.example", 12L + i, 20L + i, 30L + i, 40L + i, 204, null, 110L + i)
            }),
            aggregate(List(5) { i ->
                RouteProbeOutcome("cloudflare.example", 12L + i, 20L + i, 30L + i, 40L + i, 204, null, 110L + i)
            }),
            aggregate(List(5) { i ->
                RouteProbeOutcome("microsoft.example", 12L + i, 20L + i, 30L + i, 40L + i, 204, null, 110L + i)
            })
        ))
        assertTrue(result.any { it.title.contains("Aucune anomalie majeure") })
    }

    @Test fun doesNotReportCleanResultForIncompleteMeasurement() {
        val result = DecisionEngine.recommend(readyWifi, listOf(aggregate(listOf(
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.UNKNOWN, totalMs = 100),
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.UNKNOWN, totalMs = 100),
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.UNKNOWN, totalMs = 100)
        ))))
        assertTrue(result.any { it.title == "Réponse incomplète" })
        assertFalse(result.any { it.title.contains("Aucune anomalie majeure") })
    }

    @Test fun oneIsolatedFailureDoesNotTriggerNetworkDiagnosis() {
        val result = DecisionEngine.recommend(readyWifi, listOf(aggregate(listOf(
            RouteProbeOutcome("example.com", 12, 20, 30, 40, 204, null, 110),
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.TLS_HANDSHAKE, totalMs = 70),
            RouteProbeOutcome("example.com", 14, 22, 32, 42, 204, null, 120)
        ))))
        assertFalse(result.any { it.title == "Négociation TLS à vérifier" })
        assertFalse(result.any { it.title.contains("Aucune anomalie majeure") })
    }
    @Test fun partialEndpointSetCannotBeDeclaredClean() {
        val result = DecisionEngine.recommend(readyWifi, listOf(aggregate(listOf(
            RouteProbeOutcome("google.example", 12, 20, 30, 40, 204, null, 110),
            RouteProbeOutcome("google.example", 13, 21, 31, 41, 204, null, 115),
            RouteProbeOutcome("google.example", 14, 22, 32, 42, 204, null, 120)
        ))))
        assertFalse(result.any { it.title.contains("Aucune anomalie majeure") })
    }

}
