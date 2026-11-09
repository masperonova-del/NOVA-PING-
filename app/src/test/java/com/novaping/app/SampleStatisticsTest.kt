package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleStatisticsTest {
    @Test
    fun nearestRankP95UsesSecondHighestForTwentySamples() {
        val result = SampleStatistics.summarize((1L..20L).toList())
        assertEquals(10L, result.medianMs)
        assertEquals(19L, result.p95ObservedMs)
    }

    @Test
    fun twentyFiveSamplesUseTwentyFourthValueForNearestRankP95() {
        val result = SampleStatistics.summarize((1L..25L).toList())
        assertEquals(13L, result.medianMs)
        assertEquals(24L, result.p95ObservedMs)
    }

    @Test
    fun evenSampleMedianDoesNotOverflowWhenAddingLargeValues() {
        val result = SampleStatistics.summarize(listOf(Long.MAX_VALUE - 2L, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE - 1L, result.medianMs)
        assertEquals(Long.MAX_VALUE, result.p95ObservedMs)
    }

    @Test
    fun failedAttemptBreaksJitterAdjacency() {
        val result = SampleStatistics.summarizeAttempts(listOf(30L, null, 180L, 190L))
        assertEquals(180L, result.medianMs)
        assertEquals(190L, result.p95ObservedMs)
        assertEquals(10L, result.meanAbsoluteVariationMs)
    }

    @Test
    fun allAdjacentSuccessfulAttemptsContributeToJitter() {
        val result = SampleStatistics.summarizeAttempts(listOf(20L, 30L, 50L, 40L))
        assertEquals(13L, result.meanAbsoluteVariationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLatencySamples() {
        SampleStatistics.summarize(listOf(10L, -1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAttemptSequenceWithoutSuccesses() {
        SampleStatistics.summarizeAttempts(listOf(null, null))
    }
}
