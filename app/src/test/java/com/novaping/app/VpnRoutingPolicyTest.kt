package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutingPolicyTest {
    @Test
    fun onlyAllowlistedGameUsesVpnAndAppUpstreamSocketsDoNotNeedProtect() {
        val allowedPackages = setOf("com.activision.callofduty.shooter")

        assertTrue(
            VpnRoutingPolicy.isPackageRoutedThroughVpn(
                "com.activision.callofduty.shooter",
                allowedPackages
            )
        )
        assertFalse(
            VpnRoutingPolicy.isPackageRoutedThroughVpn(
                "com.novaping.app",
                allowedPackages
            )
        )
        assertFalse(
            VpnRoutingPolicy.requiresSocketProtection(
                "com.novaping.app",
                allowedPackages
            )
        )
    }

    @Test
    fun emptyAllowlistRoutesEveryAppThroughVpn() {
        assertTrue(
            VpnRoutingPolicy.requiresSocketProtection(
                "com.novaping.app",
                emptySet()
            )
        )
    }

    @Test
    fun appIncludedInAllowlistRequiresSocketProtection() {
        assertTrue(
            VpnRoutingPolicy.requiresSocketProtection(
                "com.novaping.app",
                setOf("com.novaping.app", "com.activision.callofduty.shooter")
            )
        )
    }
}
