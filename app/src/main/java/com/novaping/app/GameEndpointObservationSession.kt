package com.novaping.app

import java.util.UUID

/**
 * Bounded, in-memory metadata observations for one selected-app VPN session.
 * Only packet headers are represented; payloads and application contents are excluded.
 */
internal class GameEndpointObservationSession(
    private val gameId: String,
    private val gameAppPackage: String,
    private val endpointObservationOptInGranted: Boolean,
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
        if (!endpointObservationOptInGranted || observedAtEpochMs <= 0L) return false
        val transport = when (metadata.transport) {
            6 -> GameEndpointTransport.TCP
            17 -> GameEndpointTransport.UDP
            else -> return false
        }
        val direction = when (metadata.direction) {
            1 -> GamePacketDirection.OUTBOUND
            2 -> GamePacketDirection.INBOUND
            else -> return false
        }
        val observation = GameEndpointObservation(
            gameId = gameId,
            gameAppPackage = gameAppPackage,
            sessionId = sessionId,
            remoteAddress = metadata.remoteAddress,
            remotePort = metadata.remotePort,
            transport = transport,
            direction = direction,
            packetLengthBytes = metadata.packetLengthBytes,
            observedAtEpochMs = observedAtEpochMs,
            endpointObservationOptInGranted = endpointObservationOptInGranted
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

    /** Drops all endpoint metadata held by this session from memory. */
    @Synchronized
    fun clear() {
        observations.clear()
    }
}
