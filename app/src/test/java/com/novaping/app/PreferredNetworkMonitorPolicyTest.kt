package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredNetworkMonitorPolicyTest {
    @Test
    fun stopsWhenSelectedCellularNetworkLosesRequiredCapabilities() {
        assertTrue(
            PreferredNetworkMonitorPolicy.shouldStop(
                isSelectedNetwork = true,
                mobileLockEnabled = true,
                tunnelEstablished = true
            )
        )
    }

    @Test
    fun ignoresChangesToOtherCellularNetworks() {
        assertFalse(
            PreferredNetworkMonitorPolicy.shouldStop(
                isSelectedNetwork = false,
                mobileLockEnabled = true,
                tunnelEstablished = true
            )
        )
    }

    @Test
    fun doesNotStopWhenMobileLockIsDisabled() {
        assertFalse(
            PreferredNetworkMonitorPolicy.shouldStop(
                isSelectedNetwork = true,
                mobileLockEnabled = false,
                tunnelEstablished = true
            )
        )
    }

    @Test
    fun ignoresCallbackBeforeTunnelIsEstablished() {
        assertFalse(
            PreferredNetworkMonitorPolicy.shouldStop(
                isSelectedNetwork = true,
                mobileLockEnabled = true,
                tunnelEstablished = false
            )
        )
    }
}
