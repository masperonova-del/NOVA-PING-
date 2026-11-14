package com.novaping.app

import java.net.IDN
import java.net.URI

/**
 * A source-backed claim about a game's measurable server endpoint.
 * Documentary provenance is not proof that the endpoint is reachable from this device.
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
    private val supportedProtocols = setOf("TCP", "UDP", "ICMP")

    fun isUsable(evidence: GameServerEvidence, nowEpochMs: Long): Boolean {
        if (evidence.gameId.isBlank() || evidence.region.isBlank()) return false
        if (evidence.protocol.uppercase() !in supportedProtocols) return false
        if (!isValidEndpointHost(evidence.endpointHost)) return false

        val source = runCatching { URI(evidence.sourceUrl) }.getOrNull() ?: return false
        if (!source.scheme.equals("https", ignoreCase = true) ||
            source.host.isNullOrBlank() ||
            source.rawUserInfo != null ||
            source.port !in -1..65535
        ) return false

        if (evidence.sourcePublishedAtEpochMs <= 0L ||
            evidence.checkedAtEpochMs < evidence.sourcePublishedAtEpochMs ||
            evidence.expiresAtEpochMs <= evidence.checkedAtEpochMs
        ) return false

        return nowEpochMs in evidence.checkedAtEpochMs until evidence.expiresAtEpochMs
    }

    private fun isValidEndpointHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() }) return false
        if (host.startsWith(".") || host.endsWith(".") || host.contains("://") ||
            host.contains("/") || host.contains("@") || host.contains(":")
        ) return false

        // Accept a numeric IPv4 address without triggering DNS resolution.
        if (host.matches(Regex("[0-9.]+"))) {
            val octets = host.split(".")
            return octets.size == 4 && octets.all { octet ->
                octet.isNotEmpty() &&
                    (octet == "0" || !octet.startsWith("0")) &&
                    octet.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        val asciiHost = runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        }.getOrNull() ?: return false
        if (asciiHost.length > 253) return false
        val labels = asciiHost.split(".")
        return labels.size >= 2 && labels.all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    fun usableForGame(
        evidence: List<GameServerEvidence>,
        gameId: String,
        nowEpochMs: Long
    ): List<GameServerEvidence> = evidence.filter {
        it.gameId == gameId && isUsable(it, nowEpochMs)
    }
}
