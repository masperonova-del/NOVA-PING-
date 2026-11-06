package com.novaping.app

enum class NetworkReadiness(val userMessage: String) {
    READY("Connexion Internet validée."),
    NO_NETWORK("Aucun réseau actif détecté par Android."),
    NO_INTERNET_CAPABILITY("Le réseau actif ne déclare pas de capacité Internet."),
    NOT_VALIDATED("Android ne confirme pas encore l’accès à Internet.");

    companion object {
        fun from(hasNetwork: Boolean, hasInternet: Boolean, validated: Boolean): NetworkReadiness = when {
            !hasNetwork -> NO_NETWORK
            !hasInternet -> NO_INTERNET_CAPABILITY
            !validated -> NOT_VALIDATED
            else -> READY
        }
    }
}
