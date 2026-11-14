package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
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
}
