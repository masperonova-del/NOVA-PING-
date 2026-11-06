package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProbeModelTest {
    @Test fun marksCompleteOnlyWhenEveryPhaseIsPresent() {
        val complete = RouteProbeOutcome(
            host = "example.com", dnsMs = 5, tcpConnectMs = 10,
            tlsHandshakeMs = 20, httpFirstByteMs = 30, httpStatus = 204,
            totalMs = 65
        )
        val incomplete = RouteProbeOutcome(
            host = "example.com", dnsMs = 5, failure = RouteProbeFailure.TCP_CONNECT,
            totalMs = 10
        )
        assertTrue(complete.completed)
        assertFalse(incomplete.completed)
    }
}
