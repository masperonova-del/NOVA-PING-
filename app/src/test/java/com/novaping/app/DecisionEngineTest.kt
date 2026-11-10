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

    @Test fun identifiesTheMostReliableControlEndpointWithoutCallingItAGameServer() {
        val result = DecisionEngine.recommend(readyWifi, listOf(
            aggregate(List(5) { i ->
                if (i < 3) RouteProbeOutcome("cloudflare.example", 5, 100, 300, 400, 204, null, 900)
                else RouteProbeOutcome("cloudflare.example", failure = RouteProbeFailure.TLS_HANDSHAKE, totalMs = 500)
            }),
            aggregate(List(5) {
                RouteProbeOutcome("google.example", failure = RouteProbeFailure.TIMEOUT, totalMs = 1800)
            }),
            aggregate(List(5) {
                RouteProbeOutcome("microsoft.example", failure = RouteProbeFailure.TLS_HANDSHAKE, totalMs = 1700)
            })
        ))
        val reliability = result.first { it.title == "Point de contrôle le plus fiable dans cet échantillon" }
        assertTrue(reliability.explanation.contains("cloudflare.example"))
        assertTrue(reliability.explanation.contains("3/5"))
        assertTrue(reliability.explanation.contains("Route Engine"))
        assertTrue(reliability.explanation.contains("25 requêtes planifiées"))
        assertTrue(reliability.action.contains("ne désigne pas le meilleur serveur CODM"))
    }

    @Test fun reportsInsufficientDataWhenNoEndpointCompletesEnoughConnections() {
        val result = DecisionEngine.recommend(readyWifi, listOf(
            aggregate(List(5) { RouteProbeOutcome("google.example", failure = RouteProbeFailure.TIMEOUT, totalMs = 1800) }),
            aggregate(List(5) { RouteProbeOutcome("cloudflare.example", failure = RouteProbeFailure.TLS_HANDSHAKE, totalMs = 900) })
        ))
        assertTrue(result.any { it.title == "Données insuffisantes pour classer la fiabilité" })
        assertTrue(result.any { it.title == "Comparaison des délais de phase non concluante" })
    }

    @Test fun comparesPhaseMedianSumsOnlyWhenEveryPhaseHasEnoughSamples() {
        val result = DecisionEngine.recommend(readyWifi, listOf(
            aggregate(List(5) { i ->
                RouteProbeOutcome(
                    host = "cloudflare.example", dnsMs = 5L + i, tcpConnectMs = 10L + i,
                    tlsHandshakeMs = 200L + i, httpStatusLineMs = 300L + i,
                    httpStatus = 204, totalMs = 700L + i,
                    tcpAttempts = listOf(
                        TcpAddressAttempt("2001:db8::1", "IPv6", 800L),
                        TcpAddressAttempt("192.0.2.1", "IPv4", 10L + i)
                    )
                )
            }),
            aggregate(List(5) { i ->
                RouteProbeOutcome(
                    host = "google.example", dnsMs = 10L + i, tcpConnectMs = 200L + i,
                    tlsHandshakeMs = 400L + i, httpStatusLineMs = 500L + i,
                    httpStatus = 204, totalMs = 1100L + i,
                    tcpAttempts = listOf(TcpAddressAttempt("192.0.2.2", "IPv4", 200L + i))
                )
            })
        ))
        val latency = result.first { it.title == "Phases cumulées les plus courtes (indicatif)" }
        assertTrue(latency.explanation.contains("google.example"))
        assertTrue(latency.explanation.contains("somme des médianes"))
        assertTrue(latency.explanation.contains("Route Engine"))
        assertTrue(latency.explanation.contains("diagnostic HTTPS global"))
        assertTrue(latency.explanation.contains("total des tentatives TCP"))
        assertTrue(latency.action.contains("ne mesure pas la latence d'un serveur de jeu"))
    }

    @Test fun refusesPhaseLatencyRankingWhenOnePhaseHasTooFewSamples() {
        val partialTls = List(5) { i ->
            if (i < 2) RouteProbeOutcome("google.example", 5, 100, 200, 300, 204, null, 700)
            else RouteProbeOutcome("google.example", 5, 100, failure = RouteProbeFailure.TLS_HANDSHAKE, totalMs = 400)
        }
        val complete = List(5) { i ->
            RouteProbeOutcome("cloudflare.example", 5L + i, 100L + i, 200L + i, 300L + i, 204, null, 700L + i)
        }
        val result = DecisionEngine.recommend(readyWifi, listOf(aggregate(partialTls), aggregate(complete)))
        assertTrue(result.any { it.title == "Comparaison des délais de phase non concluante" })
        assertFalse(result.any { it.title == "Phases cumulées les plus courtes (indicatif)" })
    }

    @Test fun classifiesMicrosoftCertificateHostnameMismatch() {
        assertTrue(
            TlsFailureClassifier.classify(
                "SSLHandshakeException",
                "No subjectAltNames on the certificate match"
            ) == TlsFailureCategory.HOSTNAME_MISMATCH
        )
    }

    @Test fun classifiesExpiredAndUntrustedCertificatesSeparately() {
        assertTrue(TlsFailureClassifier.classify("CertificateException", "Certificate expired") ==
            TlsFailureCategory.CERTIFICATE_EXPIRED)
        assertTrue(TlsFailureClassifier.classify("CertPathValidatorException", "Trust anchor for certification path not found") ==
            TlsFailureCategory.CERTIFICATE_UNTRUSTED)
    }

    @Test fun classifiesTlsTimeoutSeparately() {
        assertTrue(TlsFailureClassifier.classify("SocketTimeoutException", "Read timed out") ==
            TlsFailureCategory.TLS_TIMEOUT)
    }

    @Test fun classifiesTlsProtocolErrorsSeparately() {
        assertTrue(TlsFailureClassifier.classify("SSLHandshakeException", "protocol_version") ==
            TlsFailureCategory.TLS_PROTOCOL_ERROR)
    }

    @Test fun unknownTlsErrorsRemainUnknown() {
        assertTrue(TlsFailureClassifier.classify("SSLException", "Unexpected TLS alert") ==
            TlsFailureCategory.TLS_UNKNOWN)
    }
}
