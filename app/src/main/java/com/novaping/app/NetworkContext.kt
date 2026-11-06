package com.novaping.app

enum class NetworkTransport {
    WIFI, CELLULAR, ETHERNET, VPN, OTHER, NONE
}

data class NetworkContext(
    val transport: NetworkTransport,
    val internetAvailable: Boolean,
    val internetValidated: Boolean,
    val metered: Boolean,
    val roaming: Boolean,
    val downstreamKbps: Int?,
    val upstreamKbps: Int?
) {
    val isMobile: Boolean get() = transport == NetworkTransport.CELLULAR
    val isWifi: Boolean get() = transport == NetworkTransport.WIFI

    fun summary(): String = buildList {
        add("Réseau : ${transport.label()}")
        add("Internet validé : ${if (internetValidated) "oui" else "non"}")
        add("Réseau mesuré par Android : ${if (metered) "oui" else "non"}")
        if (transport == NetworkTransport.CELLULAR) {
            add("Itinérance : ${if (roaming) "oui" else "non"}")
        }
        downstreamKbps?.takeIf { it > 0 }?.let { add("Bande passante descendante annoncée : $it kbit/s") }
        upstreamKbps?.takeIf { it > 0 }?.let { add("Bande passante montante annoncée : $it kbit/s") }
    }.joinToString("\n")

    companion object {
        fun from(
            transport: NetworkTransport,
            hasInternet: Boolean,
            validated: Boolean,
            metered: Boolean,
            roaming: Boolean,
            downstreamKbps: Int?,
            upstreamKbps: Int?
        ) = NetworkContext(
            transport, hasInternet, validated, metered, roaming,
            downstreamKbps, upstreamKbps
        )
    }
}

private fun NetworkTransport.label(): String = when (this) {
    NetworkTransport.WIFI -> "Wi-Fi"
    NetworkTransport.CELLULAR -> "Données mobiles"
    NetworkTransport.ETHERNET -> "Ethernet"
    NetworkTransport.VPN -> "VPN"
    NetworkTransport.OTHER -> "Autre"
    NetworkTransport.NONE -> "Aucun"
}
