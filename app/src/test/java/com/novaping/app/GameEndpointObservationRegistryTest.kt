package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEndpointObservationRegistryTest {
    private val packageName = "com.activision.callofduty.shooter"

    private fun observation(
        session: String = "session-1",
        address: String = "1.1.1.1",
        port: Int = 7500,
        transport: GameEndpointTransport = GameEndpointTransport.UDP,
        observedAt: Long = 1_800_000_000_000L,
        consent: Boolean = true,
        appPackage: String = packageName
    ) = GameEndpointObservation(
        gameId = "codm",
        gameAppPackage = appPackage,
        sessionId = session,
        remoteAddress = address,
        remotePort = port,
        transport = transport,
        observedAtEpochMs = observedAt,
        userConsentGranted = consent
    )

    @Test fun rejectsObservationWithoutExplicitConsent() {
        assertFalse(GameEndpointObservationRegistry.isAdmissible(
            observation(consent = false), packageName
        ))
    }

    @Test fun rejectsObservationFromAnotherApplication() {
        assertFalse(GameEndpointObservationRegistry.isAdmissible(
            observation(appPackage = "com.example.other"), packageName
        ))
    }

    @Test fun acceptsPublicIpv4AndIpv6Literals() {
        assertTrue(GameEndpointObservationRegistry.isAdmissible(
            observation(address = "1.1.1.1"), packageName
        ))
        assertTrue(GameEndpointObservationRegistry.isAdmissible(
            observation(address = "2606:4700:4700::1111"), packageName
        ))
    }

    @Test fun rejectsPrivateLoopbackAndMalformedAddresses() {
        listOf(
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "127.0.0.1",
            "169.254.1.1",
            "fc00::1",
            "localhost",
            "256.1.1.1",
            "1.1.1.1:443"
        ).forEach { address ->
            assertFalse("Expected address to be rejected: $address",
                GameEndpointObservationRegistry.isAdmissible(
                    observation(address = address), packageName
                ))
        }
    }

    @Test fun rejectsInvalidPortsAndTimestamps() {
        assertFalse(GameEndpointObservationRegistry.isAdmissible(
            observation(port = 0), packageName
        ))
        assertFalse(GameEndpointObservationRegistry.isAdmissible(
            observation(port = 65536), packageName
        ))
        assertFalse(GameEndpointObservationRegistry.isAdmissible(
            observation(observedAt = 0L), packageName
        ))
    }

    @Test fun aggregatesByAddressPortAndTransportAcrossSessions() {
        val observations = listOf(
            observation(session = "one", observedAt = 100L),
            observation(session = "one", observedAt = 200L),
            observation(session = "two", observedAt = 300L),
            observation(session = "two", transport = GameEndpointTransport.TCP, observedAt = 400L)
        )

        val candidates = GameEndpointObservationRegistry.candidatesForGame(
            observations, "codm", packageName
        )

        assertEquals(2, candidates.size)
        val udp = candidates.first { it.transport == GameEndpointTransport.UDP }
        assertEquals(3, udp.observationCount)
        assertEquals(2, udp.distinctSessionCount)
        assertEquals(100L, udp.firstObservedAtEpochMs)
        assertEquals(300L, udp.lastObservedAtEpochMs)
        assertTrue(udp.repeatedAcrossSessions)
        assertTrue(udp.evidenceLabel.contains("RÔLE SERVEUR NON CONFIRMÉ"))
    }

    @Test fun doesNotTreatRepeatedObservationsInOneSessionAsRepeatedSessions() {
        val candidates = GameEndpointObservationRegistry.candidatesForGame(
            listOf(
                observation(session = "same", observedAt = 100L),
                observation(session = "same", observedAt = 200L),
                observation(session = "same", observedAt = 300L)
            ),
            "codm",
            packageName
        )

        assertEquals(1, candidates.size)
        assertEquals(3, candidates.single().observationCount)
        assertEquals(1, candidates.single().distinctSessionCount)
        assertFalse(candidates.single().repeatedAcrossSessions)
    }

    @Test fun filtersOtherGamesAndApplications() {
        val valid = observation()
        val otherGame = valid.copy(gameId = "pubg")
        val otherApp = valid.copy(gameAppPackage = "com.example.other")
        val candidates = GameEndpointObservationRegistry.candidatesForGame(
            listOf(valid, otherGame, otherApp), "codm", packageName
        )
        assertEquals(1, candidates.size)
        assertEquals("1.1.1.1", candidates.single().remoteAddress)
    }
}
