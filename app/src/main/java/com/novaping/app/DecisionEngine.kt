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
        aggregates: List<RouteAggregate>
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

        val repeatedFailures = aggregates.flatMap { aggregate ->
            aggregate.failures.filterValues { it >= 2 }.keys
        }.toSet()

        if (RouteProbeFailure.DNS in repeatedFailures) {
            recommendations += recommendation(
                "Résolution DNS à vérifier",
                "Au moins deux essais n'ont pas résolu une destination.",
                "Répéter le test et comparer plusieurs destinations avant de modifier les paramètres DNS."
            )
        }
        if (RouteProbeFailure.TCP_CONNECT in repeatedFailures || RouteProbeFailure.TIMEOUT in repeatedFailures) {
            recommendations += recommendation(
                "Connexion distante incomplète",
                "Au moins deux essais ont échoué pendant une connexion ou ont expiré. Le test ne permet pas d'identifier le responsable.",
                "Comparer plusieurs destinations et répéter le test dans les mêmes conditions."
            )
        }
        if (RouteProbeFailure.TLS_HANDSHAKE in repeatedFailures) {
            recommendations += recommendation(
                "Négociation TLS à vérifier",
                "Au moins deux négociations TLS ont échoué pour une destination. Le problème peut être propre au service.",
                "Comparer d'autres destinations avant de conclure à un problème général du réseau."
            )
        }
        if (RouteProbeFailure.HTTP_RESPONSE in repeatedFailures || RouteProbeFailure.UNKNOWN in repeatedFailures) {
            recommendations += recommendation(
                "Réponse incomplète",
                "Au moins deux essais n'ont pas fourni une réponse HTTP exploitable.",
                "Relancer l'analyse et comparer plusieurs services."
            )
        }

        val non2xx = aggregates.any { aggregate ->
            aggregate.httpStatuses.filterKeys { it !in 200..299 }.values.sum() >= 2
        }
        if (non2xx) {
            recommendations += recommendation(
                "Statut HTTP à examiner",
                "Au moins deux réponses ont un statut hors de la plage 2xx attendue.",
                "Vérifier le comportement du service avant d'interpréter ces réponses."
            )
        }

        aggregates.forEach { aggregate ->
            if (aggregate.dns.successfulSamples >= 2 && (aggregate.dns.medianMs ?: 0) >= 200) {
                recommendations += recommendation(
                    "Résolution DNS lente sur un point de contrôle",
                    "La médiane DNS de ${aggregate.host} atteint au moins 200 ms sur plusieurs essais.",
                    "Comparer d'autres destinations et répéter avant toute modification DNS."
                )
            }
            if (aggregate.tcp.successfulSamples >= 2 && (aggregate.tcp.medianMs ?: 0) >= 300) {
                recommendations += recommendation(
                    "Établissement TCP lent sur un point de contrôle",
                    "La médiane TCP de ${aggregate.host} atteint au moins 300 ms sur plusieurs essais.",
                    "Comparer plusieurs destinations et répéter dans les mêmes conditions."
                )
            }
            if (aggregate.tls.successfulSamples >= 2 && (aggregate.tls.medianMs ?: 0) >= 400) {
                recommendations += recommendation(
                    "Négociation TLS longue sur un point de contrôle",
                    "La médiane TLS de ${aggregate.host} atteint au moins 400 ms. Le serveur distant influence aussi ce délai.",
                    "Comparer plusieurs mesures avant d'attribuer le délai au réseau local."
                )
            }
            if (aggregate.httpStatusLine.successfulSamples >= 2 &&
                (aggregate.httpStatusLine.medianMs ?: 0) >= 500) {
                recommendations += recommendation(
                    "Réponse HTTP lente sur un point de contrôle",
                    "La médiane jusqu'à la ligne de statut HTTP de ${aggregate.host} atteint au moins 500 ms après TLS.",
                    "Comparer d'autres services : le délai peut venir du serveur distant ou du chemin réseau."
                )
            }
        }

        val allHaveRepeatedSuccessfulResponses = aggregates.isNotEmpty() && aggregates.all {
            it.completeSamples >= 2 &&
                it.httpStatuses.filterKeys { code -> code in 200..299 }.values.sum() >= 2 &&
                it.httpStatuses.filterKeys { code -> code !in 200..299 }.values.sum() == 0
        }
        if (recommendations.isEmpty() && allHaveRepeatedSuccessfulResponses) {
            recommendations += NetworkRecommendation(
                "Aucune anomalie majeure détectée sur ces points de contrôle",
                "Au moins deux connexions HTTPS complètes ont abouti par destination, sans dépassement des seuils indicatifs.",
                "Comparer avec d'autres sessions et, si possible, avec un autre type de réseau.",
                RecommendationConfidence.LOW
            )
        }
        if (recommendations.isEmpty()) {
            recommendations += NetworkRecommendation(
                "Données insuffisantes",
                "Les essais ne sont pas assez nombreux ou complets pour formuler une recommandation fiable.",
                "Répéter l'analyse avant de tirer une conclusion.",
                RecommendationConfidence.LOW
            )
        }
        return recommendations.distinctBy { it.title }
    }

    private fun recommendation(title: String, explanation: String, action: String) =
        NetworkRecommendation(title, explanation, action, RecommendationConfidence.LOW)
}
