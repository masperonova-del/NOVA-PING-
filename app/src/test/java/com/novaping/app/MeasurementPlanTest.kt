package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementPlanTest {
    @Test fun usesTwentyFiveHttpsAttemptsPerDestination() {
        assertTrue(MeasurementPlan.HTTPS_TOTAL_REQUESTS == 25)
        assertTrue(MeasurementPlan.HTTPS_CYCLES == 5)
        assertTrue(MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE == 5)
    }

    @Test fun requiresAtLeastEightyPercentSuccessForRanking() {
        assertTrue(MeasurementPlan.isHttpsRankable(20, 25))
        assertFalse(MeasurementPlan.isHttpsRankable(19, 25))
        assertFalse(MeasurementPlan.isHttpsRankable(25, 24))
    }

    @Test fun routeAnalysisUsesFiveIndependentSamples() {
        assertTrue(MeasurementPlan.ROUTE_SAMPLES_PER_TARGET == 5)
        assertTrue(MeasurementPlan.MIN_COMPLETE_ROUTE_SAMPLES_FOR_CLEAN_RESULT == 4)
    }
}
