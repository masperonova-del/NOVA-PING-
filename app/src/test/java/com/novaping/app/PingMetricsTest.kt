package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PingMetricsTest {
    @Test
    fun computesMedianP95JitterAndLoss() {
        val result = PingMetrics.from(listOf(20, 30, 50, 40), 5)
        assertEquals(35L, result.medianMs)
        assertEquals(50L, result.p95Ms)
        assertEquals(13L, result.jitterMs)
        assertEquals(1, result.lostRequests)
        assertEquals(5, result.totalRequests)
    }

    @Test
    fun orderedAttemptsDoNotBridgeAcrossFailedRequests() {
        val result = PingMetrics.fromAttempts(listOf(30L, null, 180L, 190L))
        assertEquals(180L, result.medianMs)
        assertEquals(190L, result.p95Ms)
        assertEquals(10L, result.jitterMs)
        assertEquals(1, result.lostRequests)
        assertEquals(4, result.totalRequests)
    }

    @Test
    fun handlesSingleSuccessfulSample() {
        val result = PingMetrics.from(listOf(42), 3)
        assertEquals(42L, result.medianMs)
        assertEquals(42L, result.p95Ms)
        assertEquals(0L, result.jitterMs)
        assertEquals(2, result.lostRequests)
    }

    @Test
    fun p95CannotBeBelowMedianForNineSuccessfulSamples() {
        val result = PingMetrics.from(listOf(900, 100, 500, 300, 700, 200, 800, 400, 600), 9)
        assertEquals(500L, result.medianMs)
        assertEquals(900L, result.p95Ms)
        assertTrue(result.p95Ms >= result.medianMs)
    }
}
