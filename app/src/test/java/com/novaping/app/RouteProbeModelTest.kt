package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProbeModelTest {
    @Test fun marksCompleteOnlyWhenEveryPhaseIsPresent() {
        val complete = RouteProbeOutcome(
            host = "example.com", dnsMs = 5, tcpConnectMs = 10,
            tlsHandshakeMs = 20, httpStatusLineMs = 30, httpStatus = 204,
            totalMs = 65
        )
        val incomplete = RouteProbeOutcome(
            host = "example.com", dnsMs = 5, failure = RouteProbeFailure.TCP_CONNECT,
            totalMs = 10
        )
        assertTrue(complete.completed)
        assertFalse(incomplete.completed)
    }

    @Test fun aggregatesPhasesIndependentlyAndCountsFailures() {
        val samples = listOf(
            RouteProbeOutcome("example.com", 10, 20, 30, 40, 204, null, 110),
            RouteProbeOutcome("example.com", 20, 30, 40, 50, 204, null, 140),
            RouteProbeOutcome("example.com", 30, null, null, null, null, RouteProbeFailure.TCP_CONNECT, 80)
        )
        val aggregate = RouteStatistics.aggregate(samples)
        assertEquals(3, aggregate.sampleCount)
        assertEquals(2, aggregate.completeSamples)
        assertEquals(15L, aggregate.dns.medianMs)
        assertEquals(2, aggregate.tcp.successfulSamples)
        assertEquals(1, aggregate.failures[RouteProbeFailure.TCP_CONNECT])
        assertEquals(2, aggregate.httpStatuses[204])
    }

    @Test fun missingPhaseHasNoInventedLatency() {
        val aggregate = RouteStatistics.aggregate(listOf(
            RouteProbeOutcome("example.com", failure = RouteProbeFailure.DNS, totalMs = 2)
        ))
        assertNull(aggregate.dns.medianMs)
        assertEquals(0, aggregate.dns.successfulSamples)
    }
}
