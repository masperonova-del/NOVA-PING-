package com.novaping.app

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

            val summary = SampleStatistics.summarize(samplesMs)
            return PingMetrics(
                medianMs = summary.medianMs,
                p95Ms = summary.p95ObservedMs,
                jitterMs = summary.meanAbsoluteVariationMs ?: 0L,
                lostRequests = totalRequests - samplesMs.size,
                totalRequests = totalRequests
            )
        }
    }
}
