package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteComparisonEngineTest {
    private fun aggregate(host: String, phaseMs: Long, successful: Int = 5): RouteAggregate {
        val outcomes = (0 until 5).map { index ->
            if (index < successful) {
                RouteProbeOutcome(
                    host = host,
                    dnsMs = phaseMs,
                    tcpConnectMs = phaseMs,
                    tlsHandshakeMs = phaseMs,
                    httpStatusLineMs = phaseMs,
                    httpStatus = 204,
                    totalMs = phaseMs * 4,
                    tcpAttempts = listOf(TcpAddressAttempt("192.0.2.1", "IPv4", phaseMs))
                )
            } else {
                RouteProbeOutcome(
                    host = host,
                    failure = RouteProbeFailure.TCP_CONNECT,
                    totalMs = phaseMs
                )
            }
        }
        return RouteStatistics.aggregate(outcomes)
    }

    private fun batch(
        label: String,
        transport: NetworkTransport,
        ms: Long,
        successes: Int = 5,
        pairIndex: Int = 0,
        candidate: Boolean = label != "Direct",
        networkFingerprint: String = "wifi-underlay-1",
        measurementProfile: String = "HTTPS_CONTROL_V1"
    ): RouteComparisonBatch {
        val started = 1_000_000L + pairIndex * 120_000L + if (candidate) 20_000L else 0L
        return RouteComparisonBatch(
            label = label,
            transport = transport,
            vpnLabel = if (candidate) "Route candidate A" else null,
            aggregates = listOf(aggregate("control.example", ms, successes)),
            experimentId = "experiment-1",
            batchId = "${if (candidate) "candidate" else "direct"}-$pairIndex",
            networkFingerprint = networkFingerprint,
            startedAtEpochMs = started,
            completedAtEpochMs = started + 10_000L,
            measurementProfile = measurementProfile
        )
    }

    private fun directSeries(transport: NetworkTransport = NetworkTransport.WIFI) =
        (0..2).map { batch("Direct", transport, 100, pairIndex = it, candidate = false) }

    private fun candidateSeries(
        transport: NetworkTransport = NetworkTransport.WIFI,
        ms: Long = 60,
        networkFingerprint: String = "wifi-underlay-1",
        measurementProfile: String = "HTTPS_CONTROL_V1"
    ) = (0..2).map {
        batch(
            "Candidate", transport, ms, pairIndex = it, candidate = true,
            networkFingerprint = networkFingerprint,
            measurementProfile = measurementProfile
        )
    }

    @Test fun requiresThreeComparablePairs() {
        val result = RouteComparisonEngine.compare(
            listOf(batch("Direct", NetworkTransport.WIFI, 100, candidate = false)),
            listOf(batch("Candidate", NetworkTransport.WIFI, 80))
        )
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsPairsMeasuredOnDifferentTransports() {
        val result = RouteComparisonEngine.compare(
            directSeries(NetworkTransport.WIFI),
            candidateSeries(NetworkTransport.CELLULAR)
        )
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun identifiesConsistentControlEndpointImprovementOnlyAfterThreePairs() {
        val result = RouteComparisonEngine.compare(directSeries(), candidateSeries())
        assertEquals(RouteComparisonVerdict.PROMISING, result.verdict)
        assertEquals(3, result.validPairs)
        assertTrue(result.limitations.any { it.contains("CODM") })
    }

    @Test fun identifiesReliabilityRegression() {
        val direct = (0..2).map {
            batch("Direct", NetworkTransport.WIFI, 100, 5, pairIndex = it, candidate = false)
        }
        val candidate = (0..2).map {
            batch("Candidate", NetworkTransport.WIFI, 60, 3, pairIndex = it)
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.REGRESSION, result.verdict)
    }

    @Test fun rejectsUnequalSeriesCountsInsteadOfSilentlyZipping() {
        val direct = (0..3).map {
            batch("Direct", NetworkTransport.WIFI, 100, pairIndex = it, candidate = false)
        }
        val candidate = candidateSeries()
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsDuplicateHostsInsideOneBatch() {
        val directBase = batch("Direct", NetworkTransport.WIFI, 100, candidate = false)
        val candidateBase = batch("Candidate", NetworkTransport.WIFI, 80)
        val direct = (0..2).map {
            directBase.copy(
                batchId = "direct-$it",
                startedAtEpochMs = 1_000_000L + it * 120_000L,
                completedAtEpochMs = 1_010_000L + it * 120_000L,
                aggregates = listOf(
                    aggregate("control.example", 100),
                    aggregate("control.example", 110)
                )
            )
        }
        val candidate = (0..2).map {
            candidateBase.copy(
                batchId = "candidate-$it",
                startedAtEpochMs = 1_020_000L + it * 120_000L,
                completedAtEpochMs = 1_030_000L + it * 120_000L
            )
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsSeriesWhenCandidateIdentityChangesBetweenPairs() {
        val direct = directSeries()
        val candidate = candidateSeries().mapIndexed { index, batch ->
            if (index == 2) batch.copy(vpnLabel = "Different candidate") else batch
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }

    @Test fun rejectsIncompletePhaseEvidenceEvenWhenFiveSamplesWereAttempted() {
        val direct = (0..2).map {
            batch("Direct", NetworkTransport.WIFI, 100, 2, pairIndex = it, candidate = false)
        }
        val result = RouteComparisonEngine.compare(direct, candidateSeries())
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsCandidateWhenVpnLabelChangesBetweenPairs() {
        val candidate = candidateSeries().mapIndexed { index, batch ->
            if (index == 2) batch.copy(vpnLabel = "Another relay") else batch
        }
        val result = RouteComparisonEngine.compare(directSeries(), candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }

    @Test fun rejectsDifferentPhysicalNetworkFingerprints() {
        val result = RouteComparisonEngine.compare(
            directSeries(),
            candidateSeries(networkFingerprint = "another-wifi-underlay")
        )
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsNetworkChangeAcrossOtherwiseMatchedPairs() {
        val direct = directSeries().mapIndexed { index, batch ->
            if (index == 2) batch.copy(networkFingerprint = "wifi-underlay-2") else batch
        }
        val candidate = candidateSeries().mapIndexed { index, batch ->
            if (index == 2) batch.copy(networkFingerprint = "wifi-underlay-2") else batch
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
        assertEquals(0, result.validPairs)
    }

    @Test fun rejectsPairSeriesCapturedOutOfOrder() {
        val direct = directSeries()
        val candidate = candidateSeries().mapIndexed { index, batch ->
            if (index == 0) batch.copy(
                startedAtEpochMs = 1_000_000L + 4 * 60_000L,
                completedAtEpochMs = 1_010_000L + 4 * 60_000L
            ) else batch
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }

    @Test fun rejectsDifferentMeasurementProfiles() {
        val result = RouteComparisonEngine.compare(
            directSeries(),
            candidateSeries(measurementProfile = "HTTPS_CONTROL_V2")
        )
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }

    @Test fun rejectsCandidateBatchOutsidePairingWindow() {
        val direct = directSeries()
        val candidate = candidateSeries().map { batch ->
            batch.copy(
                startedAtEpochMs = batch.startedAtEpochMs + 31L * 60L * 1000L,
                completedAtEpochMs = batch.completedAtEpochMs + 31L * 60L * 1000L
            )
        }
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }

    @Test fun ignoresSmallChangesBelowMaterialityThreshold() {
        val result = RouteComparisonEngine.compare(
            directSeries(),
            candidateSeries(ms = 98)
        )
        assertEquals(RouteComparisonVerdict.INCONCLUSIVE, result.verdict)
    }

    @Test fun rejectsCandidateWhenVariationMateriallyWorsens() {
        fun withVariation(batches: List<RouteComparisonBatch>, variation: Long) =
            batches.map { batch ->
                batch.copy(aggregates = batch.aggregates.map { aggregate ->
                    aggregate.copy(
                        dns = aggregate.dns.copy(meanAbsoluteVariationMs = variation),
                        tcpAttemptTotal = aggregate.tcpAttemptTotal.copy(meanAbsoluteVariationMs = variation),
                        tls = aggregate.tls.copy(meanAbsoluteVariationMs = variation),
                        httpStatusLine = aggregate.httpStatusLine.copy(meanAbsoluteVariationMs = variation)
                    )
                })
            }
        val direct = withVariation(directSeries(), 1L)
        val candidate = withVariation(candidateSeries(), 2L)
        val result = RouteComparisonEngine.compare(direct, candidate)
        assertEquals(RouteComparisonVerdict.REGRESSION, result.verdict)
    }

    @Test fun rejectsInvalidOrMissingProvenance() {
        val direct = directSeries().mapIndexed { index, batch ->
            if (index == 1) batch.copy(networkFingerprint = " ") else batch
        }
        val result = RouteComparisonEngine.compare(direct, candidateSeries())
        assertEquals(RouteComparisonVerdict.INSUFFICIENT, result.verdict)
    }
}
