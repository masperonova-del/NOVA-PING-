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

    @Test fun reportsWifiRecoveryDuration() {
        val tracker = NetworkStabilityTracker()
        tracker.onWifiAvailabilityChanged(true, 1_000L)
        tracker.onWifiAvailabilityChanged(false, 5_000L)
        val restored = tracker.onWifiAvailabilityChanged(true, 8_500L)
        assertEquals("Wi-Fi rétabli après 3.5 s", restored?.details)
    }

    @Test fun reportsInternetRecoveryDurationWithoutWifiDisconnect() {
        val tracker = NetworkStabilityTracker()
        tracker.onWifiAvailabilityChanged(true, 1_000L)
        tracker.onWifiInternetValidationChanged(true, 1_100L)
        tracker.onWifiInternetValidationChanged(false, 2_000L)
        val restored = tracker.onWifiInternetValidationChanged(true, 6_000L)
        assertEquals("Internet rétabli après 4.0 s", restored?.details)
    }

    @Test fun resetStartsANewMonitoringBaseline() {
        val tracker = NetworkStabilityTracker()
        tracker.onWifiAvailabilityChanged(true, 100L)
        tracker.onWifiAvailabilityChanged(false, 200L)
        tracker.reset()
        assertEquals(
            "Réseau Wi-Fi détecté par Android",
            tracker.onWifiAvailabilityChanged(true, 300L)?.details
        )
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
