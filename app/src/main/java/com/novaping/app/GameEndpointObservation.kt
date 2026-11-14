package com.novaping.app

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Metadata-only observation of a remote socket associated with a user-selected game app.
 *
 * The capture layer must be explicit, user-consented and app-scoped. Packet payloads,
 * credentials and message contents are deliberately not represented by this model.
 * An observed remote socket is only a candidate; it is not proof of a match-server role.
 */
enum class GameEndpointTransport { TCP, UDP }

data class GameEndpointObservation(
    val gameId: String,
    val gameAppPackage: String,
    val sessionId: String,
    val remoteAddress: String,
    val remotePort: Int,
    val transport: GameEndpointTransport,
    val observedAtEpochMs: Long,
    val userConsentGranted: Boolean
)

data class GameEndpointCandidate(
    val gameId: String,
    val gameAppPackage: String,
    val remoteAddress: String,
    val remotePort: Int,
    val transport: GameEndpointTransport,
    val observationCount: Int,
    val distinctSessionCount: Int,
    val firstObservedAtEpochMs: Long,
    val lastObservedAtEpochMs: Long
) {
    /** Repetition increases confidence that the endpoint is game-related, not that it hosts a match. */
    val repeatedAcrossSessions: Boolean
        get() = distinctSessionCount >= 2 && observationCount >= 3

    val evidenceLabel: String
        get() = if (repeatedAcrossSessions) {
            "OBSERVÉ À PLUSIEURS SESSIONS — RÔLE SERVEUR NON CONFIRMÉ"
        } else {
            "OBSERVÉ — RÔLE SERVEUR NON CONFIRMÉ"
        }
}

object GameEndpointObservationRegistry {
    fun isAdmissible(observation: GameEndpointObservation, expectedPackage: String): Boolean {
        if (!observation.userConsentGranted) return false
        if (observation.gameId.isBlank() || expectedPackage.isBlank()) return false
        if (observation.gameAppPackage != expectedPackage) return false
        if (observation.sessionId.isBlank() || observation.observedAtEpochMs <= 0L) return false
        if (observation.remotePort !in 1..65535) return false
        return isPublicUnicastAddress(observation.remoteAddress)
    }

    fun candidatesForGame(
        observations: List<GameEndpointObservation>,
        gameId: String,
        expectedPackage: String
    ): List<GameEndpointCandidate> {
        if (gameId.isBlank() || expectedPackage.isBlank()) return emptyList()

        return observations
            .filter {
                it.gameId == gameId && isAdmissible(it, expectedPackage)
            }
            .groupBy {
                listOf(it.gameId, it.gameAppPackage, it.remoteAddress, it.remotePort, it.transport)
                    .joinToString("|")
            }
            .values
            .map { group ->
                val first = group.first()
                GameEndpointCandidate(
                    gameId = first.gameId,
                    gameAppPackage = first.gameAppPackage,
                    remoteAddress = first.remoteAddress,
                    remotePort = first.remotePort,
                    transport = first.transport,
                    observationCount = group.size,
                    distinctSessionCount = group.map { it.sessionId }.distinct().size,
                    firstObservedAtEpochMs = group.minOf { it.observedAtEpochMs },
                    lastObservedAtEpochMs = group.maxOf { it.observedAtEpochMs }
                )
            }
            .sortedWith(
                compareByDescending<GameEndpointCandidate> { it.distinctSessionCount }
                    .thenByDescending { it.observationCount }
                    .thenBy { it.remoteAddress }
            )
    }

    private fun isPublicUnicastAddress(rawAddress: String): Boolean {
        val address = parseNumericAddress(rawAddress) ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        if (address is Inet6Address) {
            val firstByte = address.address.first().toInt() and 0xff
            // Unique-local IPv6 (fc00::/7) is not a public Internet destination.
            if (firstByte == 0xfc || firstByte == 0xfd) return false
        }
        return address is Inet4Address || address is Inet6Address
    }

    /** Parses numeric literals only; this function never performs hostname DNS resolution. */
    private fun parseNumericAddress(rawAddress: String): InetAddress? {
        if (rawAddress.isBlank() || rawAddress.contains('%')) return null

        if (rawAddress.contains(':')) {
            if (!rawAddress.matches(Regex("[0-9a-fA-F:.]+"))) return null
            return runCatching { InetAddress.getByName(rawAddress) }
                .getOrNull()
                ?.takeIf { it is Inet6Address }
        }

        if (!rawAddress.matches(Regex("[0-9.]+"))) return null
        val octets = rawAddress.split(".")
        if (octets.size != 4) return null
        val bytes = octets.map { octet ->
            if (octet.isEmpty() || (octet.length > 1 && octet.startsWith("0"))) return null
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            value.toByte()
        }.toByteArray()
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }
}
