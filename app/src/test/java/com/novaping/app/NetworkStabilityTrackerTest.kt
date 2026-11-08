package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkStabilityTrackerTest {
    @Test fun recordsPeriodicWifiAndInternetSnapshot() {
        val tracker = NetworkStabilityTracker()
        val event = tracker.periodicSnapshot(
            wifiAvailable = true,
            internetValidated = true,
            activeTransport = "Wi-Fi",
            activeInternetAvailable = true,
            activeInternetValidated = true,
            timestampMillis = 60_000L
        )
        assertEquals(NetworkStabilityEventType.PERIODIC_STATUS, event.type)
        assertEquals(
            "État périodique : réseau actif Wi-Fi · Internet validé par Android | Wi-Fi présent et validé",
            event.details
        )
        assertEquals(60_000L, event.timestampMillis)
    }

    @Test fun recordsPeriodicSnapshotWhenWifiHasNoValidatedInternet() {
        val tracker = NetworkStabilityTracker()
        val event = tracker.periodicSnapshot(
            wifiAvailable = true,
            internetValidated = false,
            activeTransport = "Wi-Fi",
            activeInternetAvailable = true,
            activeInternetValidated = false,
            timestampMillis = 120_000L
        )
        assertEquals(
            "État périodique : réseau actif Wi-Fi · Internet non validé par Android | Wi-Fi présent mais non validé",
            event.details
        )
    }

    @Test fun recordsPeriodicSnapshotWhenWifiIsUnavailable() {
        val tracker = NetworkStabilityTracker()
        val event = tracker.periodicSnapshot(
            wifiAvailable = false,
            internetValidated = null,
            activeTransport = "Aucun",
            activeInternetAvailable = false,
            activeInternetValidated = false,
            timestampMillis = 180_000L
        )
        assertEquals(
            "État périodique : réseau actif aucun réseau actif | Wi-Fi absent",
            event.details
        )
    }


    @Test fun reportsMobileDataAsActiveWhenWifiIsUnavailable() {
        val tracker = NetworkStabilityTracker()
        val event = tracker.periodicSnapshot(
            wifiAvailable = false,
            internetValidated = null,
            activeTransport = "Données mobiles",
            activeInternetAvailable = true,
            activeInternetValidated = true,
            timestampMillis = 240_000L
        )
        assertEquals(
            "État périodique : réseau actif Données mobiles · Internet validé par Android | Wi-Fi absent",
            event.details
        )
    }


    @Test fun recordsMobileDataAvailabilityAndInternetValidation() {
        val tracker = NetworkStabilityTracker()
        assertEquals(
            NetworkStabilityEventType.MOBILE_DATA_CONNECTED,
            tracker.onMobileAvailabilityChanged(true, 100L)?.type
        )
        assertEquals(
            NetworkStabilityEventType.MOBILE_INTERNET_STATUS,
            tracker.onMobileInternetValidationChanged(true, 120L)?.type
        )
        assertNull(tracker.onMobileInternetValidationChanged(true, 130L))
        assertEquals(
            NetworkStabilityEventType.MOBILE_DATA_DISCONNECTED,
            tracker.onMobileAvailabilityChanged(false, 200L)?.type
        )
    }

    @Test fun synchronizesMobileNetworkAtMonitorStartWithoutFalseTransition() {
        val tracker = NetworkStabilityTracker()
        val initial = tracker.synchronizeMobileState(true, true, 100L)
        assertEquals(2, initial.size)
        assertEquals(
            "État initial : réseau de données mobiles disponible",
            initial.first().details
        )
        assertNull(tracker.onMobileAvailabilityChanged(true, 200L))
    }

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
        assertEquals(
            NetworkStabilityEventType.INTERNET_STATUS,
            tracker.onWifiInternetValidationChanged(true, 110L)?.type
        )
        assertEquals(
            NetworkStabilityEventType.INTERNET_LOST,
            tracker.onWifiInternetValidationChanged(false, 200L)?.type
        )
        assertEquals(
            NetworkStabilityEventType.INTERNET_RESTORED,
            tracker.onWifiInternetValidationChanged(true, 300L)?.type
        )
    }

    @Test fun doesNotCallInitialValidationARecovery() {
        val tracker = NetworkStabilityTracker()
        tracker.onWifiAvailabilityChanged(true, 100L)
        assertEquals(
            NetworkStabilityEventType.INTERNET_STATUS,
            tracker.onWifiInternetValidationChanged(false, 110L)?.type
        )
        assertEquals(
            NetworkStabilityEventType.INTERNET_STATUS,
            tracker.onWifiInternetValidationChanged(true, 200L)?.type
        )
    }

    @Test fun ignoresInternetChangesWhenWifiIsUnavailable() {
        val tracker = NetworkStabilityTracker()
        assertNull(tracker.onWifiInternetValidationChanged(false, 100L))
    }

    @Test fun reportsWifiRecoveryDurationWhenBothTransitionsWereObserved() {
        val tracker = NetworkStabilityTracker()
        tracker.onWifiAvailabilityChanged(true, 1_000L)
        tracker.onWifiAvailabilityChanged(false, 5_000L)
        val restored = tracker.onWifiAvailabilityChanged(true, 8_500L)
        assertEquals("Wi-Fi rétabli après 3.5 s", restored?.details)
    }

    @Test fun doesNotInventWifiRecoveryDurationAcrossMonitoringPause() {
        val tracker = NetworkStabilityTracker()
        tracker.synchronizeWifiState(true, true, 1_000L)
        tracker.onWifiAvailabilityChanged(false, 2_000L)
        val resumeEvents = tracker.synchronizeWifiState(true, true, 20_000L)
        assertEquals(2, resumeEvents.size)
        assertEquals(
            "Wi-Fi disponible à la reprise ; heure de retour inconnue",
            resumeEvents.first().details
        )
        assertEquals(
            "État Internet du Wi-Fi : validé par Android",
            resumeEvents.last().details
        )
        val nextLoss = tracker.onWifiAvailabilityChanged(false, 21_000L)
        val nextRecovery = tracker.onWifiAvailabilityChanged(true, 22_000L)
        assertEquals("Wi-Fi rétabli après 1.0 s", nextRecovery?.details)
        assertEquals(NetworkStabilityEventType.WIFI_DISCONNECTED, nextLoss?.type)
    }

    @Test fun unchangedSnapshotOnResumeDoesNotCreateDuplicateConnectedEvents() {
        val tracker = NetworkStabilityTracker()
        val first = tracker.synchronizeWifiState(true, true, 1_000L)
        val second = tracker.synchronizeWifiState(true, true, 5_000L)
        assertEquals(2, first.size)
        assertEquals(0, second.size)
    }

    @Test fun reportsInternetRecoveryDurationOnlyWhenObservedContinuously() {
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

    @Test fun defaultTransportSnapshotPreventsFalseTransitionOnResume() {
        val tracker = NetworkStabilityTracker()
        tracker.synchronizeDefaultTransport("Wi-Fi")
        assertNull(tracker.onDefaultTransportChanged("Wi-Fi", 200L))
        val event = tracker.onDefaultTransportChanged("Données mobiles", 300L)
        assertEquals(NetworkStabilityEventType.DEFAULT_NETWORK_CHANGED, event?.type)
    }
}
