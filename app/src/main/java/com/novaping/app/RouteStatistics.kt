package com.novaping.app

import kotlin.math.ceil
import kotlin.math.abs

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
    val dns: PhaseStatistics,
    val tcp: PhaseStatistics,
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
            dns = phase(outcomes.mapNotNull { it.dnsMs }, outcomes.size),
            tcp = phase(outcomes.mapNotNull { it.tcpConnectMs }, outcomes.size),
            tls = phase(outcomes.mapNotNull { it.tlsHandshakeMs }, outcomes.size),
            httpStatusLine = phase(outcomes.mapNotNull { it.httpStatusLineMs }, outcomes.size),
            failures = outcomes.mapNotNull { it.failure }.groupingBy { it }.eachCount(),
            httpStatuses = outcomes.mapNotNull { it.httpStatus }.groupingBy { it }.eachCount()
        )
    }

    private fun phase(samples: List<Long>, attempted: Int): PhaseStatistics {
        if (samples.isEmpty()) return PhaseStatistics(null, null, null, 0, attempted)
        val sorted = samples.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else sorted[middle]
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)
        val variation = if (samples.size < 2) null else {
            samples.zipWithNext { a, b -> abs(a - b) }.average().toLong()
        }
        return PhaseStatistics(median, sorted[p95Index], variation, samples.size, attempted)
    }
}
