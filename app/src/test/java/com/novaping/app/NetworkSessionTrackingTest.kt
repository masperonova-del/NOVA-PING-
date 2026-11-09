package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSessionTrackingTest {
    private val wifi = NetworkObservation(
        networkId = "101",
        transport = NetworkTransport.WIFI,
        internetAvailable = true,
        validated = true
    )

    @Test fun sameValidatedNetworkDoesNotInvalidateSession() {
        assertNull(wifi.issueComparedTo(wifi))
        assertTrue(wifi.isReady)
    }

    @Test fun transportOrNetworkIdentityChangeIsDetected() {
        val mobile = NetworkObservation(
            networkId = "202",
            transport = NetworkTransport.CELLULAR,
            internetAvailable = true,
            validated = true
        )
        assertEquals(NetworkSessionIssue.NETWORK_CHANGED, wifi.issueComparedTo(mobile))
    }

    @Test fun lossOfValidatedInternetIsDetected() {
        val disconnected = NetworkObservation(
            networkId = null,
            transport = NetworkTransport.NONE,
            internetAvailable = false,
            validated = false
        )
        assertEquals(NetworkSessionIssue.INTERNET_NOT_VALIDATED, wifi.issueComparedTo(disconnected))
    }

    @Test fun unvalidatedNetworkIsNotTreatedAsReady() {
        val unvalidated = wifi.copy(internetAvailable = true, validated = false)
        assertFalse(unvalidated.isReady)
        assertEquals(NetworkSessionIssue.INTERNET_NOT_VALIDATED, wifi.issueComparedTo(unvalidated))
    }

    @Test fun attemptTraceRecordsWhetherNetworkRemainedStable() {
        val stable = ProbeAttemptTrace(1L, 2L, wifi, wifi)
        val changed = ProbeAttemptTrace(
            1L,
            2L,
            wifi,
            wifi.copy(networkId = "303", transport = NetworkTransport.CELLULAR)
        )
        assertTrue(stable.networkStable)
        assertFalse(changed.networkStable)
    }
}
