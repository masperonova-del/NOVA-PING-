package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevEngineRunGateTest {
    @Test
    fun onlyOneEngineRunCanReserveTheNativeLifecycle() {
        val gate = HevEngineRunGate()

        assertTrue(gate.tryReserve())
        assertTrue(gate.isReserved())
        assertFalse(gate.tryReserve())
    }

    @Test
    fun releasedEngineRunAllowsTheNextSession() {
        val gate = HevEngineRunGate()

        assertTrue(gate.tryReserve())
        gate.release()

        assertFalse(gate.isReserved())
        assertTrue(gate.tryReserve())
    }

    @Test
    fun releaseIsSafeAfterAnAlreadyReleasedRun() {
        val gate = HevEngineRunGate()

        gate.release()
        gate.release()

        assertTrue(gate.tryReserve())
    }
}
