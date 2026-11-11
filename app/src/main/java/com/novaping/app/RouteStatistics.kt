package com.novaping.app

data class PhaseStatistics(
    val medianMs: Long?,
    val p95ObservedMs: Long?,
    val meanAbsoluteVariationMs: Long?,
    val successfulSamples: Int,
    val attemptedSamples: Int
)

data class RouteAggregate(
    val host: String,
    val sampleCount: Int,
    val completeSamples: Int,
    val httpSuccessfulSamples: Int,
    val dns: PhaseStatistics,
    val tcp: PhaseStatistics,
    val tcpAttemptTotal: PhaseStatistics,
    val tls: PhaseStatistics,
    val httpStatusLine: PhaseStatistics,
    val failures: Map<RouteProbeFailure, Int>,
    val httpStatuses: Map<Int, Int>
)

object RouteStatistics {
    fun aggregate(outcomes: List<RouteProbeOutcome>): RouteAggregate {
        require(outcomes.isNotEmpty()) { "At least one route sample is required" }
        return RouteAggregate(
            host = outcomes.first().host,
            sampleCount = outcomes.size,
            completeSamples = outcomes.count { it.completed },
            httpSuccessfulSamples = outcomes.count { it.httpResponseSuccessful },
            dns = phase(outcomes.mapNotNull { it.dnsMs }, outcomes.size),
            tcp = phase(outcomes.mapNotNull { it.tcpConnectMs }, outcomes.size),
            tcpAttemptTotal = phase(outcomes.mapNotNull { it.tcpAttemptTotalMs }, outcomes.size),
            tls = phase(outcomes.mapNotNull { it.tlsHandshakeMs }, outcomes.size),
            httpStatusLine = phase(outcomes.mapNotNull { it.httpStatusLineMs }, outcomes.size),
            failures = outcomes.mapNotNull { it.failure }.groupingBy { it }.eachCount(),
            httpStatuses = outcomes.mapNotNull { it.httpStatus }.groupingBy { it }.eachCount()
        )
    }

    private fun phase(samples: List<Long>, attempted: Int): PhaseStatistics {
        if (samples.isEmpty()) return PhaseStatistics(null, null, null, 0, attempted)
        val summary = SampleStatistics.summarize(samples)
        return PhaseStatistics(
            medianMs = summary.medianMs,
            p95ObservedMs = summary.p95ObservedMs,
            meanAbsoluteVariationMs = summary.meanAbsoluteVariationMs,
            successfulSamples = samples.size,
            attemptedSamples = attempted
        )
    }
}
