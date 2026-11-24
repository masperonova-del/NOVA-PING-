package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileDataLockResolverTest {
    @Test fun disabledLockDoesNotClaimPreference() {
        val result = MobileDataLockResolver.resolve(
            enabled = false,
            activeTransport = "Wi-Fi",
            activeInternetValidated = true,
            mobileNetworkAvailable = true,
            mobileInternetValidated = true
        )
        assertEquals(MobileDataLockState.DISABLED, result.state)
    }

    @Test fun reportsValidatedMobileAsPreferredNetworkInUse() {
        val result = MobileDataLockResolver.resolve(
            enabled = true,
            activeTransport = "Données mobiles",
            activeInternetValidated = true,
            mobileNetworkAvailable = true,
            mobileInternetValidated = true
        )
        assertEquals(MobileDataLockState.MOBILE_ACTIVE_VALIDATED, result.state)
    }

    @Test fun detectsWifiFallbackWhileMobileRemainsAvailable() {
        val result = MobileDataLockResolver.resolve(
            enabled = true,
            activeTransport = "Wi-Fi",
            activeInternetValidated = true,
            mobileNetworkAvailable = true,
            mobileInternetValidated = true
        )
        assertEquals(MobileDataLockState.WIFI_ACTIVE_MOBILE_AVAILABLE, result.state)
    }

    @Test fun doesNotClaimMobileCanBeUsedWhenUnavailable() {
        val result = MobileDataLockResolver.resolve(
            enabled = true,
            activeTransport = "Wi-Fi",
            activeInternetValidated = true,
            mobileNetworkAvailable = false,
            mobileInternetValidated = null
        )
        assertEquals(MobileDataLockState.WIFI_ACTIVE_MOBILE_UNAVAILABLE, result.state)
    }

    @Test fun doesNotInferPhysicalTransportBehindVpn() {
        val result = MobileDataLockResolver.resolve(
            enabled = true,
            activeTransport = "VPN",
            activeInternetValidated = true,
            mobileNetworkAvailable = true,
            mobileInternetValidated = true
        )
        assertEquals(MobileDataLockState.VPN_ACTIVE, result.state)
    }

    @Test fun distinguishesMobileTransportWithoutValidatedInternet() {
        val result = MobileDataLockResolver.resolve(
            enabled = true,
            activeTransport = "Données mobiles",
            activeInternetValidated = false,
            mobileNetworkAvailable = true,
            mobileInternetValidated = false
        )
        assertEquals(MobileDataLockState.MOBILE_ACTIVE_UNVALIDATED, result.state)
    }
}
