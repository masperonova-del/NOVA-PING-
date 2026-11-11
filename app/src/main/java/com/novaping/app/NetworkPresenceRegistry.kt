package com.novaping.app

/**
 * Aggregates availability and Android Internet validation across every network
 * matching one transport. A late onLost callback for one Network must not mark
 * the whole transport disconnected while another matching Network still exists.
 */
data class NetworkPresenceSnapshot(
    val available: Boolean,
    val internetValidated: Boolean?
)

class NetworkPresenceRegistry {
    private val validatedByNetwork = linkedMapOf<String, Boolean?>()

    @Synchronized
    fun synchronize(networks: Map<String, Boolean>): NetworkPresenceSnapshot {
        validatedByNetwork.clear()
        networks.forEach { (id, validated) ->
            require(id.isNotBlank())
            validatedByNetwork[id] = validated
        }
        return snapshotLocked()
    }

    @Synchronized
    fun onAvailable(networkId: String): NetworkPresenceSnapshot {
        require(networkId.isNotBlank())
        validatedByNetwork.putIfAbsent(networkId, null)
        return snapshotLocked()
    }

    @Synchronized
    fun onCapabilitiesChanged(
        networkId: String,
        internetValidated: Boolean
    ): NetworkPresenceSnapshot {
        require(networkId.isNotBlank())
        validatedByNetwork[networkId] = internetValidated
        return snapshotLocked()
    }

    @Synchronized
    fun onLost(networkId: String): NetworkPresenceSnapshot {
        validatedByNetwork.remove(networkId)
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): NetworkPresenceSnapshot = snapshotLocked()

    private fun snapshotLocked(): NetworkPresenceSnapshot {
        val values = validatedByNetwork.values
        val validation = when {
            values.isEmpty() -> null
            values.any { it == true } -> true
            values.any { it == null } -> null
            else -> false
        }
        return NetworkPresenceSnapshot(
            available = validatedByNetwork.isNotEmpty(),
            internetValidated = validation
        )
    }
}
