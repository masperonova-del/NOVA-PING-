package com.novaping.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * DNS A/AAAA resolver that uses only an explicitly supplied numeric resolver address.
 *
 * Every outbound socket is passed through its protection callback before connect/send.
 * It never calls the platform hostname resolver and never falls back to system DNS.
 */
internal class ProtectedDnsResolver(
    private val resolverAddress: InetAddress,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean,
    private val protectTcpSocket: (Socket) -> Boolean,
    private val resolverPort: Int = 53,
    private val timeoutMillis: Int = 2_500,
    private val cache: DnsAnswerCache = DnsAnswerCache(),
    private val transactionIds: () -> Int = { secureRandom.nextInt(65_536) }
) {
    init {
        require(resolverPort in 1..65_535)
        require(timeoutMillis in 1..30_000)
    }

    /**
     * Returns validated address answers, an empty list for a valid no-answer response,
     * or null for transport, protection, or validation failure.
     */
    fun resolve(hostname: String, type: DnsRecordType): List<DnsAnswer>? {
        val normalized = runCatching { DnsWireCodec.normalizeHostname(hostname) }.getOrNull()
            ?: return null
        cache.get(normalized, type)?.let { return it }

        val id = runCatching { transactionIds().also { require(it in 0..65_535) } }
            .getOrNull() ?: return null
        val query = runCatching { DnsWireCodec.encodeQuery(normalized, type, id) }.getOrNull()
            ?: return null

        val udpResponse = queryUdp(query) ?: return null
        val response = if (DnsWireCodec.isTruncatedResponse(udpResponse, id)) {
            queryTcp(query) ?: return null
        } else {
            udpResponse
        }
        val answers = DnsWireCodec.parseResponse(response, normalized, type, id) ?: return null
        cache.put(normalized, type, answers)
        return answers
    }

    /** Resolves a hostname to the first validated A address, then AAAA if needed. */
    fun resolveAddress(hostname: String): InetAddress? {
        val ipv4 = resolve(hostname, DnsRecordType.A)
            ?.firstOrNull { it.type == DnsRecordType.A }
            ?.addressBytes
        if (ipv4 != null) return runCatching { InetAddress.getByAddress(ipv4) }.getOrNull()
        val ipv6 = resolve(hostname, DnsRecordType.AAAA)
            ?.firstOrNull { it.type == DnsRecordType.AAAA }
            ?.addressBytes
        return ipv6?.let { runCatching { InetAddress.getByAddress(it) }.getOrNull() }
    }

    private fun queryUdp(query: ByteArray): ByteArray? = runCatching {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(0))
            if (!protectDatagramSocket(socket)) return null
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(resolverAddress, resolverPort))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(MAX_DNS_MESSAGE_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        }
    }.getOrNull()

    private fun queryTcp(query: ByteArray): ByteArray? = runCatching {
        Socket().use { socket ->
            if (!protectTcpSocket(socket)) return null
            socket.soTimeout = timeoutMillis
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(resolverAddress, resolverPort), timeoutMillis)
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val responseLength = input.readUnsignedShort()
            if (responseLength !in DNS_HEADER_SIZE..MAX_DNS_MESSAGE_SIZE) return null
            ByteArray(responseLength).also(input::readFully)
        }
    }.getOrNull()

    private companion object {
        const val DNS_HEADER_SIZE = 12
        const val MAX_DNS_MESSAGE_SIZE = 4_096
        val secureRandom = SecureRandom()
    }
}
