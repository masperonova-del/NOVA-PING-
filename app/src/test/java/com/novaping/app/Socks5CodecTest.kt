package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5CodecTest {
    @Test fun greetingOffersOnlyNoAuthentication() {
        val greeting = Socks5Codec.noAuthGreeting()
        assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
        assertTrue(Socks5Codec.acceptsNoAuthSelection(byteArrayOf(5, 0)))
        assertFalse(Socks5Codec.acceptsNoAuthSelection(byteArrayOf(5, 2)))
        assertFalse(Socks5Codec.acceptsNoAuthSelection(byteArrayOf(4, 0)))
        assertFalse(Socks5Codec.acceptsNoAuthSelection(byteArrayOf(5)))
    }

    @Test fun encodesConnectRequestForIpv4() {
        val endpoint = Socks5Endpoint(
            Socks5Address.Ipv4(byteArrayOf(1, 1, 1, 1)),
            443
        )
        assertArrayEquals(
            intArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 1, 187).map(Int::toByte).toByteArray(),
            Socks5Codec.commandRequest(Socks5Command.CONNECT, endpoint)
        )
    }

    @Test fun encodesUdpAssociateRequestForIpv6() {
        val address = Socks5Codec.addressFromHost("2606:4700:4700::1111")!!
        val request = Socks5Codec.commandRequest(
            Socks5Command.UDP_ASSOCIATE,
            Socks5Endpoint(address, 0)
        )
        assertEquals(5, request[0].toInt())
        assertEquals(3, request[1].toInt())
        assertEquals(4, request[3].toInt())
        assertEquals(22, request.size)
    }

    @Test fun encodesInternationalDomainToIdnaAscii() {
        val address = Socks5Codec.addressFromHost("例え.テスト")
        assertTrue(address is Socks5Address.Domain)
        assertEquals("xn--r8jz45g.xn--zckzah", (address as Socks5Address.Domain).asciiName)
    }

    @Test fun parsesIpv4CommandReplyAndRejectsMalformedReplies() {
        val reply = byteArrayOf(5, 0, 0, 1, 8, 8, 8, 8, 0x01, 0xbb.toByte())
        val parsed = Socks5Codec.parseCommandReply(reply)!!
        assertEquals(Socks5Reply.SUCCEEDED, parsed.first)
        assertEquals(443, parsed.second.port)
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), (parsed.second.address as Socks5Address.Ipv4).bytes)

        assertNull(Socks5Codec.parseCommandReply(reply.copyOf(8)))
        assertNull(Socks5Codec.parseCommandReply(reply + byteArrayOf(0)))
        assertNull(Socks5Codec.parseCommandReply(byteArrayOf(4, 0, 0, 1, 1, 1, 1, 1, 0, 80)))
        assertNull(Socks5Codec.parseCommandReply(byteArrayOf(5, 99, 0, 1, 1, 1, 1, 1, 0, 80)))
    }

    @Test fun parsesDomainCommandReply() {
        val bytes = intArrayOf(
            5, 0, 0, 3, 9,
            108, 111, 99, 97, 108, 104, 111, 115, 116,
            0x1f, 0x90
        ).map(Int::toByte).toByteArray()
        val parsed = Socks5Codec.parseCommandReply(bytes)!!
        assertEquals(Socks5Address.Domain("localhost"), parsed.second.address)
        assertEquals(8080, parsed.second.port)
    }

    @Test fun udpDatagramRoundTripsPayloadAndEndpoint() {
        val payload = byteArrayOf(10, 20, 30, 40)
        val original = Socks5UdpDatagram(
            Socks5Endpoint(Socks5Address.Ipv4(byteArrayOf(9, 9, 9, 9)), 7500),
            payload
        )
        val parsed = Socks5Codec.parseUdpDatagram(Socks5Codec.encodeUdpDatagram(original))!!
        assertEquals(original.destination, parsed.destination)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test fun rejectsFragmentedReservedAndTruncatedUdpDatagrams() {
        val valid = Socks5Codec.encodeUdpDatagram(
            Socks5UdpDatagram(
                Socks5Endpoint(Socks5Address.Ipv4(byteArrayOf(1, 1, 1, 1)), 53),
                byteArrayOf(1)
            )
        )
        assertNull(Socks5Codec.parseUdpDatagram(valid.copyOf().apply { this[2] = 1 }))
        assertNull(Socks5Codec.parseUdpDatagram(valid.copyOf().apply { this[0] = 1 }))
        assertNull(Socks5Codec.parseUdpDatagram(valid.copyOf(7)))
    }

    @Test fun addressParserAcceptsNumericLiteralsAndRejectsInvalidHosts() {
        assertTrue(Socks5Codec.addressFromHost("1.1.1.1") is Socks5Address.Ipv4)
        assertTrue(Socks5Codec.addressFromHost("2606:4700:4700::1111") is Socks5Address.Ipv6)
        assertNull(Socks5Codec.addressFromHost("999.1.1.1"))
        assertNull(Socks5Codec.addressFromHost("01.1.1.1"))
        assertNull(Socks5Codec.addressFromHost("bad host"))
        assertNull(Socks5Codec.addressFromHost(""))
    }
}
