package com.novaping.app

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback SOCKS5 UDP relay for one authenticated TCP control connection.
 *
 * Only the first UDP source endpoint from the TCP peer's IP is pinned.
 * Domain destinations are resolved only by the injected protected DNS resolver.
 */
internal class LocalSocks5UdpRelay(
    private val protectUpstreamDatagramSocket: (DatagramSocket) -> Boolean,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val protectedDnsResolver: ProtectedDnsResolver? = null
) : Closeable {
    private val running = AtomicBoolean(false)
    private val endpointLock = Any()
    @Volatile private var relaySocket: DatagramSocket? = null
    @Volatile private var upstreamSocket: DatagramSocket? = null
    private val allowedDestinations = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    @Volatile private var allowedClientAddress: InetAddress? = null
    @Volatile private var expectedClientPort: Int? = null
    @Volatile private var clientEndpoint: InetSocketAddress? = null
    private val receiver = Thread(::receiveLoop, "NovaSocks5UdpReceiver").apply { isDaemon = true }
    private val upstreamReceiver = Thread(::receiveUpstreamLoop, "NovaSocks5UdpUpstreamReceiver").apply { isDaemon = true }

    val localAddress: InetAddress get() = relaySocket?.localAddress ?: bindAddress
    val localPort: Int get() = relaySocket?.localPort ?: -1
    val isRunning: Boolean get() = running.get()

    private companion object {
        const val MAX_DESTINATIONS = 512
    }

    @Synchronized
    fun start(tcpPeerAddress: InetAddress, expectedClientPort: Int? = null): Int {
        require(expectedClientPort == null || expectedClientPort in 1..65535)
        check(running.compareAndSet(false, true)) { "UDP relay already started" }
        allowedClientAddress = tcpPeerAddress
        this.expectedClientPort = expectedClientPort
        try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(bindAddress, 0))
            relaySocket = socket
            val upstream = DatagramSocket(null)
            upstream.reuseAddress = false
            upstream.bind(InetSocketAddress(0))
            upstreamSocket = upstream
            check(protectUpstreamDatagramSocket(upstream)) {
                "Impossible de protéger le socket UDP amont du tunnel"
            }
            receiver.start()
            upstreamReceiver.start()
            VpnDiagnostics.tracker.recordUdpAssociationStarted()
            return socket.localPort
        } catch (error: Exception) {
            VpnDiagnostics.tracker.recordUdpAssociationFailure()
            running.set(false)
            relaySocket?.close()
            relaySocket = null
            upstreamSocket?.close()
            upstreamSocket = null
            throw error
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65_507)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                relaySocket?.receive(packet) ?: break
                if (packet.address != allowedClientAddress) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }

                val endpointCandidate = synchronized(endpointLock) {
                    val current = clientEndpoint
                    if (current == null) {
                        if (expectedClientPort != null && packet.port != expectedClientPort) null
                        else InetSocketAddress(packet.address, packet.port).also { clientEndpoint = it }
                    } else if (current.address == packet.address && current.port == packet.port) {
                        current
                    } else {
                        null
                    }
                }
                if (endpointCandidate == null) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }

                val frame = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val parsedDatagram = Socks5Codec.parseUdpDatagram(frame)
                if (parsedDatagram == null) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }
                val datagram = parsedDatagram
                val destination = when (val address = datagram.destination.address) {
                    is Socks5Address.Ipv4 -> InetAddress.getByAddress(address.bytes)
                    is Socks5Address.Ipv6 -> InetAddress.getByAddress(address.bytes)
                    is Socks5Address.Domain -> protectedDnsResolver?.resolveAddress(address.asciiName)
                }
                if (destination == null) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }
                if (datagram.destination.port !in 1..65535 || datagram.payload.isEmpty()) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }
                val remote = InetSocketAddress(destination, datagram.destination.port)
                if (!allowedDestinations.contains(remote) && allowedDestinations.size >= MAX_DESTINATIONS) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }
                allowedDestinations.add(remote)
                try {
                    val socket = upstreamSocket
                    if (socket == null) {
                        VpnDiagnostics.tracker.recordUdpSendFailure()
                    } else {
                        socket.send(DatagramPacket(datagram.payload, datagram.payload.size, remote))
                        VpnDiagnostics.tracker.recordUdpOutbound()
                    }
                } catch (_: Exception) {
                    VpnDiagnostics.tracker.recordUdpSendFailure()
                }
            } catch (_: SocketException) {
                if (running.get()) close()
            } catch (_: Exception) {
                // Malformed datagrams and transient socket errors are dropped.
            }
        }
    }

    private fun receiveUpstreamLoop() {
        val buffer = ByteArray(65_507)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                upstreamSocket?.receive(packet) ?: break
                val remote = InetSocketAddress(packet.address, packet.port)
                if (!allowedDestinations.contains(remote)) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }
                val endpoint = clientEndpoint
                if (endpoint == null) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                    continue
                }
                val sourceAddress = if (packet.address.address.size == 4) {
                    Socks5Address.Ipv4(packet.address.address)
                } else {
                    Socks5Address.Ipv6(packet.address.address)
                }
                val encoded = Socks5Codec.encodeUdpDatagram(
                    Socks5UdpDatagram(
                        Socks5Endpoint(sourceAddress, packet.port),
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    )
                )
                val clientSocket = relaySocket
                if (clientSocket == null) {
                    VpnDiagnostics.tracker.recordUdpDrop()
                } else {
                    clientSocket.send(DatagramPacket(encoded, encoded.size, endpoint))
                    VpnDiagnostics.tracker.recordUdpInbound()
                }
            } catch (_: SocketException) {
                if (running.get()) close()
            } catch (_: Exception) {
                VpnDiagnostics.tracker.recordUdpDrop()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!running.getAndSet(false)) return
        relaySocket?.close()
        relaySocket = null
        upstreamSocket?.close()
        upstreamSocket = null
        allowedDestinations.clear()
    }
}
