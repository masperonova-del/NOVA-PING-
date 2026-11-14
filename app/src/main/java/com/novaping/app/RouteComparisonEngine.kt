package com.novaping.app

data class RouteComparisonBatch(
    val label: String,
    val transport: NetworkTransport,
    val vpnLabel: String?,
    val aggregates: List<RouteAggregate>
)

enum class RouteComparisonVerdict { INSUFFICIENT, INCONCLUSIVE, PROMISING, REGRESSION }

data class RouteComparisonResult(
    val verdict: RouteComparisonVerdict,
    val validPairs: Int,
    val summary: String,
    val limitations: List<String>
)

/**
 * Compares repeated HTTPS control-endpoint batches. It never estimates game ping
 * and never changes Android routing.
 */
object RouteComparisonEngine {
    private const val REQUIRED_PAIRS = 3
    private const val REQUIRED_SAMPLES_PER_HOST = MeasurementPlan.ROUTE_SAMPLES_PER_TARGET
    private const val MIN_COMPLETE_SAMPLES = MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION

    fun compare(
        directBatches: List<RouteComparisonBatch>,
        candidateBatches: List<RouteComparisonBatch>
    ): RouteComparisonResult {
        val limitations = listOf(
            "Les destinations sont des points de contrôle HTTPS, pas les serveurs de match.",
            "La somme des phases DNS, TCP, TLS et HTTP est indicative, pas un RTT de bout en bout.",
            "Une amélioration HTTPS ne prouve pas une réduction du ping dans CODM.",
            "Aucun routage n'est modifié et aucun VPN n'est activé."
        )
        if (directBatches.size != candidateBatches.size ||
            directBatches.size < REQUIRED_PAIRS
        ) {
            return insufficient(0, limitations, "Les deux séries doivent contenir le même nombre de lots et au moins $REQUIRED_PAIRS paires.")
        }

        val directIdentity = directBatches.map { it.label }.distinct()
        val candidateIdentity = candidateBatches.map { it.label to it.vpnLabel }.distinct()
        if (directIdentity.size != 1 || candidateIdentity.size != 1) {
            return insufficient(0, limitations, "Le profil direct ou le profil candidat change entre les séries.")
        }

        val paired = directBatches.zip(candidateBatches).filter { (direct, candidate) ->
            direct.transport == candidate.transport &&
                isValidBatch(direct) &&
                isValidBatch(candidate) &&
                direct.aggregates.map { it.host }.toSet() ==
                    candidate.aggregates.map { it.host }.toSet()
        }
        if (paired.size < REQUIRED_PAIRS) {
            return insufficient(
                paired.size, limitations,
                "Comparaison insuffisante : ${paired.size}/$REQUIRED_PAIRS séries appariées avec des lots complets et comparables."
            )
        }

        var improvedPairs = 0
        var regressedPairs = 0
        var comparablePairs = 0
        paired.forEach { (direct, candidate) ->
            val directByHost = direct.aggregates.associateBy { it.host }
            val candidateByHost = candidate.aggregates.associateBy { it.host }
            val comparisons = directByHost.keys.mapNotNull { host ->
                val baseline = directByHost[host] ?: return@mapNotNull null
                val trial = candidateByHost[host] ?: return@mapNotNull null
                val baseScore = phaseScore(baseline) ?: return@mapNotNull null
                val trialScore = phaseScore(trial) ?: return@mapNotNull null
                Triple(baseline, trial, baseScore to trialScore)
            }
            if (comparisons.isEmpty()) return@forEach

            val reliabilityPreserved = comparisons.all { (base, trial, _) ->
                trial.completeSamples >= base.completeSamples &&
                    trial.httpSuccessfulSamples >= base.httpSuccessfulSamples
            }
            val better = comparisons.count { (_, _, scores) ->
                scores.second.first < scores.first.first &&
                    scores.second.second <= scores.first.second
            }
            val worse = comparisons.count { (_, _, scores) ->
                scores.second.first > scores.first.first ||
                    scores.second.second > scores.first.second
            }
            comparablePairs++
            if (reliabilityPreserved && better > comparisons.size / 2) improvedPairs++
            if (!reliabilityPreserved || worse > comparisons.size / 2) regressedPairs++
        }

        val verdict = when {
            comparablePairs < REQUIRED_PAIRS -> RouteComparisonVerdict.INSUFFICIENT
            improvedPairs == comparablePairs -> RouteComparisonVerdict.PROMISING
            regressedPairs == comparablePairs -> RouteComparisonVerdict.REGRESSION
            else -> RouteComparisonVerdict.INCONCLUSIVE
        }
        val summary = when (verdict) {
            RouteComparisonVerdict.PROMISING ->
                "Amélioration répétée sur les points de contrôle HTTPS (${improvedPairs}/${comparablePairs} séries), sans baisse du taux de réussite."
            RouteComparisonVerdict.REGRESSION ->
                "Le candidat dégrade ou ne préserve pas la fiabilité des mesures (${regressedPairs}/${comparablePairs} séries)."
            RouteComparisonVerdict.INCONCLUSIVE ->
                "Les résultats ne montrent pas d'avantage suffisamment constant (${improvedPairs}/${comparablePairs} séries favorables)."
            RouteComparisonVerdict.INSUFFICIENT ->
                "Les séries comparables ne contiennent pas assez de mesures exploitables."
        }
        return RouteComparisonResult(verdict, comparablePairs, summary, limitations)
    }

    private fun insufficient(
        validPairs: Int,
        limitations: List<String>,
        reason: String
    ) = RouteComparisonResult(
        RouteComparisonVerdict.INSUFFICIENT,
        validPairs,
        reason,
        limitations
    )

    private fun isValidBatch(batch: RouteComparisonBatch): Boolean {
        if (batch.aggregates.isEmpty()) return false
        val hosts = batch.aggregates.map { it.host }
        if (hosts.any { it.isBlank() } || hosts.distinct().size != hosts.size) return false
        return batch.aggregates.all { aggregate ->
            aggregate.sampleCount == REQUIRED_SAMPLES_PER_HOST &&
                aggregate.completeSamples >= MIN_COMPLETE_SAMPLES &&
                aggregate.dns.successfulSamples >= MIN_COMPLETE_SAMPLES &&
                aggregate.tcp.successfulSamples >= MIN_COMPLETE_SAMPLES &&
                aggregate.tcpAttemptTotal.successfulSamples >= MIN_COMPLETE_SAMPLES &&
                aggregate.tls.successfulSamples >= MIN_COMPLETE_SAMPLES &&
                aggregate.httpStatusLine.successfulSamples >= MIN_COMPLETE_SAMPLES
        }
    }

    private fun phaseScore(aggregate: RouteAggregate): Pair<Long, Long>? {
        val phases = listOf(aggregate.dns, aggregate.tcpAttemptTotal, aggregate.tls, aggregate.httpStatusLine)
        if (phases.any {
                it.successfulSamples < MIN_COMPLETE_SAMPLES ||
                    it.medianMs == null || it.p95ObservedMs == null
            }) return null
        return phases.sumOf { it.medianMs!! } to phases.sumOf { it.p95ObservedMs!! }
    }
}
