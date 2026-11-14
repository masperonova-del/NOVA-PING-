package com.novaping.app

import java.net.URI

/**
 * Publicly documented network-port information for a game.
 * A port reference is not a server address and does not prove a match-server role.
 */
data class PublishedGamePort(
    val protocol: String,
    val firstPort: Int,
    val lastPort: Int,
    val purpose: String
) {
    fun displayRange(): String =
        if (firstPort == lastPort) firstPort.toString() else "$firstPort-$lastPort"
}

data class GameNetworkReference(
    val gameId: String,
    val platform: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val checkedOn: String,
    val ports: List<PublishedGamePort>
)

object GameNetworkReferenceRegistry {
    private val references = listOf(
        GameNetworkReference(
            gameId = "codm",
            platform = "Android / iOS",
            sourceTitle = "Ports utilisés pour les jeux Call of Duty",
            sourceUrl = "https://support.activision.com/fr/articles/ports-used-for-call-of-duty-games",
            checkedOn = "2026-11-14",
            ports = listOf(
                PublishedGamePort("TCP", 80, 80, "Fonction non précisée dans la source"),
                PublishedGamePort("TCP", 443, 443, "Fonction non précisée dans la source"),
                PublishedGamePort("TCP", 65010, 65010, "Salon"),
                PublishedGamePort("TCP", 65050, 65050, "Chat"),
                PublishedGamePort("UDP", 7500, 8000, "Plage publiée ; fonction détaillée non précisée")
            )
        )
    )

    fun isAdmissible(reference: GameNetworkReference): Boolean {
        if (reference.gameId.isBlank() || reference.platform.isBlank() ||
            reference.sourceTitle.isBlank() || reference.checkedOn.isBlank() ||
            reference.ports.isEmpty()
        ) return false

        val source = runCatching { URI(reference.sourceUrl) }.getOrNull() ?: return false
        if (source.scheme != "https" || source.host != "support.activision.com") return false
        if (!reference.checkedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false

        return reference.ports.all { port ->
            port.protocol in setOf("TCP", "UDP") &&
                port.firstPort in 1..65535 &&
                port.lastPort in port.firstPort..65535 &&
                port.purpose.isNotBlank()
        }
    }

    fun forGame(gameId: String): List<GameNetworkReference> =
        references.filter { it.gameId == gameId && isAdmissible(it) }
}
