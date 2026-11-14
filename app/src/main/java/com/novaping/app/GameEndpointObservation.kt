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

    internal fun isPublicUnicastAddress(rawAddress: String): Boolean {
        val address = parseNumericAddress(rawAddress) ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            val first = bytes[0]
            val second = bytes[1]
            val third = bytes[2]

            // Exclude special-purpose, private, shared, documentation, benchmark,
            // multicast and reserved IPv4 ranges from candidate endpoint evidence.
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false // 100.64.0.0/10
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 0 && (third == 0 || third == 2)) return false
            if (first == 192 && second == 88 && third == 99) return false
            if (first == 192 && second == 168) return false
            if (first == 198 && second in 18..19) return false
            if (first == 198 && second == 51 && third == 100) return false
            if (first == 203 && second == 0 && third == 113) return false
            return true
        }

        if (address is Inet6Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            // Restrict candidates to global-unicast 2000::/3 and exclude
            // documentation (2001:db8::/32) and ORCHID ranges.
            if ((bytes[0] and 0xe0) != 0x20) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
                bytes[2] == 0x0d && bytes[3] == 0xb8
            ) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00 &&
                (bytes[3] in 0x10..0x1f || bytes[3] in 0x20..0x2f)
            ) return false
            return true
        }

        return false
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
