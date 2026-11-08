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
    fun summarize(samplesMs: List<Long>): SampleSummary {
        require(samplesMs.isNotEmpty()) { "At least one successful sample is required" }
        require(samplesMs.all { it >= 0L }) { "Latency samples cannot be negative" }

        val sorted = samplesMs.sorted()
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

        val variation = if (samplesMs.size < 2) null else {
            samplesMs.zipWithNext { first, second ->
                abs(first - second)
            }.average().toLong()
        }

        return SampleSummary(
            medianMs = median,
            p95ObservedMs = sorted[p95Index],
            meanAbsoluteVariationMs = variation
        )
    }
}
