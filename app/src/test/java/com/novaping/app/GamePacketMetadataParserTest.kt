package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class GamePacketMetadataParserTest {
    private val localV4 = "10.44.0.2"
    private val remoteV4 = "1.1.1.1"
    private val localV6 = "fd00:44::2"
    private val remoteV6 = "2606:4700:4700::1111"

    @Test fun parsesOutboundIpv4UdpWithoutRetainingPayload() {
        val packet = ipv4Udp(localV4, remoteV4, 54000, 7500)

        val result = GamePacketMetadataParser.parse(packet, setOf(localV4))

        assertEquals(4, result?.ipVersion)
        assertEquals(remoteV4, result?.remoteAddress)
        assertEquals(7500, result?.remotePort)
        assertEquals(GameEndpointTransport.UDP, result?.transport)
        assertEquals(GamePacketDirection.OUTBOUND, result?.direction)
        assertFalse(result?.fragmented ?: true)
        assertEquals(packet.size, result?.packetLengthBytes)
    }

    @Test fun parsesInboundIpv4TcpUsingRemoteSourcePort() {
        val packet = ipv4Tcp(remoteV4, localV4, 443, 54000)

        val result = GamePacketMetadataParser.parse(packet, setOf(localV4))

        assertEquals(remoteV4, result?.remoteAddress)
        assertEquals(443, result?.remotePort)
        assertEquals(GameEndpointTransport.TCP, result?.transport)
        assertEquals(GamePacketDirection.INBOUND, result?.direction)
    }

    @Test fun parsesIpv6UdpWithExtensionHeader() {
        val packet = ipv6UdpWithDestinationOptions(localV6, remoteV6, 53000, 7500)

        val result = GamePacketMetadataParser.parse(packet, setOf("fd00:44:0:0:0:0:0:2"))

        assertEquals(6, result?.ipVersion)
        assertEquals(7500, result?.remotePort)
        assertEquals(GameEndpointTransport.UDP, result?.transport)
        assertEquals(GamePacketDirection.OUTBOUND, result?.direction)
    }

    @Test fun identifiesFirstIpv4FragmentButRejectsNonInitialFragment() {
        val first = ipv4Udp(localV4, remoteV4, 53000, 7500).apply {
            writeU16(this, 6, 0x2000)
        }
        val later = ipv4Udp(localV4, remoteV4, 53000, 7500).apply {
            writeU16(this, 6, 0x0001)
        }

        assertTrue(GamePacketMetadataParser.parse(first, setOf(localV4))?.fragmented == true)
        assertNull(GamePacketMetadataParser.parse(later, setOf(localV4)))
    }

    @Test fun rejectsUnsupportedProtocolsPrivateDestinationsAndUnknownLocalSide() {
        val tcp = ipv4Tcp(localV4, remoteV4, 54000, 443)
        tcp[9] = 1 // ICMP is not a TCP/UDP socket observation.
        assertNull(GamePacketMetadataParser.parse(tcp, setOf(localV4)))

        assertNull(GamePacketMetadataParser.parse(
            ipv4Udp(localV4, "192.168.1.10", 54000, 7500), setOf(localV4)
        ))
        assertNull(GamePacketMetadataParser.parse(
            ipv4Udp(localV4, remoteV4, 54000, 7500), setOf("10.44.0.3")
        ))
    }

    @Test fun rejectsTruncatedAndMalformedPackets() {
        assertNull(GamePacketMetadataParser.parse(ByteArray(12), setOf(localV4)))

        val truncated = ipv4Udp(localV4, remoteV4, 54000, 7500)
        writeU16(truncated, 2, 80)
        assertNull(GamePacketMetadataParser.parse(truncated, setOf(localV4)))

        val invalidUdpLength = ipv4Udp(localV4, remoteV4, 54000, 7500)
        writeU16(invalidUdpLength, 24, 4)
        assertNull(GamePacketMetadataParser.parse(invalidUdpLength, setOf(localV4)))
    }

    @Test fun rejectsNonInitialIpv6Fragments() {
        val packet = ipv6UdpWithFragmentHeader(localV6, remoteV6, 53000, 7500, 0x0008)
        assertNull(GamePacketMetadataParser.parse(packet, setOf(localV6)))
    }

    @Test fun acceptsOnlyKnownNumericLocalAddresses() {
        val packet = ipv4Udp(localV4, remoteV4, 54000, 7500)
        assertNull(GamePacketMetadataParser.parse(packet, setOf("localhost")))
        assertNull(GamePacketMetadataParser.parse(packet, emptySet()))
    }

    private fun ipv4Udp(
        source: String,
        destination: String,
        sourcePort: Int,
        destinationPort: Int
    ): ByteArray {
        val packet = ByteArray(28)
        packet[0] = 0x45
        writeU16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        copyAddress(source, packet, 12)
        copyAddress(destination, packet, 16)
        writeU16(packet, 20, sourcePort)
        writeU16(packet, 22, destinationPort)
        writeU16(packet, 24, 8)
        return packet
    }

    private fun ipv4Tcp(
        source: String,
        destination: String,
        sourcePort: Int,
        destinationPort: Int
    ): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45
        writeU16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 6
        copyAddress(source, packet, 12)
        copyAddress(destination, packet, 16)
        writeU16(packet, 20, sourcePort)
        writeU16(packet, 22, destinationPort)
        packet[32] = 0x50
        return packet
    }

    private fun ipv6UdpWithDestinationOptions(
        source: String,
        destination: String,
        sourcePort: Int,
        destinationPort: Int
    ): ByteArray {
        val packet = ByteArray(56)
        packet[0] = 0x60
        writeU16(packet, 4, 16)
        packet[6] = 60 // Destination Options
        packet[7] = 64
        copyAddress(source, packet, 8)
        copyAddress(destination, packet, 24)
        packet[40] = 17
        packet[41] = 0 // Eight-byte extension header
        writeU16(packet, 48, sourcePort)
        writeU16(packet, 50, destinationPort)
        writeU16(packet, 52, 8)
        return packet
    }

    private fun ipv6UdpWithFragmentHeader(
        source: String,
        destination: String,
        sourcePort: Int,
        destinationPort: Int,
        fragmentField: Int
    ): ByteArray {
        val packet = ByteArray(56)
        packet[0] = 0x60
        writeU16(packet, 4, 16)
        packet[6] = 44 // Fragment
        packet[7] = 64
        copyAddress(source, packet, 8)
        copyAddress(destination, packet, 24)
        packet[40] = 17
        writeU16(packet, 42, fragmentField)
        writeU16(packet, 48, sourcePort)
        writeU16(packet, 50, destinationPort)
        writeU16(packet, 52, 8)
        return packet
    }

    private fun copyAddress(address: String, target: ByteArray, offset: Int) {
        val bytes = InetAddress.getByName(address).address
        System.arraycopy(bytes, 0, target, offset, bytes.size)
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }
}
