package com.novaping.app

enum class NetworkStabilityEventType {
    WIFI_CONNECTED,
    WIFI_DISCONNECTED,
    INTERNET_LOST,
    INTERNET_RESTORED,
    DEFAULT_NETWORK_CHANGED
}

data class NetworkStabilityEvent(
    val type: NetworkStabilityEventType,
    val timestampMillis: Long,
    val details: String
)

/**
 * Small state tracker fed by Android ConnectivityManager callbacks.
 * It records observed transitions; it does not infer game-server latency.
 */
class NetworkStabilityTracker {
    @Synchronized
    fun reset() {
        wifiAvailable = null
        wifiInternetValidated = null
        defaultTransport = null
        wifiDisconnectedAt = null
        internetLostAt = null
    }

    private var wifiAvailable: Boolean? = null
    private var wifiInternetValidated: Boolean? = null
    private var defaultTransport: String? = null
    private var wifiDisconnectedAt: Long? = null
    private var internetLostAt: Long? = null

    @Synchronized
    fun onWifiAvailabilityChanged(available: Boolean, timestampMillis: Long): NetworkStabilityEvent? {
        require(timestampMillis >= 0)
        if (wifiAvailable == available) return null
        val wasAvailable = wifiAvailable
        wifiAvailable = available
        if (!available) {
            wifiDisconnectedAt = timestampMillis
            wifiInternetValidated = null
            internetLostAt = null
        }
        val recoveryDuration = if (available && wasAvailable == false) {
            wifiDisconnectedAt?.let { (timestampMillis - it).coerceAtLeast(0) }
        } else null
        if (available) wifiDisconnectedAt = null
        return NetworkStabilityEvent(
            type = if (available) NetworkStabilityEventType.WIFI_CONNECTED
                else NetworkStabilityEventType.WIFI_DISCONNECTED,
            timestampMillis = timestampMillis,
            details = when {
                !available -> "Réseau Wi-Fi perdu selon Android"
                recoveryDuration != null -> "Wi-Fi rétabli après ${recoveryDuration / 1000.0} s"
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
        if (previous == null) return null
        val recoveryStart = internetLostAt
        val recoveryDuration = if (validated && previous == false) {
            recoveryStart?.let { (timestampMillis - it).coerceAtLeast(0) }
        } else null
        if (!validated) internetLostAt = timestampMillis else internetLostAt = null
        if (validated && previous == false && recoveryStart == null) return null
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
            type = NetworkStabilityEventType.DEFAULT_NETWORK_CHANGED,
            timestampMillis = timestampMillis,
            details = "Réseau actif passé de $previous à $transport"
        )
    }
}
