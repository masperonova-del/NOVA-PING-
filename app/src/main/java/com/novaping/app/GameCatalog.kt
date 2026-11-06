package com.novaping.app

enum class ServerEvidence {
    NOT_VERIFIED,
    VERIFIED_PUBLIC_METADATA,
    VERIFIED_MEASURABLE_ENDPOINT
}

data class GameProfile(
    val id: String,
    val displayName: String,
    val serverEvidence: ServerEvidence,
    val note: String
)

object GameCatalog {
    val profiles = listOf(
        GameProfile(
            "codm", "Call of Duty: Mobile", ServerEvidence.NOT_VERIFIED,
            "Les destinations de match ne sont pas encore vérifiées. Aucun ping de serveur de partie n'est annoncé."
        ),
        GameProfile(
            "pubg", "PUBG Mobile", ServerEvidence.NOT_VERIFIED,
            "Les destinations de match ne sont pas encore vérifiées. Aucun ping de serveur de partie n'est annoncé."
        ),
        GameProfile(
            "freefire", "Free Fire", ServerEvidence.NOT_VERIFIED,
            "Les destinations de match ne sont pas encore vérifiées. Aucun ping de serveur de partie n'est annoncé."
        ),
        GameProfile(
            "other", "Autre jeu", ServerEvidence.NOT_VERIFIED,
            "Aucune destination spécifique au jeu n'est configurée."
        )
    )

    fun findByDisplayName(name: String): GameProfile =
        profiles.firstOrNull { it.displayName == name } ?: profiles.last()
}
