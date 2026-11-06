package com.novaping.app

enum class RecommendationConfidence { LOW, MODERATE }

data class NetworkRecommendation(
    val title: String,
    val explanation: String,
    val action: String,
    val confidence: RecommendationConfidence
)

object DecisionEngine {
    fun recommend(
        context: NetworkContext,
        outcomes: List<RouteProbeOutcome>
    ): List<NetworkRecommendation> {
        val recommendations = mutableListOf<NetworkRecommendation>()

        if (!context.internetValidated) {
            recommendations += NetworkRecommendation(
                "Connexion non validée",
                "Android ne confirme pas un accès Internet fonctionnel sur le réseau actif.",
                "Vérifier le Wi-Fi ou les données mobiles, puis relancer les mesures.",
                RecommendationConfidence.MODERATE
            )
        }

        if (context.metered) {
            recommendations += NetworkRecommendation(
                "Réseau potentiellement limité",
                "Android signale que le réseau est mesuré. Cela ne signifie pas qu'il est lent.",
                "Surveiller la consommation de données avant de lancer des tests répétés.",
                RecommendationConfidence.MODERATE
            )
        }

        val dns = outcomes.mapNotNull { it.dnsMs }
        val tcp = outcomes.mapNotNull { it.tcpConnectMs }
        val tls = outcomes.mapNotNull { it.tlsHandshakeMs }
        val response = outcomes.mapNotNull { it.httpFirstByteMs }
        val failures = outcomes.mapNotNull { it.failure }

        if (RouteProbeFailure.DNS in failures) {
            recommendations += NetworkRecommendation(
                "Résolution DNS à vérifier",
                "Au moins une destination n'a pas pu être résolue pendant ce test.",
                "Répéter le test et comparer plusieurs destinations avant de modifier les paramètres DNS.",
                RecommendationConfidence.MODERATE
            )
        }
        if (RouteProbeFailure.TCP_CONNECT in failures || RouteProbeFailure.TIMEOUT in failures) {
            recommendations += NetworkRecommendation(
                "Connexion distante incomplète",
                "Une connexion TCP ou une phase réseau a expiré. Le test seul ne permet pas d'identifier l'opérateur ou le routeur responsable.",
                "Répéter les mesures sur plusieurs destinations et comparer Wi-Fi et données mobiles séparément.",
                RecommendationConfidence.LOW
            )
        }
        if (RouteProbeFailure.TLS_HANDSHAKE in failures) {
            recommendations += NetworkRecommendation(
                "Négociation TLS échouée",
                "La connexion sécurisée n'a pas abouti pour au moins une destination. Cela peut être propre au service ou à la négociation TLS.",
                "Ne pas interpréter cet échec comme une preuve de mauvaise qualité générale du réseau.",
                RecommendationConfidence.MODERATE
            )
        }
        if (dns.any { it >= 200 }) {
            recommendations += NetworkRecommendation(
                "Temps DNS élevé sur une destination",
                "Une résolution de nom a pris au moins 200 ms dans cet échantillon.",
                "Répéter plusieurs fois et comparer d'autres destinations avant toute modification DNS.",
                RecommendationConfidence.LOW
            )
        }
        if (tcp.any { it >= 300 }) {
            recommendations += NetworkRecommendation(
                "Établissement TCP lent",
                "Une connexion TCP a pris au moins 300 ms vers une destination de contrôle.",
                "Comparer plusieurs destinations et répéter le test dans des conditions identiques.",
                RecommendationConfidence.LOW
            )
        }
        if (tls.any { it >= 400 }) {
            recommendations += NetworkRecommendation(
                "Négociation TLS longue",
                "La négociation sécurisée a pris au moins 400 ms. Ce temps dépend aussi du serveur distant.",
                "Comparer plusieurs mesures avant d'attribuer le délai au réseau local.",
                RecommendationConfidence.LOW
            )
        }
        if (response.any { it >= 500 }) {
            recommendations += NetworkRecommendation(
                "Réponse distante lente",
                "Le premier en-tête HTTP a mis au moins 500 ms à arriver après la négociation TLS.",
                "Comparer d'autres services : le délai peut venir du serveur distant ou du chemin réseau.",
                RecommendationConfidence.LOW
            )
        }

        if (recommendations.isEmpty()) {
            recommendations += NetworkRecommendation(
                "Aucune anomalie majeure détectée sur ces points de contrôle",
                "Les phases mesurées n'ont pas dépassé les seuils indicatifs du diagnostic.",
                "Comparer avec d'autres sessions et, si possible, avec le réseau alternatif.",
                RecommendationConfidence.LOW
            )
        }
        return recommendations.distinctBy { it.title }
    }
}
