package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameNetworkReferenceRegistryTest {
    @Test fun exposesOfficialCodmPortReferenceOnlyForCodm() {
        val references = GameNetworkReferenceRegistry.forGame("codm")
        assertEquals(1, references.size)
        assertTrue(GameNetworkReferenceRegistry.forGame("pubg").isEmpty())
        assertTrue(GameNetworkReferenceRegistry.isAdmissible(references.single()))
    }

    @Test fun recordsPublishedTcpAndUdpPortsWithoutInventingHosts() {
        val ports = GameNetworkReferenceRegistry.forGame("codm").single().ports
        assertEquals(
            listOf("TCP:80-80", "TCP:443-443", "TCP:65010-65010", "TCP:65050-65050", "UDP:7500-8000"),
            ports.map { "${it.protocol}:${it.firstPort}-${it.lastPort}" }
        )
        assertTrue(ports.all { it.purpose.isNotBlank() })
    }

    @Test fun rejectsNonOfficialOrNonHttpsSources() {
        val valid = GameNetworkReferenceRegistry.forGame("codm").single()
        assertFalse(GameNetworkReferenceRegistry.isAdmissible(
            valid.copy(sourceUrl = "http://support.activision.com/example")
        ))
        assertFalse(GameNetworkReferenceRegistry.isAdmissible(
            valid.copy(sourceUrl = "https://example.org/activision-ports")
        ))
    }

    @Test fun rejectsInvalidPortRanges() {
        val valid = GameNetworkReferenceRegistry.forGame("codm").single()
        val invalid = valid.copy(
            ports = listOf(PublishedGamePort("UDP", 8001, 7500, "Invalid range"))
        )
        assertFalse(GameNetworkReferenceRegistry.isAdmissible(invalid))
    }

    @Test fun rejectsUnsupportedProtocols() {
        val valid = GameNetworkReferenceRegistry.forGame("codm").single()
        val invalid = valid.copy(
            ports = listOf(PublishedGamePort("ICMP", 1, 1, "Unsupported"))
        )
        assertFalse(GameNetworkReferenceRegistry.isAdmissible(invalid))
    }
}
