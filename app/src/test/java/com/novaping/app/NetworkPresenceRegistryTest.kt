package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPresenceRegistryTest {
    @Test fun losingOneOfTwoWifiNetworksDoesNotReportWifiUnavailable() {
        val registry = NetworkPresenceRegistry()
        registry.synchronize(mapOf("wifi-a" to true, "wifi-b" to true))

        val afterLoss = registry.onLost("wifi-a")

        assertTrue(afterLoss.available)
        assertEquals(true, afterLoss.internetValidated)
    }

    @Test fun validationUsesAnyRemainingValidatedNetwork() {
        val registry = NetworkPresenceRegistry()
        registry.synchronize(mapOf("wifi-a" to true, "wifi-b" to false))

        val afterValidatedNetworkIsLost = registry.onLost("wifi-a")

        assertTrue(afterValidatedNetworkIsLost.available)
        assertEquals(false, afterValidatedNetworkIsLost.internetValidated)
    }

    @Test fun unknownCapabilitiesDoNotBecomeFalseValidation() {
        val registry = NetworkPresenceRegistry()
        registry.onAvailable("wifi-a")

        val snapshot = registry.snapshot()

        assertTrue(snapshot.available)
        assertNull(snapshot.internetValidated)
    }

    @Test fun duplicateLostCallbackDoesNotChangeRemainingNetworkState() {
        val registry = NetworkPresenceRegistry()
        registry.synchronize(mapOf("wifi-a" to true, "wifi-b" to false))
        registry.onLost("wifi-a")

        val afterDuplicateLoss = registry.onLost("wifi-a")

        assertTrue(afterDuplicateLoss.available)
        assertEquals(false, afterDuplicateLoss.internetValidated)
    }

    @Test fun losingLastNetworkProducesUnavailableSnapshot() {
        val registry = NetworkPresenceRegistry()
        registry.synchronize(mapOf("mobile-a" to true))

        val snapshot = registry.onLost("mobile-a")

        assertFalse(snapshot.available)
        assertNull(snapshot.internetValidated)
    }

    @Test fun synchronizationReplacesOldNetworkIdentities() {
        val registry = NetworkPresenceRegistry()
        registry.synchronize(mapOf("old" to true))
        registry.synchronize(mapOf("new" to false))

        val afterOldNetworkLost = registry.onLost("old")

        assertTrue(afterOldNetworkLost.available)
        assertEquals(false, afterOldNetworkLost.internetValidated)
    }
}
