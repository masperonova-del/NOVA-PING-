package com.novaping.app

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback SOCKS5 UDP relay for one authenticated TCP control connection.
 *
 * Only the first UDP source endpoint from the TCP peer's IP is pinned.
 * Domain destinations are rejected deliberately: system DNS resolution here
 * could bypass the protected socket path and leak outside the VPN.
 */
internal class LocalSocks5UdpRelay(
    private val protectUpstreamDatagramSocket: (DatagramSocket) -> Boolean,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val responseTimeoutMillis: Int = 3_000
) : Closeable {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(8) { task ->
        Thread(task, "NovaSocks5UdpWorker").apply { isDaemon = true }
    }
    private val permits = Semaphore(16)
    private val endpointLock = Any()
    @Volatile private var relaySocket: DatagramSocket? = null
    @Volatile private var allowedClientAddress: InetAddress? = null
    @Volatile private var clientEndpoint: InetSocketAddress? = null
    private val receiver = Thread(::receiveLoop, "NovaSocks5UdpReceiver").apply { isDaemon = true }

    val localAddress: InetAddress get() = relaySocket?.localAddress ?: bindAddress
    val localPort: Int get() = relaySocket?.localPort ?: -1
    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(tcpPeerAddress: InetAddress): Int {
        check(running.compareAndSet(false, true)) { "UDP relay already started" }
        allowedClientAddress = tcpPeerAddress
        try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(bindAddress, 0))
            relaySocket = socket
            receiver.start()
            return socket.localPort
        } catch (error: Exception) {
            running.set(false)
            relaySocket?.close()
            relaySocket = null
            throw error
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65_507)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                relaySocket?.receive(packet) ?: break
                if (packet.address != allowedClientAddress) continue

                val endpoint = synchronized(endpointLock) {
                    val current = clientEndpoint
                    if (current == null) {
                        InetSocketAddress(packet.address, packet.port).also { clientEndpoint = it }
                    } else if (current.address == packet.address && current.port == packet.port) {
                        current
                    } else {
                        null
                    }
                } ?: continue

                val frame = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val datagram = Socks5Codec.parseUdpDatagram(frame) ?: continue
                val destination = when (val address = datagram.destination.address) {
                    is Socks5Address.Ipv4 -> InetAddress.getByAddress(address.bytes)
                    is Socks5Address.Ipv6 -> InetAddress.getByAddress(address.bytes)
                    is Socks5Address.Domain -> continue
                }
                if (datagram.destination.port !in 1..65535 || !permits.tryAcquire()) continue
                workers.execute {
                    try {
                        forwardOne(datagram.payload, destination, datagram.destination.port, endpoint)
                    } finally {
                        permits.release()
                    }
                }
            } catch (_: SocketException) {
                if (running.get()) close()
            } catch (_: Exception) {
                // Malformed datagrams and transient socket errors are dropped.
            }
        }
    }

    private fun forwardOne(
        payload: ByteArray,
        destination: InetAddress,
        destinationPort: Int,
        client: InetSocketAddress
    ) {
        if (!running.get() || payload.isEmpty()) return
        try {
            DatagramSocket(null).use { upstream ->
                upstream.bind(InetSocketAddress(0))
                if (!protectUpstreamDatagramSocket(upstream)) return
                // Connecting the UDP socket pins replies to the requested peer and filters spoofed sources.
                upstream.connect(InetSocketAddress(destination, destinationPort))
                upstream.soTimeout = responseTimeoutMillis
                upstream.send(DatagramPacket(payload, payload.size))
                val responseBytes = ByteArray(65_507)
                val response = DatagramPacket(responseBytes, responseBytes.size)
                upstream.receive(response)
                if (!running.get()) return

                val sourceAddress = when (val bytes = response.address.address) {
                    else -> if (bytes.size == 4) Socks5Address.Ipv4(bytes)
                    else Socks5Address.Ipv6(bytes)
                }
                val encoded = Socks5Codec.encodeUdpDatagram(
                    Socks5UdpDatagram(
                        Socks5Endpoint(sourceAddress, response.port),
                        response.data.copyOfRange(response.offset, response.offset + response.length)
                    )
                )
                relaySocket?.send(DatagramPacket(encoded, encoded.size, client))
            }
        } catch (_: Exception) {
            // UDP is best-effort; a failed request must not stop the relay.
        }
    }

    @Synchronized
    override fun close() {
        if (!running.getAndSet(false)) return
        relaySocket?.close()
        relaySocket = null
        workers.shutdownNow()
        try {
            workers.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
