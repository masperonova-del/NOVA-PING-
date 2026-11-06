package com.novaping.app

import kotlin.math.ceil

data class PingMetrics(
    val medianMs: Long,
    val p95Ms: Long,
    val jitterMs: Long,
    val lostRequests: Int,
    val totalRequests: Int
) {
    companion object {
        /** Jitter is the mean absolute difference between consecutive successful samples. */
        fun from(samplesMs: List<Long>, totalRequests: Int): PingMetrics {
            require(totalRequests > 0) { "totalRequests must be positive" }
            require(samplesMs.isNotEmpty()) { "At least one successful sample is required" }
            require(samplesMs.size <= totalRequests) { "Samples cannot exceed total requests" }
            require(samplesMs.all { it >= 0 }) { "Latency samples cannot be negative" }

            val sorted = samplesMs.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2
            } else sorted[middle]

            val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)
            val jitter = if (samplesMs.size < 2) 0L else {
                samplesMs.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong()
            }

            return PingMetrics(
                medianMs = median,
                p95Ms = sorted[p95Index],
                jitterMs = jitter,
                lostRequests = totalRequests - samplesMs.size,
                totalRequests = totalRequests
            )
        }
    }
}
