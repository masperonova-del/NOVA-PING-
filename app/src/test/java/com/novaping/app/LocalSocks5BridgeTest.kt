package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalSocks5BridgeTest {
    @Test
    fun tcpConnectRelaysDataThroughLoopbackBridge() {
        val loopback = InetAddress.getLoopbackAddress()
        val destination = ServerSocket(0, 1, loopback)
        val echoThread = thread(isDaemon = true) {
            destination.accept().use { peer ->
                val bytes = ByteArray(4)
                val count = peer.getInputStream().read(bytes)
                if (count > 0) peer.getOutputStream().apply {
                    write(bytes, 0, count)
                    flush()
                }
            }
        }

        var protectCalls = 0
        val bridge = LocalSocks5Bridge(
            protectUpstreamSocket = {
                protectCalls++
                true
            },
            bindAddress = loopback
        )
        try {
            val port = bridge.start()
            Socket(loopback, port).use { client ->
                client.soTimeout = 5_000
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())

                val credentials = bridge.credentials
                val username = credentials.username.toByteArray(Charsets.UTF_8)
                val password = credentials.password.toByteArray(Charsets.UTF_8)
                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(2, input.readUnsignedByte())
                output.writeByte(1)
                output.writeByte(username.size)
                output.write(username)
                output.writeByte(password.size)
                output.write(password)
                output.flush()
                assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())

                val address = loopback.address
                output.writeByte(5)
                output.writeByte(1)
                output.writeByte(0)
                output.writeByte(if (address.size == 16) 4 else 1)
                output.write(address)
                output.writeShort(destination.localPort)
                output.flush()

                assertEquals(5, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                val atyp = input.readUnsignedByte()
                val replyAddressLength = when (atyp) {
                    1 -> 4
                    4 -> 16
                    else -> error("Unexpected SOCKS5 reply address type: $atyp")
                }
                input.skipBytes(replyAddressLength + 2)

                val payload = "NOVA".toByteArray(Charsets.US_ASCII)
                output.write(payload)
                output.flush()
                val echoed = ByteArray(payload.size)
                input.readFully(echoed)
                assertArrayEquals(payload, echoed)
            }
            echoThread.join(2_000)
            assertTrue("Upstream socket must be protected before connect", protectCalls > 0)
        } finally {
            bridge.close()
            destination.close()
        }
    }

    @Test
    fun tcpConnectRejectsDomainWhenProtectedResolverIsNotConfigured() {
        val loopback = InetAddress.getLoopbackAddress()
        var protectCalls = 0
        val bridge = LocalSocks5Bridge(
            protectUpstreamSocket = {
                protectCalls++
                true
            },
            bindAddress = loopback
        )
        try {
            Socket(loopback, bridge.start()).use { client ->
                client.soTimeout = 3_000
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                val credentials = bridge.credentials
                val username = credentials.username.toByteArray(Charsets.UTF_8)
                val password = credentials.password.toByteArray(Charsets.UTF_8)

                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(2, input.readUnsignedByte())
                output.writeByte(1)
                output.writeByte(username.size)
                output.write(username)
                output.writeByte(password.size)
                output.write(password)
                output.flush()
                assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())

                val domain = "example.com".toByteArray(Charsets.US_ASCII)
                output.writeByte(5)
                output.writeByte(1)
                output.writeByte(0)
                output.writeByte(3)
                output.writeByte(domain.size)
                output.write(domain)
                output.writeShort(443)
                output.flush()

                assertEquals(5, input.readUnsignedByte())
                assertEquals(4, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                val replyType = input.readUnsignedByte()
                val replyAddressLength = when (replyType) {
                    1 -> 4
                    4 -> 16
                    else -> error("Unexpected SOCKS5 reply address type: $replyType")
                }
                input.skipBytes(replyAddressLength + 2)
            }
            assertEquals("Without a configured protected resolver, no upstream socket may be opened", 0, protectCalls)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun bridgeBindsOnlyToLoopbackAndStops() {
        val loopback = InetAddress.getLoopbackAddress()
        val bridge = LocalSocks5Bridge({ true }, loopback)
        val port = bridge.start()
        assertTrue(bridge.isRunning)
        assertTrue(bridge.localPort == port && port > 0)
        bridge.close()
        assertTrue(!bridge.isRunning)
        assertEquals(-1, bridge.localPort)
    }

    @Test
    fun udpAssociateRelaysUnfragmentedDatagramsAndProtectsUpstreamSocket() {
        val loopback = InetAddress.getLoopbackAddress()
        val echo = DatagramSocket(null).apply {
            bind(InetSocketAddress(loopback, 0))
            soTimeout = 5_000
        }
        val echoThread = thread(isDaemon = true) {
            val bytes = ByteArray(1024)
            val packet = DatagramPacket(bytes, bytes.size)
            echo.receive(packet)
            echo.send(DatagramPacket(packet.data, packet.offset, packet.length, packet.address, packet.port))
        }
        var protectedUdpSockets = 0
        val bridge = LocalSocks5Bridge(
            protectUpstreamSocket = { true },
            bindAddress = loopback,
            protectUpstreamDatagramSocket = {
                protectedUdpSockets++
                true
            }
        )
        try {
            val tcpPort = bridge.start()
            Socket(loopback, tcpPort).use { control ->
                control.soTimeout = 5_000
                val input = DataInputStream(control.getInputStream())
                val output = DataOutputStream(control.getOutputStream())
                val credentials = bridge.credentials
                val username = credentials.username.toByteArray(Charsets.UTF_8)
                val password = credentials.password.toByteArray(Charsets.UTF_8)

                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(2, input.readUnsignedByte())
                output.writeByte(1)
                output.writeByte(username.size)
                output.write(username)
                output.writeByte(password.size)
                output.write(password)
                output.flush()
                assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())

                // UDP ASSOCIATE with an unspecified client UDP endpoint.
                output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                val atyp = input.readUnsignedByte()
                val addressLength = when (atyp) {
                    1 -> 4
                    4 -> 16
                    else -> error("Unexpected UDP relay address type: $atyp")
                }
                val relayAddress = InetAddress.getByAddress(ByteArray(addressLength).also(input::readFully))
                val relayPort = input.readUnsignedShort()

                DatagramSocket(null).use { clientUdp ->
                    clientUdp.bind(InetSocketAddress(loopback, 0))
                    clientUdp.soTimeout = 5_000
                    val payload = "NOVA-UDP".toByteArray(Charsets.US_ASCII)
                    val frame = Socks5Codec.encodeUdpDatagram(
                        Socks5UdpDatagram(
                            Socks5Endpoint(Socks5Address.Ipv4(loopback.address), echo.localPort),
                            payload
                        )
                    )
                    clientUdp.send(DatagramPacket(frame, frame.size, relayAddress, relayPort))
                    val responseBytes = ByteArray(2048)
                    val response = DatagramPacket(responseBytes, responseBytes.size)
                    clientUdp.receive(response)
                    val decoded = Socks5Codec.parseUdpDatagram(
                        response.data.copyOfRange(response.offset, response.offset + response.length)
                    )
                    assertTrue(decoded != null)
                    assertArrayEquals(payload, decoded!!.payload)
                }
                assertTrue("UDP upstream socket must be protected", protectedUdpSockets > 0)
            }
            echoThread.join(2_000)
        } finally {
            bridge.close()
            echo.close()
        }
    }

    @Test
    fun udpRelayRejectsDatagramsFromUnadvertisedClientPort() {
        val loopback = InetAddress.getLoopbackAddress()
        val echo = DatagramSocket(null).apply {
            bind(InetSocketAddress(loopback, 0))
            soTimeout = 5_000
        }
        val echoThread = thread(isDaemon = true) {
            val bytes = ByteArray(1024)
            val packet = DatagramPacket(bytes, bytes.size)
            echo.receive(packet)
            echo.send(DatagramPacket(packet.data, packet.offset, packet.length, packet.address, packet.port))
        }
        DatagramSocket(null).use { allowedClient ->
            DatagramSocket(null).use { unadvertisedClient ->
                allowedClient.bind(InetSocketAddress(loopback, 0))
                unadvertisedClient.bind(InetSocketAddress(loopback, 0))
                val relay = LocalSocks5UdpRelay({ true }, loopback)
                try {
                    val relayPort = relay.start(loopback, allowedClient.localPort)
                    val frame = Socks5Codec.encodeUdpDatagram(
                        Socks5UdpDatagram(
                            Socks5Endpoint(Socks5Address.Ipv4(loopback.address), echo.localPort),
                            "PIN".toByteArray(Charsets.US_ASCII)
                        )
                    )
                    unadvertisedClient.soTimeout = 300
                    unadvertisedClient.send(DatagramPacket(frame, frame.size, loopback, relayPort))
                    val unexpected = DatagramPacket(ByteArray(128), 128)
                    try {
                        unadvertisedClient.receive(unexpected)
                        throw AssertionError("Datagram from an unadvertised UDP port must be rejected")
                    } catch (_: java.net.SocketTimeoutException) {
                        // Expected: the relay must not forward this source endpoint.
                    }

                    allowedClient.soTimeout = 5_000
                    allowedClient.send(DatagramPacket(frame, frame.size, loopback, relayPort))
                    val response = DatagramPacket(ByteArray(512), 512)
                    allowedClient.receive(response)
                    val decoded = Socks5Codec.parseUdpDatagram(
                        response.data.copyOfRange(response.offset, response.offset + response.length)
                    )
                    assertTrue(decoded != null)
                    assertArrayEquals("PIN".toByteArray(Charsets.US_ASCII), decoded!!.payload)
                } finally {
                    relay.close()
                }
            }
        }
        echoThread.join(2_000)
        echo.close()
    }

    @Test
    fun tcpConnectResolvesDomainThroughInjectedProtectedDnsResolver() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 1, loopback).use { destination ->
            val echoThread = thread(isDaemon = true) {
                destination.accept().use { peer ->
                    val buffer = ByteArray(4)
                    val count = peer.getInputStream().read(buffer)
                    if (count > 0) peer.getOutputStream().apply {
                        write(buffer, 0, count)
                        flush()
                    }
                }
            }
            DatagramSocket(null).use { dnsServer ->
                dnsServer.bind(InetSocketAddress(loopback, 0))
                val dnsThread = startDnsResponder(dnsServer, loopback.address)
                var dnsProtected = 0
                var upstreamProtected = 0
                val resolver = ProtectedDnsResolver(
                    resolverAddress = loopback,
                    protectDatagramSocket = { dnsProtected++; true },
                    protectTcpSocket = { false },
                    resolverPort = dnsServer.localPort,
                    transactionIds = { 0x3210 }
                )
                val bridge = LocalSocks5Bridge(
                    protectUpstreamSocket = { upstreamProtected++; true },
                    bindAddress = loopback,
                    protectedDnsResolver = resolver
                )
                try {
                    Socket(loopback, bridge.start()).use { client ->
                        client.soTimeout = 5_000
                        val input = DataInputStream(client.getInputStream())
                        val output = DataOutputStream(client.getOutputStream())
                        authenticateBridgeClient(input, output, bridge)

                        val domain = "nova-test.invalid".toByteArray(Charsets.US_ASCII)
                        output.writeByte(5)
                        output.writeByte(1)
                        output.writeByte(0)
                        output.writeByte(3)
                        output.writeByte(domain.size)
                        output.write(domain)
                        output.writeShort(destination.localPort)
                        output.flush()

                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        val replyType = input.readUnsignedByte()
                        val addressLength = when (replyType) {
                            1 -> 4
                            4 -> 16
                            else -> error("Unexpected SOCKS5 reply address type: $replyType")
                        }
                        input.skipBytes(addressLength + 2)
                        val payload = "DNS!".toByteArray(Charsets.US_ASCII)
                        output.write(payload)
                        output.flush()
                        val echoed = ByteArray(payload.size)
                        input.readFully(echoed)
                        assertArrayEquals(payload, echoed)
                    }
                    assertTrue("DNS UDP socket must be protected", dnsProtected > 0)
                    assertTrue("Resolved TCP upstream socket must be protected", upstreamProtected > 0)
                    dnsThread.join(2_000)
                    echoThread.join(2_000)
                } finally {
                    bridge.close()
                }
            }
        }
    }

    @Test
    fun udpAssociateResolvesDomainThroughInjectedProtectedDnsResolver() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        DatagramSocket(null).use { echo ->
            echo.bind(InetSocketAddress(loopback, 0))
            echo.soTimeout = 5_000
            val echoThread = thread(isDaemon = true) {
                val packet = DatagramPacket(ByteArray(512), 512)
                echo.receive(packet)
                echo.send(DatagramPacket(packet.data, packet.offset, packet.length, packet.address, packet.port))
            }
            DatagramSocket(null).use { dnsServer ->
                dnsServer.bind(InetSocketAddress(loopback, 0))
                val dnsThread = startDnsResponder(dnsServer, loopback.address)
                var dnsProtected = 0
                var upstreamUdpProtected = 0
                val resolver = ProtectedDnsResolver(
                    resolverAddress = loopback,
                    protectDatagramSocket = { dnsProtected++; true },
                    protectTcpSocket = { false },
                    resolverPort = dnsServer.localPort,
                    transactionIds = { 0x4321 }
                )
                val bridge = LocalSocks5Bridge(
                    protectUpstreamSocket = { true },
                    bindAddress = loopback,
                    protectUpstreamDatagramSocket = { upstreamUdpProtected++; true },
                    protectedDnsResolver = resolver
                )
                try {
                    Socket(loopback, bridge.start()).use { control ->
                        control.soTimeout = 5_000
                        val input = DataInputStream(control.getInputStream())
                        val output = DataOutputStream(control.getOutputStream())
                        authenticateBridgeClient(input, output, bridge)

                        output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        val replyType = input.readUnsignedByte()
                        val replyAddressLength = when (replyType) {
                            1 -> 4
                            4 -> 16
                            else -> error("Unexpected SOCKS5 reply address type: $replyType")
                        }
                        val relayAddress = InetAddress.getByAddress(ByteArray(replyAddressLength).also(input::readFully))
                        val relayPort = input.readUnsignedShort()

                        DatagramSocket(null).use { clientUdp ->
                            clientUdp.bind(InetSocketAddress(loopback, 0))
                            clientUdp.soTimeout = 5_000
                            val payload = "DNS-UDP".toByteArray(Charsets.US_ASCII)
                            val frame = Socks5Codec.encodeUdpDatagram(
                                Socks5UdpDatagram(
                                    Socks5Endpoint(Socks5Address.Domain("nova-test.invalid"), echo.localPort),
                                    payload
                                )
                            )
                            clientUdp.send(DatagramPacket(frame, frame.size, relayAddress, relayPort))
                            val response = DatagramPacket(ByteArray(1024), 1024)
                            clientUdp.receive(response)
                            val decoded = Socks5Codec.parseUdpDatagram(
                                response.data.copyOfRange(response.offset, response.offset + response.length)
                            )
                            assertTrue(decoded != null)
                            assertArrayEquals(payload, decoded!!.payload)
                        }
                        assertTrue("DNS UDP socket must be protected", dnsProtected > 0)
                        assertTrue("Resolved UDP upstream socket must be protected", upstreamUdpProtected > 0)
                        dnsThread.join(2_000)
                        echoThread.join(2_000)
                    }
                } finally {
                    bridge.close()
                }
            }
        }
    }

    private fun authenticateBridgeClient(
        input: DataInputStream,
        output: DataOutputStream,
        bridge: LocalSocks5Bridge
    ) {
        val username = bridge.credentials.username.toByteArray(Charsets.UTF_8)
        val password = bridge.credentials.password.toByteArray(Charsets.UTF_8)
        output.write(byteArrayOf(5, 1, 2))
        output.flush()
        assertEquals(5, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        output.writeByte(1)
        output.writeByte(username.size)
        output.write(username)
        output.writeByte(password.size)
        output.write(password)
        output.flush()
        assertEquals(1, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
    }

    private fun startDnsResponder(server: DatagramSocket, address: ByteArray): Thread =
        thread(isDaemon = true) {
            val packet = DatagramPacket(ByteArray(512), 512)
            server.receive(packet)
            val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val response = query.copyOf(query.size + 16)
            response[2] = 0x81.toByte()
            response[3] = 0x80.toByte()
            response[6] = 0
            response[7] = 1
            response[8] = 0
            response[9] = 0
            response[10] = 0
            response[11] = 0
            var offset = query.size
            response[offset++] = 0xc0.toByte()
            response[offset++] = 0x0c
            response[offset++] = 0
            response[offset++] = 1
            response[offset++] = 0
            response[offset++] = 1
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 0
            response[offset++] = 45
            response[offset++] = 0
            response[offset++] = address.size.toByte()
            address.copyInto(response, offset)
            server.send(DatagramPacket(response, response.size, packet.address, packet.port))
        }

}
