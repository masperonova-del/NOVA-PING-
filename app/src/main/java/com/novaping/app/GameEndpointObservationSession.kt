package com.novaping.app

import java.util.UUID

/**
 * Bounded, in-memory observations for one explicitly user-started, per-app VPN session.
 *
 * HEV receives only the selected application's TUN traffic because the VPN builder
 * allow-lists that package. The session never persists endpoint addresses and is
 * cleared when the tunnel stops. An observation is a candidate, never proof of a
 * match-server role.
 */
internal class GameEndpointObservationSession(
    private val gameId: String,
    private val gameAppPackage: String,
    private val userConsentGranted: Boolean,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val maxObservations: Int = DEFAULT_MAX_OBSERVATIONS
) {
    companion object {
        const val DEFAULT_MAX_OBSERVATIONS = 512
    }

    private val observations = ArrayDeque<GameEndpointObservation>()

    init {
        require(gameId.isNotBlank())
        require(gameAppPackage.isNotBlank())
        require(sessionId.isNotBlank())
        require(maxObservations in 1..DEFAULT_MAX_OBSERVATIONS)
    }

    @Synchronized
    fun record(metadata: HevPacketMetadata, observedAtEpochMs: Long): Boolean {
        if (!userConsentGranted || observedAtEpochMs <= 0L) return false
        val transport = when (metadata.transport) {
            6 -> GameEndpointTransport.TCP
            17 -> GameEndpointTransport.UDP
            else -> return false
        }
        val observation = GameEndpointObservation(
            gameId = gameId,
            gameAppPackage = gameAppPackage,
            sessionId = sessionId,
            remoteAddress = metadata.remoteAddress,
            remotePort = metadata.remotePort,
            transport = transport,
            observedAtEpochMs = observedAtEpochMs,
            userConsentGranted = userConsentGranted
        )
        if (!GameEndpointObservationRegistry.isAdmissible(observation, gameAppPackage)) {
            return false
        }
        if (observations.size == maxObservations) observations.removeFirst()
        observations.addLast(observation)
        return true
    }

    @Synchronized
    fun candidates(): List<GameEndpointCandidate> =
        GameEndpointObservationRegistry.candidatesForGame(
            observations.toList(), gameId, gameAppPackage
        )

    @Synchronized
    fun observationCount(): Int = observations.size

    /** Drops all endpoint addresses held by this session from memory. */
    @Synchronized
    fun clear() {
        observations.clear()
    }
}
