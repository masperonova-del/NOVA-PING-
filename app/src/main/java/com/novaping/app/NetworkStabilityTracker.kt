package com.novaping.app

enum class NetworkStabilityEventType {
    WIFI_CONNECTED,
    WIFI_DISCONNECTED,
    WIFI_STATUS,
    INTERNET_LOST,
    INTERNET_RESTORED,
    INTERNET_STATUS,
    DEFAULT_NETWORK_CHANGED,
    PERIODIC_STATUS
}

data class NetworkStabilityEvent(
    val type: NetworkStabilityEventType,
    val timestampMillis: Long,
    val details: String
)

/**
 * Tracks Android network callbacks. State snapshots taken when foreground
 * monitoring resumes are not treated as timed transitions.
 */
class NetworkStabilityTracker {
    private var wifiAvailable: Boolean? = null
    private var wifiInternetValidated: Boolean? = null
    private var defaultTransport: String? = null
    private var wifiDisconnectedAt: Long? = null
    private var internetLostAt: Long? = null

    @Synchronized
    fun reset() {
        wifiAvailable = null
        wifiInternetValidated = null
        defaultTransport = null
        wifiDisconnectedAt = null
        internetLostAt = null
    }

    /**
     * Reconciles the current Android snapshot after monitoring was paused.
     * Any changes during the pause have unknown timestamps, so no recovery
     * duration is inferred from them.
     */
    @Synchronized
    fun synchronizeWifiState(
        available: Boolean,
        internetValidated: Boolean?,
        timestampMillis: Long
    ): List<NetworkStabilityEvent> {
        require(timestampMillis >= 0)
        val previousAvailable = wifiAvailable
        val previousValidated = wifiInternetValidated
        wifiAvailable = available
        wifiInternetValidated = if (available) internetValidated else null
        wifiDisconnectedAt = null
        internetLostAt = null

        val events = mutableListOf<NetworkStabilityEvent>()
        if (previousAvailable == null || previousAvailable != available) {
            val detail = when {
                previousAvailable == null && available ->
                    "État initial : réseau Wi-Fi disponible"
                previousAvailable == null ->
                    "État initial : aucun réseau Wi-Fi disponible"
                available ->
                    "Wi-Fi disponible à la reprise ; heure de retour inconnue"
                else ->
                    "Wi-Fi indisponible à la reprise ; heure de perte inconnue"
            }
            events += NetworkStabilityEvent(
                NetworkStabilityEventType.WIFI_STATUS, timestampMillis, detail
            )
        }
        if (available && internetValidated != null &&
            (previousAvailable == null || previousAvailable != available ||
                previousValidated != internetValidated)
        ) {
            events += NetworkStabilityEvent(
                NetworkStabilityEventType.INTERNET_STATUS,
                timestampMillis,
                if (internetValidated) "État Internet du Wi-Fi : validé par Android"
                else "État Internet du Wi-Fi : non validé par Android"
            )
        }
        return events
    }

    /** Records a periodic foreground snapshot without pretending it was a transition. */
    @Synchronized
    fun periodicSnapshot(
        wifiAvailable: Boolean,
        internetValidated: Boolean?,
        activeTransport: String,
        activeInternetAvailable: Boolean,
        activeInternetValidated: Boolean,
        timestampMillis: Long
    ): NetworkStabilityEvent {
        require(timestampMillis >= 0)
        require(activeTransport.isNotBlank())
        val activeDetails = when {
            activeTransport == "Aucun" -> "aucun réseau actif"
            activeInternetValidated -> "$activeTransport · Internet validé par Android"
            activeInternetAvailable -> "$activeTransport · Internet non validé par Android"
            else -> "$activeTransport · accès Internet non confirmé"
        }
        val wifiDetails = when {
            !wifiAvailable -> "Wi-Fi absent"
            internetValidated == true -> "Wi-Fi présent et validé"
            internetValidated == false -> "Wi-Fi présent mais non validé"
            else -> "état Internet du Wi-Fi inconnu"
        }
        return NetworkStabilityEvent(
            NetworkStabilityEventType.PERIODIC_STATUS,
            timestampMillis,
            "État périodique : réseau actif $activeDetails | $wifiDetails"
        )
    }

