package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiLockPolicyTest {
    private fun snapshot(
        preferredSsid: String? = "Home-WiFi",
        currentSsid: String? = "Home-WiFi",
        preferredAvailable: Boolean = true,
        preferredInternetValidated: Boolean = true,
        currentInternetValidated: Boolean = true,
        alternativeAvailable: Boolean = false
    ) = WifiLockSnapshot(
        preferredSsid = preferredSsid,
        currentSsid = currentSsid,
        preferredAvailable = preferredAvailable,
        preferredInternetValidated = preferredInternetValidated,
        currentInternetValidated = currentInternetValidated,
        alternativeAvailable = alternativeAvailable
    )

    @Test
    fun manualModeKeepsPreferredNetworkWhileAvailableEvenIfInternetIsNotValidated() {
        assertEquals(
            WifiLockDecision.KEEP_CURRENT,
            WifiLockPolicy.decide(
                WifiLockMode.MANUAL,
                snapshot(preferredInternetValidated = false, currentInternetValidated = false)
            )
        )
    }

    @Test
    fun manualModeRequestsPreferredNetworkWhenItIsAvailableButNotCurrent() {
        assertEquals(
            WifiLockDecision.REQUEST_PREFERRED_NETWORK,
            WifiLockPolicy.decide(
                WifiLockMode.MANUAL,
                snapshot(currentSsid = "Guest-WiFi")
            )
        )
    }

    @Test
    fun manualModeWaitsWhenPreferredNetworkIsUnavailable() {
        assertEquals(
            WifiLockDecision.WAIT_FOR_PREFERRED_NETWORK,
            WifiLockPolicy.decide(
                WifiLockMode.MANUAL,
                snapshot(preferredAvailable = false, currentSsid = "Guest-WiFi")
            )
        )
    }

    @Test
    fun smartModeKeepsPreferredNetworkWhenItIsUsable() {
        assertEquals(
            WifiLockDecision.KEEP_CURRENT,
            WifiLockPolicy.decide(WifiLockMode.SMART, snapshot())
        )
    }

    @Test
    fun smartModeKeepsUsableTemporaryNetworkWhilePreferredIsUnusable() {
        assertEquals(
            WifiLockDecision.KEEP_TEMPORARY_NETWORK,
            WifiLockPolicy.decide(
                WifiLockMode.SMART,
                snapshot(
                    currentSsid = "Guest-WiFi",
                    preferredInternetValidated = false,
                    alternativeAvailable = true
                )
            )
        )
    }

    @Test
    fun smartModeRequestsAlternativeOnlyWhenCurrentAndPreferredAreUnusable() {
        assertEquals(
            WifiLockDecision.REQUEST_TEMPORARY_ALTERNATIVE,
            WifiLockPolicy.decide(
                WifiLockMode.SMART,
                snapshot(
                    preferredInternetValidated = false,
                    currentInternetValidated = false,
                    alternativeAvailable = true
                )
            )
        )
    }

    @Test
    fun smartModeRequestsReturnToPreferredWhenItBecomesUsableAgain() {
        assertEquals(
            WifiLockDecision.REQUEST_PREFERRED_NETWORK,
            WifiLockPolicy.decide(
                WifiLockMode.SMART,
                snapshot(
                    currentSsid = "Guest-WiFi",
                    preferredInternetValidated = true,
                    currentInternetValidated = true,
                    alternativeAvailable = true
                )
            )
        )
    }

    @Test
    fun missingPreferredNetworkRequiresConfiguration() {
        assertEquals(
            WifiLockDecision.CONFIGURE_PREFERRED_NETWORK,
            WifiLockPolicy.decide(
                WifiLockMode.SMART,
                snapshot(preferredSsid = " ")
            )
        )
    }
}
