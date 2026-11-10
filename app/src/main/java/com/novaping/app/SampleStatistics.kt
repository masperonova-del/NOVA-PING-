package com.novaping.app

import kotlin.math.abs

/**
 * Descriptive statistics for successful, non-negative latency samples.
 *
 * P95 uses the nearest-rank definition: rank = ceil(0.95 * n).
 * For small samples this is intentionally conservative (the maximum for n <= 19).
 */
data class SampleSummary(
    val medianMs: Long,
    val p95ObservedMs: Long,
    val meanAbsoluteVariationMs: Long?
)

object SampleStatistics {
    /**
     * Summarizes an ordered attempt sequence. A null represents a failed attempt.
     * Variation is calculated only when two adjacent attempts both succeeded;
     * a failure is never silently removed to make its neighbours appear adjacent.
     */
    fun summarizeAttempts(attemptsMs: List<Long?>): SampleSummary {
        require(attemptsMs.isNotEmpty()) { "At least one attempt is required" }
        val successfulSamples = attemptsMs.filterNotNull()
        require(successfulSamples.isNotEmpty()) { "At least one successful sample is required" }
        require(successfulSamples.all { it >= 0L }) { "Latency samples cannot be negative" }

        val sorted = successfulSamples.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            val lower = sorted[middle - 1]
            val upper = sorted[middle]
            lower + (upper - lower) / 2L
        } else {
            sorted[middle]
        }

        // Integer form of ceil(0.95 * n), avoiding floating-point rank rounding.
        val nearestRank = (95L * sorted.size + 99L) / 100L
        val p95Index = (nearestRank - 1L).toInt()

        val adjacentDifferences = attemptsMs.zipWithNext().mapNotNull { (first, second) ->
            if (first != null && second != null) abs(first - second) else null
        }
        val variation = adjacentDifferences.takeIf { it.isNotEmpty() }?.average()?.toLong()

        return SampleSummary(
            medianMs = median,
            p95ObservedMs = sorted[p95Index],
            meanAbsoluteVariationMs = variation
        )
    }

    /** All supplied samples are treated as consecutive successful attempts. */
    fun summarize(samplesMs: List<Long>): SampleSummary =
        summarizeAttempts(samplesMs.map { it })
}
