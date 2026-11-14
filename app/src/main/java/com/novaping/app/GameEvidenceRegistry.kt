package com.novaping.app

/**
 * A source-backed claim about a game's measurable server endpoint.
 * This model deliberately stores evidence metadata separately from HTTPS control probes.
 */
data class GameServerEvidence(
    val gameId: String,
    val region: String,
    val endpointHost: String,
    val protocol: String,
    val sourceUrl: String,
    val sourcePublishedAtEpochMs: Long,
    val checkedAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

object GameEvidenceRegistry {
    fun isUsable(evidence: GameServerEvidence, nowEpochMs: Long): Boolean {
        if (evidence.gameId.isBlank() || evidence.region.isBlank() ||
            evidence.endpointHost.isBlank() || evidence.protocol.isBlank()
        ) return false
        if (!evidence.endpointHost.contains('.') || evidence.endpointHost.startsWith('.') ||
            evidence.endpointHost.endsWith('.')
        ) return false
        val source = runCatching { java.net.URI(evidence.sourceUrl) }.getOrNull() ?: return false
        if (source.scheme != "https" || source.host.isNullOrBlank()) return false
        if (evidence.sourcePublishedAtEpochMs <= 0L ||
            evidence.checkedAtEpochMs < evidence.sourcePublishedAtEpochMs ||
            evidence.expiresAtEpochMs <= evidence.checkedAtEpochMs
        ) return false
        return nowEpochMs in evidence.checkedAtEpochMs until evidence.expiresAtEpochMs
    }

    fun usableForGame(
        evidence: List<GameServerEvidence>,
        gameId: String,
        nowEpochMs: Long
    ): List<GameServerEvidence> = evidence.filter {
        it.gameId == gameId && isUsable(it, nowEpochMs)
    }
}
