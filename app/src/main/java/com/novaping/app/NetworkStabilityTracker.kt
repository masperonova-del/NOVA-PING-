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
    private var wifiAvailable: Boolean? = null
    private var wifiInternetValidated: Boolean? = null
    private var defaultTransport: String? = null

    @Synchronized
    fun onWifiAvailabilityChanged(available: Boolean, timestampMillis: Long): NetworkStabilityEvent? {
        require(timestampMillis >= 0)
        if (wifiAvailable == available) return null
        wifiAvailable = available
        if (!available) wifiInternetValidated = null
        return NetworkStabilityEvent(
            type = if (available) NetworkStabilityEventType.WIFI_CONNECTED
                else NetworkStabilityEventType.WIFI_DISCONNECTED,
            timestampMillis = timestampMillis,
            details = if (available) "Réseau Wi-Fi détecté par Android"
                else "Réseau Wi-Fi perdu selon Android"
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
        return NetworkStabilityEvent(
            type = if (validated) NetworkStabilityEventType.INTERNET_RESTORED
                else NetworkStabilityEventType.INTERNET_LOST,
            timestampMillis = timestampMillis,
            details = if (validated) "Internet à nouveau validé sur le Wi-Fi"
                else "Wi-Fi présent, mais Internet n'est plus validé par Android"
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
