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

        val allHaveRepeatedSuccessfulResponses = aggregates.size == 3 && aggregates.all {
            it.sampleCount == MeasurementPlan.ROUTE_SAMPLES_PER_TARGET &&
                it.completeSamples >= MeasurementPlan.MIN_COMPLETE_ROUTE_SAMPLES_FOR_CLEAN_RESULT && it.failures.isEmpty() &&
                it.httpStatuses.filterKeys { code -> code in 200..299 }.values.sum() >= MeasurementPlan.MIN_COMPLETE_ROUTE_SAMPLES_FOR_CLEAN_RESULT &&
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
        addRouteComparisonRecommendations(aggregates, recommendations)

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


    /**
     * Compares only complete five-sample batches. These are public HTTPS control
     * endpoints, never game match servers. Phase-median sums are indicative and
     * are not an end-to-end latency measurement.
     */
    private fun addRouteComparisonRecommendations(
        aggregates: List<RouteAggregate>,
        recommendations: MutableList<NetworkRecommendation>
    ) {
        val comparable = aggregates.filter {
            it.sampleCount == MeasurementPlan.ROUTE_SAMPLES_PER_TARGET
        }
        if (comparable.size < 2) return

        val byReliability = comparable.sortedWith(
            compareByDescending<RouteAggregate> { it.completeSamples }
                .thenBy { it.failures.values.sum() }
        )
        val best = byReliability[0]
        val second = byReliability[1]
        when {
            best.completeSamples < 2 -> recommendations += NetworkRecommendation(
                "Données insuffisantes pour classer la fiabilité",
                "Aucun point de contrôle n'a obtenu au moins deux connexions HTTPS complètes sur cinq essais.",
                "Répéter l'analyse dans les mêmes conditions. Ne pas choisir une destination sur la base de ces seuls résultats.",
                RecommendationConfidence.LOW
            )
            best.completeSamples > second.completeSamples -> {
                recommendations += NetworkRecommendation(
                    "Point de contrôle le plus fiable dans cet échantillon",
                    "${best.host} a obtenu ${best.completeSamples}/${best.sampleCount} connexions HTTPS complètes, contre ${second.completeSamples}/${second.sampleCount} pour ${second.host}. Ce classement concerne uniquement ces points de contrôle.",
                    "Répéter le test sur plusieurs sessions et réseaux. Cela ne désigne pas le meilleur serveur CODM.",
                    RecommendationConfidence.LOW
                )
            }
            else -> recommendations += NetworkRecommendation(
                "Aucun point de contrôle ne se détache en fiabilité",
                "Les résultats de connexions complètes sont trop proches pour désigner un gagnant fiable parmi ces destinations.",
                "Répéter les mesures à plusieurs moments avant de comparer les destinations.",
                RecommendationConfidence.LOW
            )
        }

        fun phaseMedianSum(aggregate: RouteAggregate): Long? {
            val phases = listOf(aggregate.dns, aggregate.tcp, aggregate.tls, aggregate.httpStatusLine)
            if (phases.any { it.successfulSamples < 3 || it.medianMs == null }) return null
            return phases.sumOf { it.medianMs!! }
        }

        val latencyCandidates = comparable.mapNotNull { aggregate ->
            phaseMedianSum(aggregate)?.let { aggregate to it }
        }.sortedBy { it.second }

        if (latencyCandidates.size < 2) {
            recommendations += NetworkRecommendation(
                "Comparaison des délais de phase non concluante",
                "Moins de deux destinations disposent d'au moins trois mesures réussies pour chacune des quatre phases DNS, TCP, TLS et HTTP.",
                "Répéter l'analyse. Aucune destination ne peut être déclarée la plus rapide sur la base des phases incomplètes.",
                RecommendationConfidence.LOW
            )
        } else {
            val fastest = latencyCandidates[0]
            val runnerUp = latencyCandidates[1]
            if (fastest.second < runnerUp.second) {
                recommendations += NetworkRecommendation(
                    "Phases cumulées les plus courtes (indicatif)",
                    "${fastest.first.host} présente une somme des médianes DNS + TCP + TLS + HTTP de ${fastest.second} ms, contre ${runnerUp.second} ms pour ${runnerUp.first.host}. Cette somme n'est pas une mesure directe du temps total de connexion.",
                    "Confirmer sur plusieurs sessions. Ce résultat ne mesure pas la latence d'un serveur de jeu.",
                    RecommendationConfidence.LOW
                )
            } else {
                recommendations += NetworkRecommendation(
                    "Aucun gagnant clair sur les délais de phase",
                    "Les sommes des médianes de phase ne permettent pas de distinguer clairement les destinations.",
                    "Répéter les mesures avant de conclure.",
                    RecommendationConfidence.LOW
                )
            }
        }
    }

    private fun recommendation(title: String, explanation: String, action: String) =
        NetworkRecommendation(title, explanation, action, RecommendationConfidence.LOW)
}
