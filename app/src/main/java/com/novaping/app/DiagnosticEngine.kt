package com.novaping.app

/**
 * Interprets the consistency of HTTPS control-endpoint measurements.
 * These heuristics do not measure game-server ping.
 */
data class StabilityAssessment(
    val label: String,
    val explanation: String
)

object DiagnosticEngine {
    private const val MIN_SUCCESSFUL_SAMPLES = 6
    private const val HIGH_LOSS_PERCENT = 33
    private const val HIGH_JITTER_MS = 100L
    private const val HIGH_CYCLE_SPREAD_MS = 150L
    private const val MODERATE_JITTER_MS = 50L
    private const val MODERATE_CYCLE_SPREAD_MS = 80L

    fun assess(metrics: PingMetrics?, cycleMedians: List<Long>): StabilityAssessment {
        if (metrics == null) {
            return StabilityAssessment("Données insuffisantes", "Aucune mesure HTTPS exploitable n'a été obtenue.")
        }

        val successful = metrics.totalRequests - metrics.lostRequests
        if (successful < MIN_SUCCESSFUL_SAMPLES) {
            return StabilityAssessment(
                "Données insuffisantes",
                "Seulement $successful/${metrics.totalRequests} mesures ont abouti. Relance le test dans de meilleures conditions."
            )
        }

        val lossPercent = (metrics.lostRequests * 100) / metrics.totalRequests
        val spread = if (cycleMedians.size >= 2) {
            cycleMedians.maxOrNull()!! - cycleMedians.minOrNull()!!
        } else null

        val reasons = mutableListOf<String>()
        if (metrics.lostRequests > 0) reasons.add("$lossPercent % de requêtes sans réponse")
        reasons.add("variation moyenne ${metrics.jitterMs} ms")
        if (spread != null) reasons.add("écart entre cycles ${spread} ms")

        val label = when {
            lossPercent >= HIGH_LOSS_PERCENT -> "Pertes importantes"
            metrics.jitterMs >= HIGH_JITTER_MS || (spread != null && spread >= HIGH_CYCLE_SPREAD_MS) -> "Très variable"
            metrics.lostRequests > 0 || metrics.jitterMs >= MODERATE_JITTER_MS ||
                (spread != null && spread >= MODERATE_CYCLE_SPREAD_MS) -> "Variable"
            else -> "Régulier"
        }

        val explanation = when (label) {
            "Pertes importantes" -> "Une part importante des requêtes n'a pas abouti : ${reasons.joinToString(", ")}."
            "Très variable" -> "Les temps de réponse changent fortement : ${reasons.joinToString(", ")}."
            "Variable" -> "Une irrégularité est détectée : ${reasons.joinToString(", ")}."
            else -> "Les mesures obtenues sont relativement cohérentes : ${reasons.joinToString(", ")}."
        }
        return StabilityAssessment(label, explanation)
    }
}
