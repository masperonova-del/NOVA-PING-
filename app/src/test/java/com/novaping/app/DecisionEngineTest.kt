package com.novaping.app

import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {
    private val readyWifi = NetworkContext.from(
        NetworkTransport.WIFI, true, true, false, false, 50000, 10000
    )

    @Test fun recommendsCheckingDnsWhenResolutionFails() {
        val outcome = RouteProbeOutcome(
            host = "example.com", failure = RouteProbeFailure.DNS, totalMs = 10
        )
        val result = DecisionEngine.recommend(readyWifi, listOf(outcome))
        assertTrue(result.any { it.title == "Résolution DNS à vérifier" })
    }

    @Test fun warnsWhenInternetIsNotValidated() {
        val context = NetworkContext.from(
            NetworkTransport.CELLULAR, true, false, true, false, null, null
        )
        val result = DecisionEngine.recommend(context, emptyList())
        assertTrue(result.any { it.title == "Connexion non validée" })
    }

    @Test fun avoidsClaimingRouteOptimization() {
        val outcome = RouteProbeOutcome(
            host = "example.com", dnsMs = 12, tcpConnectMs = 30,
            tlsHandshakeMs = 40, httpFirstByteMs = 50, httpStatus = 204,
            totalMs = 132
        )
        val result = DecisionEngine.recommend(readyWifi, listOf(outcome))
        assertTrue(result.any { it.title.contains("anomalie majeure") })
    }
}
