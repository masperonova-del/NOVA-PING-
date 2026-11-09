package com.novaping.app

/**
 * Immutable view of the active default network at one point in a diagnostic.
 * The network id is Android's Network identity, not a server or route identifier.
 */
data class NetworkObservation(
    val networkId: String?,
    val transport: NetworkTransport,
    val internetAvailable: Boolean,
    val validated: Boolean
) {
    val isReady: Boolean
        get() = !networkId.isNullOrBlank() && internetAvailable && validated

    fun displayName(): String = when (transport) {
        NetworkTransport.WIFI -> "Wi-Fi"
        NetworkTransport.CELLULAR -> "Données mobiles"
        NetworkTransport.ETHERNET -> "Ethernet"
        NetworkTransport.VPN -> "VPN"
        NetworkTransport.OTHER -> "Autre réseau"
        NetworkTransport.NONE -> "Aucun réseau"
    }

    /**
     * Returns a reason when a measurement can no longer be attributed to the
     * same validated default network as the session start.
     */
    fun issueComparedTo(current: NetworkObservation): NetworkSessionIssue? {
        if (!current.isReady) return NetworkSessionIssue.INTERNET_NOT_VALIDATED
        if (!isReady || networkId != current.networkId || transport != current.transport) {
            return NetworkSessionIssue.NETWORK_CHANGED
        }
        return null
    }
}

enum class NetworkSessionIssue(val userMessage: String) {
    INTERNET_NOT_VALIDATED("La connexion Internet n’est plus validée par Android. Le diagnostic a été arrêté."),
    NETWORK_CHANGED("Le réseau actif a changé pendant le diagnostic. Les résultats ne peuvent pas être classés ensemble.")
}

data class ProbeAttemptTrace(
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val networkBefore: NetworkObservation,
    val networkAfter: NetworkObservation
) {
    val networkStable: Boolean
        get() = networkBefore.issueComparedTo(networkAfter) == null
}
