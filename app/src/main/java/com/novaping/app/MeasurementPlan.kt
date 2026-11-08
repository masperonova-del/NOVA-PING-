package com.novaping.app

/**
 * Central measurement policy for NOVA PING 1.8.
 * The HTTPS batch uses 25 attempts per destination so nearest-rank P95
 * is the 24th ordered sample rather than the maximum of a tiny sample.
 */
object MeasurementPlan {
    const val HTTPS_CYCLES = 5
    const val HTTPS_REQUESTS_PER_CYCLE = 5
    const val HTTPS_TOTAL_REQUESTS = HTTPS_CYCLES * HTTPS_REQUESTS_PER_CYCLE
    const val MIN_SUCCESSFUL_HTTPS_FOR_ASSESSMENT = 15
    const val MIN_SUCCESSFUL_HTTPS_FOR_RANKING = 20

    const val ROUTE_SAMPLES_PER_TARGET = 5
    const val MIN_COMPLETE_ROUTE_SAMPLES_FOR_CLEAN_RESULT = 4

    fun isHttpsRankable(successful: Int, attempted: Int): Boolean =
        attempted == HTTPS_TOTAL_REQUESTS &&
            successful >= MIN_SUCCESSFUL_HTTPS_FOR_RANKING
}
