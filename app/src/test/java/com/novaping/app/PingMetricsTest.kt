package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PingMetricsTest {
    @Test
    fun computesMedianP95JitterAndLoss() {
        val result = PingMetrics.from(listOf(20, 30, 50, 40), 5)
        assertEquals(35L, result.medianMs)
        assertEquals(50L, result.p95Ms)
        assertEquals(20L, result.jitterMs)
        assertEquals(1, result.lostRequests)
        assertEquals(5, result.totalRequests)
    }

    @Test
    fun handlesSingleSuccessfulSample() {
        val result = PingMetrics.from(listOf(42), 3)
        assertEquals(42L, result.medianMs)
        assertEquals(42L, result.p95Ms)
        assertEquals(0L, result.jitterMs)
        assertEquals(2, result.lostRequests)
    }
}
