package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNetworkAvailabilityTest {
    @Test
    fun cellularWithInternetIsUsable() {
        assertTrue(
            MobileNetworkAvailability.isUsable(
                hasCellularTransport = true,
                hasInternetCapability = true
            )
        )
    }

    @Test
    fun wifiIsNotAcceptedAsSelectedCellularUpstream() {
        assertFalse(
            MobileNetworkAvailability.isUsable(
                hasCellularTransport = false,
                hasInternetCapability = true
            )
        )
    }

    @Test
    fun cellularWithoutInternetIsUnavailable() {
        assertFalse(
            MobileNetworkAvailability.isUsable(
                hasCellularTransport = true,
                hasInternetCapability = false
            )
        )
    }

    @Test
    fun missingBothCapabilitiesIsUnavailable() {
        assertFalse(
            MobileNetworkAvailability.isUsable(
                hasCellularTransport = false,
                hasInternetCapability = false
            )
        )
    }
}
