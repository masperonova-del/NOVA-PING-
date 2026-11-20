package com.novaping.app

/**
 * One measured HTTPS-control batch. The network fingerprint must identify the
 * same physical underlay across DIRECT and CANDIDATE runs; it must not be a VPN
 * label, SSID, MAC address, or guessed value.
 */
data class RouteComparisonBatch(
    val label: String,
    val transport: NetworkTransport,
    val vpnLabel: String?,
    val aggregates: List<RouteAggregate>,
    val experimentId: String,
    val batchId: String,
    val networkFingerprint: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val measurementProfile: String = "HTTPS_CONTROL_V1",
    val targetFingerprint: String = aggregates.map { it.host }.sorted().joinToString("|")
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
 *
 * A pair is accepted only when both runs have complete provenance, the same
 * experiment/physical-network fingerprint/measurement profile/target set, and
 * the candidate run follows the direct run within the allowed pairing window.
 */
object RouteComparisonEngine {
    private const val REQUIRED_PAIRS = 3
    private const val REQUIRED_SAMPLES_PER_HOST = MeasurementPlan.ROUTE_SAMPLES_PER_TARGET
    private const val MIN_COMPLETE_SAMPLES = MeasurementPlan.MIN_COMPLETE_SAMPLES_FOR_BOOST_RECOMMENDATION
    private const val MAX_PAIR_GAP_MS = 30 * 60 * 1000L
    private const val MATERIAL_CHANGE_PERCENT = 5L

    fun compare(
        directBatches: List<RouteComparisonBatch>,
        candidateBatches: List<RouteComparisonBatch>
    ): RouteComparisonResult {
        val limitations = listOf(
            "Les destinations sont des points de contrôle HTTPS, pas les serveurs de match.",
            "La somme des phases DNS, TCP, TLS et HTTP est indicative, pas un RTT de bout en bout.",
            "Une amélioration HTTPS ne prouve pas une réduction du ping dans CODM.",
            "Le fingerprint doit représenter le même réseau physique sous-jacent ; Android peut ne pas exposer cette information pour certains VPN.",
            "Aucun routage n'est modifié et aucun VPN n'est activé."
        )
        if (directBatches.size != candidateBatches.size ||
            directBatches.size < REQUIRED_PAIRS
        ) {
            return insufficient(0, limitations, "Les deux séries doivent contenir le même nombre de lots et au moins $REQUIRED_PAIRS paires.")
        }

        val directIdentity = directBatches.map { it.label to it.experimentId }.distinct()
        val candidateIdentity = candidateBatches.map { Triple(it.label, it.vpnLabel, it.experimentId) }.distinct()
        if (directIdentity.size != 1 || candidateIdentity.size != 1) {
            return insufficient(0, limitations, "Le profil direct, le profil candidat ou l'identifiant d'expérience change entre les séries.")
        }

        val allBatchIds = (directBatches + candidateBatches).map { it.batchId }
        if (allBatchIds.any { it.isBlank() } || allBatchIds.distinct().size != allBatchIds.size) {
            return insufficient(0, limitations, "Chaque lot doit posséder un identifiant unique.")
        }

        val paired = directBatches.zip(candidateBatches).filter { (direct, candidate) ->
            isValidBatch(direct) &&
                isValidBatch(candidate) &&
                direct.experimentId == candidate.experimentId &&
                direct.transport == candidate.transport &&
                direct.networkFingerprint == candidate.networkFingerprint &&
                direct.measurementProfile == candidate.measurementProfile &&
                direct.targetFingerprint == candidate.targetFingerprint &&
                direct.aggregates.map { it.host }.toSet() ==
                    candidate.aggregates.map { it.host }.toSet() &&
                candidate.startedAtEpochMs >= direct.completedAtEpochMs &&
                candidate.startedAtEpochMs - direct.completedAtEpochMs <= MAX_PAIR_GAP_MS
        }
        if (paired.size < REQUIRED_PAIRS) {
            return insufficient(
                paired.size, limitations,
                "Comparaison insuffisante : ${paired.size}/$REQUIRED_PAIRS paires avec provenance, réseau physique, destinations et chronologie comparables."
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
                materiallyBetter(scores.second.first, scores.first.first) &&
                    scores.second.second <= scores.first.second
            }
            val worse = comparisons.count { (_, _, scores) ->
                materiallyWorse(scores.second.first, scores.first.first) ||
                    materiallyWorse(scores.second.second, scores.first.second)
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
                "Amélioration répétée et d'au moins 5 % sur la médiane des phases HTTPS ($improvedPairs/$comparablePairs séries), sans dégradation du P95 observé ni du taux de réussite."
            RouteComparisonVerdict.REGRESSION ->
                "Le candidat dégrade sensiblement les mesures ou ne préserve pas leur fiabilité ($regressedPairs/$comparablePairs séries)."
            RouteComparisonVerdict.INCONCLUSIVE ->
                "Les résultats ne montrent pas d'avantage suffisamment constant ou matériel ($improvedPairs/$comparablePairs séries favorables)."
            RouteComparisonVerdict.INSUFFICIENT ->
                "Les séries comparables ne contiennent pas assez de mesures exploitables."
        }
        return RouteComparisonResult(verdict, comparablePairs, summary, limitations)
    }

    private fun materiallyBetter(candidate: Long, baseline: Long): Boolean =
        baseline > 0 && candidate * 100L <= baseline * (100L - MATERIAL_CHANGE_PERCENT)

    private fun materiallyWorse(candidate: Long, baseline: Long): Boolean =
        baseline > 0 && candidate * 100L >= baseline * (100L + MATERIAL_CHANGE_PERCENT)

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
        if (batch.experimentId.isBlank() || batch.batchId.isBlank() ||
            batch.networkFingerprint.isBlank() || batch.measurementProfile.isBlank() ||
            batch.startedAtEpochMs <= 0L ||
            batch.completedAtEpochMs <= batch.startedAtEpochMs
        ) return false
        if (batch.aggregates.isEmpty()) return false
        val hosts = batch.aggregates.map { it.host }
        if (hosts.any { it.isBlank() } || hosts.distinct().size != hosts.size) return false
        if (batch.targetFingerprint != hosts.sorted().joinToString("|")) return false
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
