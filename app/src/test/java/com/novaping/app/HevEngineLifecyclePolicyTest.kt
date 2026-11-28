package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevEngineLifecyclePolicyTest {
    @Test
    fun cleanExitIsUnexpectedWhenStopWasNotRequested() {
        assertEquals(
            "Le moteur HEV s'est arrêté de manière inattendue.",
            HevEngineExitPolicy.unexpectedExitMessage(exitCode = 0, stopRequested = false)
        )
    }

    @Test
    fun nonZeroExitIsReportedWhenStopWasNotRequested() {
        assertEquals(
            "Le moteur HEV s'est arrêté avec le code 7.",
            HevEngineExitPolicy.unexpectedExitMessage(exitCode = 7, stopRequested = false)
        )
    }

    @Test
    fun requestedStopDoesNotBecomeAnEngineFailure() {
        assertNull(
            HevEngineExitPolicy.unexpectedExitMessage(exitCode = 0, stopRequested = true)
        )
        assertNull(
            HevEngineExitPolicy.unexpectedExitMessage(exitCode = 7, stopRequested = true)
        )
    }

    @Test
    fun readinessTimesOutOnlyAfterDeadlineWhileEngineIsWaiting() {
        assertFalse(
            HevReadinessPolicy.hasTimedOut(
                ready = false, engineAlive = true, stopRequested = false,
                elapsedMs = 14_999L, timeoutMs = 15_000L
            )
        )
        assertTrue(
            HevReadinessPolicy.hasTimedOut(
                ready = false, engineAlive = true, stopRequested = false,
                elapsedMs = 15_000L, timeoutMs = 15_000L
            )
        )
    }

    @Test
    fun readinessDoesNotTimeoutAfterReadyOrDuringStop() {
        assertFalse(
            HevReadinessPolicy.hasTimedOut(
                ready = true, engineAlive = true, stopRequested = false,
                elapsedMs = 30_000L, timeoutMs = 15_000L
            )
        )
        assertFalse(
            HevReadinessPolicy.hasTimedOut(
                ready = false, engineAlive = true, stopRequested = true,
                elapsedMs = 30_000L, timeoutMs = 15_000L
            )
        )
    }

    @Test
    fun readinessDoesNotTimeoutAfterEngineHasExited() {
        assertFalse(
            HevReadinessPolicy.hasTimedOut(
                ready = false, engineAlive = false, stopRequested = false,
                elapsedMs = 30_000L, timeoutMs = 15_000L
            )
        )
    }
}
