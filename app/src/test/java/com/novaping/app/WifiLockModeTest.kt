package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLockModeTest {
    @Test fun defaultsUnknownPreferenceToManualMode() {
        assertTrue(WifiLockMode.fromPreference(null) == WifiLockMode.MANUAL)
        assertTrue(WifiLockMode.fromPreference("manual") == WifiLockMode.MANUAL)
    }

    @Test fun restoresSmartModeFromPreference() {
        assertTrue(WifiLockMode.fromPreference("smart") == WifiLockMode.SMART)
    }

    @Test fun preferredNetworkIsActiveOnlyWhenSsidMatches() {
        val snapshot = WifiLockSnapshot("Home", "Home", true, true)
        assertTrue(snapshot.preferredNetworkIsActive())
        assertFalse(snapshot.copy(activeSsid = "Guest").preferredNetworkIsActive())
    }

    @Test fun preferredNetworkRequiresWifiAndValidatedInternetToBeUsable() {
        val healthy = WifiLockSnapshot("Home", "Home", true, true)
        assertTrue(healthy.preferredNetworkIsUsable())
        assertFalse(healthy.copy(wifiAvailable = false).preferredNetworkIsUsable())
        assertFalse(healthy.copy(internetValidated = false).preferredNetworkIsUsable())
        assertFalse(healthy.copy(activeSsid = "Guest").preferredNetworkIsUsable())
    }
}
