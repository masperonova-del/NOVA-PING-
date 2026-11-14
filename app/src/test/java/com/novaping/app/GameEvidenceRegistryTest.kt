package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEvidenceRegistryTest {
    private val now = 1_800_000_000_000L

    private fun evidence(
        sourceUrl: String = "https://example.org/server-info",
        endpointHost: String = "match.example.org",
        protocol: String = "UDP",
        expiresAt: Long = now + 86_400_000L
    ) = GameServerEvidence(
        gameId = "codm",
        region = "global",
        endpointHost = endpointHost,
        protocol = protocol,
        sourceUrl = sourceUrl,
        sourcePublishedAtEpochMs = now - 86_400_000L,
        checkedAtEpochMs = now - 1_000L,
        expiresAtEpochMs = expiresAt
    )

    @Test fun acceptsCurrentEvidenceWithHttpsProvenance() {
        assertTrue(GameEvidenceRegistry.isUsable(evidence(), now))
    }

    @Test fun acceptsValidIpv4AndInternationalizedHostnames() {
        assertTrue(GameEvidenceRegistry.isUsable(evidence(endpointHost = "192.0.2.10"), now))
        assertTrue(GameEvidenceRegistry.isUsable(evidence(endpointHost = "serveur.éxemple.fr"), now))
    }

    @Test fun rejectsExpiredEvidence() {
        assertFalse(GameEvidenceRegistry.isUsable(evidence(expiresAt = now), now))
    }

    @Test fun rejectsNonHttpsOrCredentialBearingProvenance() {
        assertFalse(GameEvidenceRegistry.isUsable(evidence(sourceUrl = "http://example.org/info"), now))
        assertFalse(GameEvidenceRegistry.isUsable(
            evidence(sourceUrl = "https://user:pass@example.org/info"), now
        ))
    }

    @Test fun rejectsEvidenceCheckedBeforeItsSourceWasPublished() {
        val invalid = evidence().copy(sourcePublishedAtEpochMs = now, checkedAtEpochMs = now - 1L)
        assertFalse(GameEvidenceRegistry.isUsable(invalid, now))
    }

    @Test fun rejectsMalformedEndpointHosts() {
        listOf(
            "match",
            "match..example.org",
            "match.example.org/path",
            "https://match.example.org",
            "match.example.org:443",
            "bad host.example.org",
            "-bad.example.org",
            "bad-.example.org",
            "256.1.1.1",
            "192.168.001.1"
        ).forEach { host ->
            assertFalse("Expected host to be rejected: $host",
                GameEvidenceRegistry.isUsable(evidence(endpointHost = host), now))
        }
    }

    @Test fun rejectsUnsupportedOrMislabelledProtocols() {
        listOf("HTTPS", "HTTP", "TCP/UDP", "").forEach { protocol ->
            assertFalse("Expected protocol to be rejected: $protocol",
                GameEvidenceRegistry.isUsable(evidence(protocol = protocol), now))
        }
    }

    @Test fun filtersEvidenceByGameAndValidity() {
        val valid = evidence()
        val otherGame = valid.copy(gameId = "pubg")
        val expired = evidence(expiresAt = now - 1L)
        assertEquals(listOf(valid), GameEvidenceRegistry.usableForGame(
            listOf(valid, otherGame, expired), "codm", now
        ))
    }
}
