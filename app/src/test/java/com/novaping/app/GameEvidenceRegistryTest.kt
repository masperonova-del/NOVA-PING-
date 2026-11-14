package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEvidenceRegistryTest {
    private val now = 1_800_000_000_000L
    private fun evidence(
        sourceUrl: String = "https://example.org/server-info",
        expiresAt: Long = now + 86_400_000L
    ) = GameServerEvidence(
        gameId = "codm",
        region = "global",
        endpointHost = "match.example.org",
        protocol = "HTTPS",
        sourceUrl = sourceUrl,
        sourcePublishedAtEpochMs = now - 86_400_000L,
        checkedAtEpochMs = now - 1_000L,
        expiresAtEpochMs = expiresAt
    )

    @Test fun acceptsCurrentEvidenceWithHttpsProvenance() {
        assertTrue(GameEvidenceRegistry.isUsable(evidence(), now))
    }

    @Test fun rejectsExpiredEvidence() {
        assertFalse(GameEvidenceRegistry.isUsable(evidence(expiresAt = now), now))
    }

    @Test fun rejectsNonHttpsProvenance() {
        assertFalse(GameEvidenceRegistry.isUsable(evidence(sourceUrl = "http://example.org/info"), now))
    }

    @Test fun rejectsEvidenceCheckedBeforeItsSourceWasPublished() {
        val invalid = evidence().copy(sourcePublishedAtEpochMs = now, checkedAtEpochMs = now - 1L)
        assertFalse(GameEvidenceRegistry.isUsable(invalid, now))
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
