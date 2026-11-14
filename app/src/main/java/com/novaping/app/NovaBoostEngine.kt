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
    val evidenceSummary: String,
    val limitations: List<String>,
    val routingAvailable: Boolean = false,
    val vpnConsentRequired: Boolean = false
)

object NovaBoostEngine {
    fun assess(
        context: NetworkContext,
        aggregates: List<RouteAggregate>,
        gameProfile: GameProfile
    ): BoostAssessment {
        val recommendations = DecisionEngine.recommend(context, aggregates)
        val evidence = assessEvidence(aggregates)
        val contextNeedsAction = !context.internetValidated
        val mode = if (evidence.isSufficient || contextNeedsAction) {
            BoostMode.RECOMMEND
        } else {
            BoostMode.OBSERVE
        }

        val summary = when {
            contextNeedsAction ->
                "Android ne valide pas l'accès Internet. NOVA BOOST recommande de rétablir la connexion avant toute comparaison."
            evidence.isSufficient ->
                "Les mesures disposent d'un niveau de preuve minimal sur plusieurs points de contrôle HTTPS. Les recommandations restent indicatives et ne modifient pas le routage."
            else ->
                "Mode observation : les mesures disponibles ne couvrent pas encore assez de points de contrôle avec des connexions complètes répétées pour formuler une recommandation réseau robuste."
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
            evidenceSummary = evidence.summary,
            limitations = limitations
        )
    }

    private fun assessEvidence(aggregates: List<RouteAggregate>): EvidenceAssessment {
        val groups = aggregates.filter { it.host.isNotBlank() }.groupBy { it.host }
        val qualifyingTargets = groups.values.count { group ->
            // Duplicate aggregates for one host are ambiguous and cannot increase confidence.
            group.size == 1 && group.single().let { aggregate ->
                aggregate.sampleCount == MeasurementPlan.ROUTE_SAMPLES_PER_TARGET &&
                    aggregate.completeSamples >= MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION &&
                    aggregate.dns.successfulSamples >= MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION &&
                    aggregate.tcp.successfulSamples >= MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION &&
                    aggregate.tls.successfulSamples >= MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION &&
                    aggregate.httpStatusLine.successfulSamples >= MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION
            }
        }
        val requiredTargets = MeasurementPlan.MIN_TARGETS_FOR_BOOST_RECOMMENDATION
        val summary = "Preuves exploitables : $qualifyingTargets/${groups.size} destinations. Seuil NOVA BOOST : $requiredTargets destinations avec au moins ${MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION}/${MeasurementPlan.ROUTE_SAMPLES_PER_TARGET} connexions HTTPS complètes chacune."
        return EvidenceAssessment(
            qualifyingTargets = qualifyingTargets,
            observedTargets = groups.size,
            isSufficient = qualifyingTargets >= requiredTargets,
            summary = summary
        )
    }

    private data class EvidenceAssessment(
        val qualifyingTargets: Int,
        val observedTargets: Int,
        val isSufficient: Boolean,
        val summary: String
    )
}