    @Synchronized
    fun synchronizeDefaultTransport(transport: String) {
        require(transport.isNotBlank())
        defaultTransport = transport
    }

    @Synchronized
    fun onWifiAvailabilityChanged(
        available: Boolean,
        timestampMillis: Long
    ): NetworkStabilityEvent? {
        require(timestampMillis >= 0)
        if (wifiAvailable == available) return null
        val wasAvailable = wifiAvailable
        wifiAvailable = available
        if (!available) {
            wifiDisconnectedAt = timestampMillis
            wifiInternetValidated = null
            internetLostAt = null
        }
        val recoveryStart = wifiDisconnectedAt
        val recoveryDuration = if (available && wasAvailable == false) {
            recoveryStart?.let { (timestampMillis - it).coerceAtLeast(0) }
        } else null
        if (available) wifiDisconnectedAt = null
        return NetworkStabilityEvent(
            type = if (available) NetworkStabilityEventType.WIFI_CONNECTED
                else NetworkStabilityEventType.WIFI_DISCONNECTED,
            timestampMillis = timestampMillis,
            details = when {
                !available -> "Réseau Wi-Fi perdu selon Android"
                recoveryDuration != null -> "Wi-Fi rétabli après ${recoveryDuration / 1000.0} s"
                wasAvailable == false -> "Wi-Fi de nouveau disponible ; durée de coupure inconnue"
                else -> "Réseau Wi-Fi détecté par Android"
            }
        )
    }

    @Synchronized
    fun onWifiInternetValidationChanged(
        validated: Boolean,
        timestampMillis: Long
    ): NetworkStabilityEvent? {
        require(timestampMillis >= 0)
        if (wifiAvailable != true || wifiInternetValidated == validated) return null
        val previous = wifiInternetValidated
        wifiInternetValidated = validated
        if (previous == null) {
            return NetworkStabilityEvent(
                NetworkStabilityEventType.INTERNET_STATUS,
                timestampMillis,
                if (validated) "Internet validé sur le Wi-Fi"
                else "Wi-Fi présent, mais Internet non validé par Android"
            )
        }
        val recoveryStart = internetLostAt
        val recoveryDuration = if (validated && previous == false) {
            recoveryStart?.let { (timestampMillis - it).coerceAtLeast(0) }
        } else null
        if (!validated) internetLostAt = timestampMillis else internetLostAt = null
        if (validated && previous == false && recoveryStart == null) {
            return NetworkStabilityEvent(
                NetworkStabilityEventType.INTERNET_STATUS,
                timestampMillis,
                "Internet validé sur le Wi-Fi ; durée de perte inconnue"
            )
        }
        return NetworkStabilityEvent(
            type = if (validated) NetworkStabilityEventType.INTERNET_RESTORED
                else NetworkStabilityEventType.INTERNET_LOST,
            timestampMillis = timestampMillis,
            details = when {
                !validated -> "Wi-Fi présent, mais Internet n'est plus validé par Android"
                recoveryDuration != null -> "Internet rétabli après ${recoveryDuration / 1000.0} s"
                else -> "Internet validé sur le Wi-Fi"
            }
        )
    }

    @Synchronized
    fun onDefaultTransportChanged(
        transport: String,
        timestampMillis: Long
    ): NetworkStabilityEvent? {
        require(timestampMillis >= 0)
        require(transport.isNotBlank())
        val previous = defaultTransport
        defaultTransport = transport
        if (previous == null || previous == transport) return null
        return NetworkStabilityEvent(
            NetworkStabilityEventType.DEFAULT_NETWORK_CHANGED,
            timestampMillis,
            "Réseau actif passé de $previous à $transport"
        )
    }
}
