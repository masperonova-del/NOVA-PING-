package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class LocalSocks5UdpRelayTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun forwardsRepeatedDatagramsAndMultipleDestinationsOnOneUpstreamSocket() {
        val observedSourcePorts = ConcurrentLinkedQueue<Int>()
        fun echoServer(expectedPackets: Int): Pair<DatagramSocket, Thread> {
            val server = DatagramSocket(null).apply {
                bind(InetSocketAddress(loopback, 0))
                soTimeout = 4_000
            }
            val worker = thread(isDaemon = true, name = "udp-echo-test") {
                repeat(expectedPackets) {
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    server.receive(packet)
                    observedSourcePorts.add(packet.port)
                    val response = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    server.send(DatagramPacket(response, response.size, packet.address, packet.port))
                }
            }
            return server to worker
        }

        val firstServer = echoServer(2)
        val secondServer = echoServer(1)
        DatagramSocket(null).use { client ->
            client.bind(InetSocketAddress(loopback, 0))
            client.soTimeout = 3_000
            val relay = LocalSocks5UdpRelay(
                protectUpstreamDatagramSocket = { true },
                bindAddress = loopback
            )
            try {
                relay.start(loopback, client.localPort)

                fun roundTrip(destination: DatagramSocket, payload: ByteArray) {
                    val frame = Socks5Codec.encodeUdpDatagram(
                        Socks5UdpDatagram(
                            Socks5Endpoint(
                                Socks5Address.Ipv4(byteArrayOf(127, 0, 0, 1)),
                                destination.localPort
                            ),
                            payload
                        )
                    )
                    client.send(DatagramPacket(frame, frame.size, loopback, relay.localPort))

                    val response = DatagramPacket(ByteArray(65_507), 65_507)
                    client.receive(response)
                    val decoded = Socks5Codec.parseUdpDatagram(
                        response.data.copyOfRange(response.offset, response.offset + response.length)
                    ) ?: throw AssertionError("Relay returned an invalid SOCKS5 UDP frame")
                    assertEquals(destination.localPort, decoded.destination.port)
                    assertArrayEquals(payload, decoded.payload)
                }

                roundTrip(firstServer.first, byteArrayOf(1, 2, 3))
                roundTrip(firstServer.first, byteArrayOf(4, 5, 6))
                roundTrip(secondServer.first, byteArrayOf(7, 8, 9))

                firstServer.second.join(2_000)
                secondServer.second.join(2_000)
                assertEquals(3, observedSourcePorts.size)
                assertTrue(
                    "All destinations in one association must share the same upstream source port",
                    observedSourcePorts.toSet().size == 1
                )
            } finally {
                relay.close()
                firstServer.first.close()
                secondServer.first.close()
            }
        }
    }

    @Test
    fun refusesToStartWhenUpstreamSocketCannotBeProtected() {
        val relay = LocalSocks5UdpRelay(
            protectUpstreamDatagramSocket = { false },
            bindAddress = loopback
        )
        try {
            try {
                relay.start(loopback)
                throw AssertionError("Relay must refuse an unprotected upstream socket")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("protéger"))
            }
            assertTrue(!relay.isRunning)
            assertEquals(-1, relay.localPort)
        } finally {
            relay.close()
        }
    }
}
