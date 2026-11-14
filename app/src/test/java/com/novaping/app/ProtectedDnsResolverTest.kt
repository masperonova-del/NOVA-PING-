package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProtectedDnsResolverTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun answer(query: ByteArray, address: ByteArray): ByteArray {
        val response = query.copyOf(query.size + 12 + address.size)
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
        return response
    }

    @Test
    fun resolvesOverProtectedUdpAndCachesValidatedAnswer() {
        DatagramSocket(null).use { server ->
            server.bind(InetSocketAddress(loopback, 0))
            val serverThread = thread(isDaemon = true) {
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                server.receive(packet)
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val response = answer(query, byteArrayOf(9, 9, 9, 9))
                server.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }
            var protected = 0
            val cache = DnsAnswerCache()
            val resolver = ProtectedDnsResolver(
                resolverAddress = loopback,
                protectDatagramSocket = { protected++; true },
                protectTcpSocket = { false },
                resolverPort = server.localPort,
                cache = cache,
                transactionIds = { 0x2345 }
            )
            val result = resolver.resolve("Example.com.", DnsRecordType.A)
            assertNotNull(result)
            assertEquals(1, result!!.size)
            assertArrayEquals(byteArrayOf(9, 9, 9, 9), result[0].addressBytes)
            assertEquals(1, protected)
            assertEquals(1, cache.size())
            serverThread.join(2_000)
        }
    }

    @Test
    fun retriesTruncatedUdpResponseOverProtectedTcp() {
        DatagramSocket(null).use { udp ->
            udp.bind(InetSocketAddress(loopback, 0))
            ServerSocket(udp.localPort, 1, loopback).use { tcp ->
                val udpThread = thread(isDaemon = true) {
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)
                    val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val truncated = query.copyOf().apply {
                        this[2] = 0x83.toByte()
                        this[3] = 0x80.toByte()
                    }
                    udp.send(DatagramPacket(truncated, truncated.size, packet.address, packet.port))
                }
                val tcpThread = thread(isDaemon = true) {
                    tcp.accept().use { client ->
                        val input = DataInputStream(client.getInputStream())
                        val length = input.readUnsignedShort()
                        val query = ByteArray(length).also(input::readFully)
                        val response = answer(query, byteArrayOf(1, 2, 3, 4))
                        val output = DataOutputStream(client.getOutputStream())
                        output.writeShort(response.size)
                        output.write(response)
                        output.flush()
                    }
                }
                var udpProtected = 0
                var tcpProtected = 0
                val resolver = ProtectedDnsResolver(
                    resolverAddress = loopback,
                    protectDatagramSocket = { udpProtected++; true },
                    protectTcpSocket = { tcpProtected++; true },
                    resolverPort = udp.localPort,
                    transactionIds = { 0x4567 }
                )
                val result = resolver.resolve("fallback.example", DnsRecordType.A)
                assertNotNull(result)
                assertEquals(1, result!!.size)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), result[0].addressBytes)
                assertEquals(1, udpProtected)
                assertEquals(1, tcpProtected)
                udpThread.join(2_000)
                tcpThread.join(2_000)
            }
        }
    }

    @Test
    fun refusesToSendWhenUdpSocketProtectionFails() {
        DatagramSocket(null).use { server ->
            server.bind(InetSocketAddress(loopback, 0))
            server.soTimeout = 250
            val resolver = ProtectedDnsResolver(
                resolverAddress = loopback,
                protectDatagramSocket = { false },
                protectTcpSocket = { false },
                resolverPort = server.localPort,
                transactionIds = { 7 }
            )
            assertNull(resolver.resolve("blocked.example", DnsRecordType.A))
            try {
                server.receive(DatagramPacket(ByteArray(512), 512))
                throw AssertionError("Resolver must not send before socket protection succeeds")
            } catch (_: java.net.SocketTimeoutException) {
                assertTrue(true)
            }
        }
    }
}
