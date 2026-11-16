package com.novaping.app

import org.junit.Assert.assertTrue
import org.junit.Test

class HevTunnelConfigTest {
    private val credentials = Socks5BridgeCredentials(
        username = "a".repeat(48),
        password = "b".repeat(48)
    )

    @Test
    fun configTargetsOnlyAuthenticatedLoopbackBridge() {
        val config = HevTunnelConfig.create(45678, credentials)

        assertTrue(config.contains("address: 127.0.0.1"))
        assertTrue(config.contains("port: 45678"))
        assertTrue(config.contains("username: '${credentials.username}'"))
        assertTrue(config.contains("password: '${credentials.password}'"))
        assertTrue(config.contains("ipv4: 198.18.0.1"))
        assertTrue(config.contains("ipv6: 'fc00::1'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPort() {
        HevTunnelConfig.create(0, credentials)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsYamlInjectionInCredentials() {
        HevTunnelConfig.create(
            1080,
            Socks5BridgeCredentials("user\n  address: 0.0.0.0", "password")
        )
    }
}
