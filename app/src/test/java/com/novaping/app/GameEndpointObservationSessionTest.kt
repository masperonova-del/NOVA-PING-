package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEndpointObservationSessionTest {
    private fun metadata(
        address: String = "1.1.1.1",
        port: Int = 7500,
        transport: Int = 17,
        direction: Int = 1,
        packetLengthBytes: Int = 96
    ) = HevPacketMetadata(
        ipVersion = 4,
        remoteAddress = address,
        transport = transport,
        direction = direction,
        remotePort = port,
        packetLengthBytes = packetLengthBytes,
        fragmented = false
    )

    @Test
    fun recordsOnlyConsentedPublicEndpointsForSelectedPackage() {
        val session = GameEndpointObservationSession(
            gameId = "codm",
            gameAppPackage = "com.activision.callofduty.shooter",
            endpointObservationOptInGranted = true
        )

        assertTrue(session.record(metadata(), 1_800_000_000_000L))
        assertFalse(session.record(metadata("192.168.1.1"), 1_800_000_000_001L))
        assertFalse(session.record(metadata("1.1.1.1", port = 0), 1_800_000_000_002L))
        assertEquals(1, session.observationCount())
        val candidate = session.candidates().single()
        assertEquals("codm", candidate.gameId)
        assertEquals("com.activision.callofduty.shooter", candidate.gameAppPackage)
        assertEquals("1.1.1.1", candidate.remoteAddress)
        assertEquals(7500, candidate.remotePort)
        assertEquals(GameEndpointTransport.UDP, candidate.transport)
        assertTrue(candidate.evidenceLabel.contains("RÔLE SERVEUR NON CONFIRMÉ"))
    }


    @Test
    fun retainsDirectionAndPacketSizeForEndpointAggregation() {
        val session = GameEndpointObservationSession(
            gameId = "codm",
            gameAppPackage = "com.activision.callofduty.shooter",
            endpointObservationOptInGranted = true
        )

        assertTrue(session.record(metadata(direction = 1, packetLengthBytes = 120), 100L))
        assertTrue(session.record(metadata(direction = 2, packetLengthBytes = 80), 200L))

        val candidate = session.candidates().single()
        assertEquals(1, candidate.outboundPacketCount)
        assertEquals(1, candidate.inboundPacketCount)
        assertEquals(200L, candidate.totalObservedBytes)
        assertEquals(80, candidate.minPacketLengthBytes)
        assertEquals(120, candidate.maxPacketLengthBytes)
    }

    @Test
    fun rejectsUnknownPacketDirection() {
        val session = GameEndpointObservationSession(
            gameId = "codm",
            gameAppPackage = "com.activision.callofduty.shooter",
            endpointObservationOptInGranted = true
        )

        assertFalse(session.record(metadata(direction = 3), 100L))
        assertEquals(0, session.observationCount())
    }

    @Test
    fun refusesToRecordWithoutExplicitEndpointObservationOptIn() {
        val session = GameEndpointObservationSession(
            gameId = "codm",
            gameAppPackage = "com.activision.callofduty.shooter",
            endpointObservationOptInGranted = false
        )

        assertFalse(session.record(metadata(), 1_800_000_000_000L))
        assertEquals(0, session.observationCount())
        assertTrue(session.candidates().isEmpty())
    }

    @Test
    fun boundsSessionMemoryAndKeepsNewestObservations() {
        val session = GameEndpointObservationSession(
            gameId = "codm",
            gameAppPackage = "com.activision.callofduty.shooter",
            endpointObservationOptInGranted = true,
            sessionId = "test-session",
            maxObservations = 2
        )

        assertTrue(session.record(metadata("1.1.1.1"), 100L))
        assertTrue(session.record(metadata("8.8.8.8"), 200L))
        assertTrue(session.record(metadata("9.9.9.9"), 300L))

        assertEquals(2, session.observationCount())
        assertEquals(
            setOf("8.8.8.8", "9.9.9.9"),
            session.candidates().map { it.remoteAddress }.toSet()
        )
    }

    @Test
    fun clearDropsAllEndpointObservations() {
        val session = GameEndpointObservationSession(
            gameId = "codm",
            gameAppPackage = "com.activision.callofduty.shooter",
            endpointObservationOptInGranted = true
        )
        assertTrue(session.record(metadata(), 1_800_000_000_000L))

        session.clear()

        assertEquals(0, session.observationCount())
        assertTrue(session.candidates().isEmpty())
    }
}
