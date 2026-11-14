package com.novaping.app

/**
 * NOVA BOOST recommendation layer.
 *
 * Advisory only: consumes NOVA PING evidence and never changes Android
 * routing or starts a VPN session.
 */
enum class BoostMode { OBSERVE, RECOMMEND, READY, ACTIVE, FAILED, STOPPED }

data class BoostAssessment(
    val gameProfile: GameProfile,
    val mode: BoostMode,
    val recommendations: List<NetworkRecommendation>,
    val summary: String,
    val limitations: List<String>,
    val routingAvailable: Boolean = false,
    val vpnConsentRequired: Boolean = false
)

object NovaBoostEngine {
    private const val MIN_QUALIFIED_TARGETS_FOR_RECOMMENDATION = 2

    fun assess(
        context: NetworkContext,
        aggregates: List<RouteAggregate>,
        gameProfile: GameProfile
    ): BoostAssessment {
        val recommendations = DecisionEngine.recommend(context, aggregates)
        val hasMeasurements = aggregates.any { it.sampleCount > 0 }
        val qualifiedTargets = aggregates
            .filter {
                it.sampleCount == MeasurementPlan.ROUTE_SAMPLES_PER_TARGET &&
                    it.completeSamples >= MeasurementPlan.MIN_COMPLETE_ROUTE_SAMPLES_FOR_CLEAN_RESULT
            }
            .map { it.host.lowercase() }
            .distinct()
            .size
        val hasRecommendationEvidence =
            qualifiedTargets >= MIN_QUALIFIED_TARGETS_FOR_RECOMMENDATION
        val mode = if (hasRecommendationEvidence) BoostMode.RECOMMEND else BoostMode.OBSERVE

        val summary = when {
            !hasMeasurements ->
                "NOVA BOOST observe le contexte réseau. Lance un diagnostic complet pour obtenir des recommandations fondées sur des mesures."
            !hasRecommendationEvidence ->
                "Des mesures existent, mais elles ne sont pas encore suffisantes : au moins deux destinations doivent fournir chacune cinq essais, dont quatre connexions HTTPS complètes. NOVA BOOST reste en observation."
            recommendations.any { it.title == "Données insuffisantes" } ->
                "Les mesures disponibles sont insuffisantes pour conclure. Recommencer le diagnostic avant toute optimisation."
            else ->
                "Les recommandations NOVA BOOST sont calculées à partir de mesures suffisamment complètes. Aucune modification du routage n'a été appliquée."
        }

        val limitations = buildList {
            add("Les points de contrôle HTTPS ne représentent pas le serveur de partie.")
            add("Le ping réel de " + gameProfile.displayName + " n'est pas vérifié par ces mesures.")
            if (gameProfile.serverEvidence != ServerEvidence.VERIFIED_MEASURABLE_ENDPOINT) {
                add("Aucune destination de match vérifiée n'est disponible pour ce profil.")
            }
            add("Le routage VPN et les relais NOVA ne sont pas implémentés dans cette version.")
        }

        return BoostAssessment(
            gameProfile = gameProfile,
            mode = mode,
            recommendations = recommendations,
            summary = summary,
            limitations = limitations
        )
    }
}