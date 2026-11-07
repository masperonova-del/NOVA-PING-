package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkStabilityTrackerTest {
    @Test fun detectsWifiDisconnectSeparately() {
        val tracker = NetworkStabilityTracker()
        assertEquals(
            NetworkStabilityEventType.WIFI_CONNECTED,
            tracker.onWifiAvailabilityChanged(true, 100L)?.type
        )
        assertEquals(
            NetworkStabilityEventType.WIFI_DISCONNECTED,
            tracker.onWifiAvailabilityChanged(false, 200L)?.type
        )
    }

    @Test fun distinguishesInternetLossWhileWifiRemainsConnected() {
        val tracker = NetworkStabilityTracker()
        tracker.onWifiAvailabilityChanged(true, 100L)
        assertNull(tracker.onWifiInternetValidationChanged(true, 110L))
        assertEquals(
            NetworkStabilityEventType.INTERNET_LOST,
            tracker.onWifiInternetValidationChanged(false, 200L)?.type
        )
        assertEquals(
            NetworkStabilityEventType.INTERNET_RESTORED,
            tracker.onWifiInternetValidationChanged(true, 300L)?.type
        )
    }

    @Test fun ignoresInternetChangesWhenWifiIsUnavailable() {
        val tracker = NetworkStabilityTracker()
        assertNull(tracker.onWifiInternetValidationChanged(false, 100L))
    }

    @Test fun recordsDefaultNetworkTransitionsOnlyAfterInitialState() {
        val tracker = NetworkStabilityTracker()
        assertNull(tracker.onDefaultTransportChanged("Wi-Fi", 100L))
        assertNull(tracker.onDefaultTransportChanged("Wi-Fi", 200L))
        val event = tracker.onDefaultTransportChanged("4G", 300L)
        assertEquals(NetworkStabilityEventType.DEFAULT_NETWORK_CHANGED, event?.type)
        assertEquals("Réseau actif passé de Wi-Fi à 4G", event?.details)
    }
}
