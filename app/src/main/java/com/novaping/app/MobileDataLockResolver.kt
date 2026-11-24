package com.novaping.app

enum class MobileDataLockState {
    DISABLED,
    MOBILE_ACTIVE_VALIDATED,
    MOBILE_ACTIVE_UNVALIDATED,
    WIFI_ACTIVE_MOBILE_AVAILABLE,
    WIFI_ACTIVE_MOBILE_UNAVAILABLE,
    VPN_ACTIVE,
    OTHER_NETWORK_ACTIVE,
    NO_NETWORK
}

data class MobileDataLockSnapshot(
    val state: MobileDataLockState,
    val details: String
)

/**
 * Resolves what Android reports. This is an observer/preference layer only:
 * it never attempts to change the system's default network.
 */
object MobileDataLockResolver {
    fun resolve(
        enabled: Boolean,
        activeTransport: String?,
        activeInternetValidated: Boolean?,
        mobileNetworkAvailable: Boolean,
        mobileInternetValidated: Boolean?
    ): MobileDataLockSnapshot {
        if (!enabled) {
            return MobileDataLockSnapshot(
                MobileDataLockState.DISABLED,
                "Mobile Data Lock désactivé."
            )
        }

        return when (activeTransport) {
            "Données mobiles" -> {
                if (activeInternetValidated == true) {
                    MobileDataLockSnapshot(
                        MobileDataLockState.MOBILE_ACTIVE_VALIDATED,
                        "Données mobiles utilisées par Android ; Internet validé."
                    )
                } else {
                    MobileDataLockSnapshot(
                        MobileDataLockState.MOBILE_ACTIVE_UNVALIDATED,
                        "Données mobiles utilisées par Android, mais Internet n'est pas validé."
                    )
                }
            }
            "Wi-Fi" -> {
                if (mobileNetworkAvailable) {
                    val mobileState = when (mobileInternetValidated) {
                        true -> "Les données mobiles sont disponibles et Internet est validé."
                        false -> "Les données mobiles sont détectées, mais Internet n'est pas validé."
                        null -> "La validation Internet des données mobiles est inconnue."
                    }
                    MobileDataLockSnapshot(
                        MobileDataLockState.WIFI_ACTIVE_MOBILE_AVAILABLE,
                        "Android utilise le Wi-Fi au lieu des données mobiles. $mobileState Désactive le Wi-Fi dans les paramètres Android pour repasser au mobile."
                    )
                } else {
                    MobileDataLockSnapshot(
                        MobileDataLockState.WIFI_ACTIVE_MOBILE_UNAVAILABLE,
                        "Android utilise le Wi-Fi ; aucun réseau mobile disponible n'est détecté."
                    )
                }
            }
            "VPN" -> MobileDataLockSnapshot(
                MobileDataLockState.VPN_ACTIVE,
                "Un VPN est le réseau actif. Le transport physique sous-jacent ne peut pas être confirmé ici."
            )
            "Aucun", null -> MobileDataLockSnapshot(
                MobileDataLockState.NO_NETWORK,
                "Aucun réseau actif n'est détecté par Android."
            )
            else -> MobileDataLockSnapshot(
                MobileDataLockState.OTHER_NETWORK_ACTIVE,
                "Android utilise un autre type de réseau : $activeTransport."
            )
        }
    }
}
