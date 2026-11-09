package com.novaping.app

data class PingMetrics(
    val medianMs: Long,
    val p95Ms: Long,
    val jitterMs: Long,
    val lostRequests: Int,
    val totalRequests: Int
) {
    companion object {
        /** Jitter uses only adjacent attempts that both completed successfully. */
        fun from(samplesMs: List<Long>, totalRequests: Int): PingMetrics {
            require(totalRequests > 0) { "totalRequests must be positive" }
            require(samplesMs.isNotEmpty()) { "At least one successful sample is required" }
            require(samplesMs.size <= totalRequests) { "Samples cannot exceed total requests" }
            return fromAttempts(samplesMs.map { it }, totalRequests)
        }

        /**
         * Builds metrics from the original ordered attempt sequence.
         * Null entries represent failed attempts and remain part of the loss count.
         */
        fun fromAttempts(attemptsMs: List<Long?>, totalRequests: Int = attemptsMs.size): PingMetrics {
            require(totalRequests > 0) { "totalRequests must be positive" }
            require(attemptsMs.isNotEmpty()) { "At least one attempt is required" }
            require(attemptsMs.size == totalRequests) { "Attempt sequence must match total requests" }
            val summary = SampleStatistics.summarizeAttempts(attemptsMs)
            val successfulCount = attemptsMs.count { it != null }
            return PingMetrics(
                medianMs = summary.medianMs,
                p95Ms = summary.p95ObservedMs,
                jitterMs = summary.meanAbsoluteVariationMs ?: 0L,
                lostRequests = totalRequests - successfulCount,
                totalRequests = totalRequests
            )
        }
    }
}
