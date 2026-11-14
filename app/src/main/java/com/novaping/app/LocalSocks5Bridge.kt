package com.novaping.app

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

/**
 * Loopback-only SOCKS5 bridge with authenticated TCP CONNECT and UDP ASSOCIATE.
 *
 * The caller supplies protectors for upstream TCP and UDP sockets (for example
 * VpnService.protect). UDP domain destinations are rejected until controlled DNS
 * resolution is available. No VPN is started by this class.
 */
internal data class Socks5BridgeCredentials(val username: String, val password: String)

internal class LocalSocks5Bridge(
    private val protectUpstreamSocket: (Socket) -> Boolean,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val protectUpstreamDatagramSocket: (DatagramSocket) -> Boolean = { false },
    private val connectTimeoutMillis: Int = 10_000
) : Closeable {
    private val running = AtomicBoolean(false)
    val credentials: Socks5BridgeCredentials = generateCredentials()
    private val workers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "NovaSocks5Worker").apply { isDaemon = true }
    }
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var serverSocket: ServerSocket? = null

    val isRunning: Boolean get() = running.get()
    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start(): Int {
        check(running.compareAndSet(false, true)) { "SOCKS5 bridge already started" }
        try {
            val server = ServerSocket(0, 32, bindAddress)
            serverSocket = server
            workers.execute {
                while (running.get()) {
                    try {
                        val client = server.accept()
                        activeSockets.add(client)
                        workers.execute { handleClient(client) }
                    } catch (_: SocketException) {
                        if (running.get()) close()
                    } catch (_: Exception) {
                        if (running.get()) close()
                    }
                }
            }
            return server.localPort
        } catch (error: Exception) {
            running.set(false)
            serverSocket?.close()
            serverSocket = null
            throw error
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            val version = input.readUnsignedByte()
            val methodCount = input.readUnsignedByte()
            if (version != 5 || methodCount == 0) return
            val methods = ByteArray(methodCount)
            input.readFully(methods)
            if (methods.none { (it.toInt() and 0xff) == 2 }) {
                output.write(byteArrayOf(5, 0xff.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(5, 2))
            output.flush()

            val authVersion = input.readUnsignedByte()
            val usernameLength = input.readUnsignedByte()
            val usernameBytes = ByteArray(usernameLength)
            input.readFully(usernameBytes)
            val passwordLength = input.readUnsignedByte()
            val passwordBytes = ByteArray(passwordLength)
            input.readFully(passwordBytes)
            val suppliedUsername = String(usernameBytes, Charsets.UTF_8)
            val suppliedPassword = String(passwordBytes, Charsets.UTF_8)
            val validCredentials = authVersion == 1 &&
                MessageDigest.isEqual(
                    suppliedUsername.toByteArray(Charsets.UTF_8),
                    credentials.username.toByteArray(Charsets.UTF_8)
                ) &&
                MessageDigest.isEqual(
                    suppliedPassword.toByteArray(Charsets.UTF_8),
                    credentials.password.toByteArray(Charsets.UTF_8)
                )
            output.write(byteArrayOf(1, if (validCredentials) 0 else 1))
            output.flush()
            if (!validCredentials) return

            val requestVersion = input.readUnsignedByte()
            val command = input.readUnsignedByte()
            val reserved = input.readUnsignedByte()
            val addressType = input.readUnsignedByte()
            if (requestVersion != 5 || reserved != 0) {
                writeReply(output, 0x01)
                return
            }
            if (command != 0x01 && command != 0x03) {
                writeReply(output, 0x07)
                return
            }

            val host = when (addressType) {
                0x01 -> ByteArray(4).also(input::readFully).let(InetAddress::getByAddress)
                0x04 -> ByteArray(16).also(input::readFully).let(InetAddress::getByAddress)
                0x03 -> {
                    val length = input.readUnsignedByte()
                    if (length == 0) {
                        writeReply(output, 0x08)
                        return
                    }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    val name = String(bytes, Charsets.US_ASCII)
                    if (name.any { it.code !in 0x21..0x7e }) {
                        writeReply(output, 0x08)
                        return
                    }
                    if (command == 0x03) InetAddress.getLoopbackAddress()
                    else InetAddress.getAllByName(name).first()
                }
                else -> {
                    writeReply(output, 0x08)
                    return
                }
            }
            val destinationPort = input.readUnsignedShort()
            if (command == 0x03) {
                // RFC 1928 permits an unspecified endpoint or the client's own TCP peer address.
                if (!host.isAnyLocalAddress && host != client.inetAddress) {
                    writeReply(output, 0x02)
                    return
                }
                val relay = LocalSocks5UdpRelay(
                    protectUpstreamDatagramSocket = protectUpstreamDatagramSocket,
                    bindAddress = bindAddress
                )
                try {
                    relay.start(client.inetAddress, destinationPort.takeIf { it != 0 })
                    writeReply(output, 0x00, relay.localAddress, relay.localPort)
                    client.soTimeout = 0
                    // RFC 1928: the UDP association ends when its TCP control connection closes.
                    while (input.read() != -1) {
                        // No application data is expected on the control channel.
                    }
                } finally {
                    relay.close()
                }
                return
            }
            if (destinationPort == 0) {
                writeReply(output, 0x01)
                return
            }

            upstream = Socket()
            activeSockets.add(upstream)
            if (!protectUpstreamSocket(upstream)) {
                writeReply(output, 0x01)
                return
            }
            try {
                upstream.connect(java.net.InetSocketAddress(host, destinationPort), connectTimeoutMillis)
                upstream.tcpNoDelay = true
            } catch (_: java.net.ConnectException) {
                writeReply(output, 0x05)
                return
            } catch (_: java.net.UnknownHostException) {
                writeReply(output, 0x04)
                return
            } catch (_: java.net.SocketTimeoutException) {
                writeReply(output, 0x04)
                return
            } catch (_: Exception) {
                writeReply(output, 0x03)
                return
            }

            writeReply(output, 0x00, upstream.localAddress, upstream.localPort)
            client.soTimeout = 0
            relayBidirectionally(client, upstream)
        } catch (_: EOFException) {
            // Client closed during negotiation.
        } catch (_: Exception) {
            // A disconnected client or upstream must not crash the accept loop.
        } finally {
            closeSocket(upstream)
            closeSocket(client)
        }
    }

    private fun relayBidirectionally(client: Socket, upstream: Socket) {
        val finished = CountDownLatch(2)
        workers.execute {
            copyStream(client, upstream)
            finished.countDown()
        }
        workers.execute {
            copyStream(upstream, client)
            finished.countDown()
        }
        try {
            finished.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun copyStream(source: Socket, destination: Socket) {
        try {
            val buffer = ByteArray(16 * 1024)
            val input = source.getInputStream()
            val output = destination.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {
            // Socket shutdown is the normal cancellation path.
        } finally {
            try { destination.shutdownOutput() } catch (_: Exception) { }
        }
    }

    private fun writeReply(
        output: DataOutputStream,
        reply: Int,
        address: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
        port: Int = 0
    ) {
        val bytes = address.address
        val atyp = if (bytes.size == 16) 0x04 else 0x01
        output.write(byteArrayOf(5, reply.toByte(), 0, atyp.toByte()))
        output.write(bytes)
        output.writeShort(port)
        output.flush()
    }


    private fun generateCredentials(): Socks5BridgeCredentials {
        val random = SecureRandom()
        fun token(): String = ByteArray(24).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
        return Socks5BridgeCredentials(token(), token())
    }

    private fun closeSocket(socket: Socket?) {
        if (socket == null) return
        activeSockets.remove(socket)
        try { socket.close() } catch (_: Exception) { }
    }

    @Synchronized
    override fun close() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        activeSockets.toList().forEach(::closeSocket)
        workers.shutdownNow()
        try { workers.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
