package com.novaping.app

import java.net.InetAddress

/**
 * Metadata extracted from one IP packet.
 *
 * The parser never retains the packet buffer or exposes transport payload bytes.
 * It is a pure decoding component: it does not create a VPN, forward packets,
 * open sockets, or observe device traffic by itself.
 */
enum class GamePacketDirection { OUTBOUND, INBOUND }

data class ParsedGamePacketMetadata(
    val ipVersion: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val transport: GameEndpointTransport,
    val direction: GamePacketDirection,
    val packetLengthBytes: Int,
    val fragmented: Boolean
)

object GamePacketMetadataParser {
    private const val IPV4_MIN_HEADER_BYTES = 20
    private const val IPV6_HEADER_BYTES = 40
    private const val TCP_MIN_HEADER_BYTES = 20
    private const val UDP_HEADER_BYTES = 8
    private const val MAX_IPV6_EXTENSION_HEADERS = 8

    /**
     * Parses a single packet read from a TUN interface.
     *
     * A packet is accepted only when exactly one endpoint matches a supplied local
     * TUN address and the other endpoint is a public unicast address. Non-initial
     * fragments and unsupported transport protocols are intentionally ignored.
     */
    fun parse(
        packet: ByteArray,
        localAddresses: Set<String>
    ): ParsedGamePacketMetadata? {
        if (packet.isEmpty() || localAddresses.isEmpty()) return null

        val localAddressKeys = localAddresses.mapNotNull(::numericAddressKey).toSet()
        if (localAddressKeys.isEmpty()) return null

        return when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> parseIpv4(packet, localAddressKeys)
            6 -> parseIpv6(packet, localAddressKeys)
            else -> null
        }
    }

    private fun parseIpv4(
        packet: ByteArray,
        localAddressKeys: Set<String>
    ): ParsedGamePacketMetadata? {
        if (packet.size < IPV4_MIN_HEADER_BYTES) return null

        val headerLength = (u8(packet, 0) and 0x0f) * 4
        val totalLength = u16(packet, 2)
        if (headerLength < IPV4_MIN_HEADER_BYTES ||
            headerLength > 60 ||
            totalLength < headerLength ||
            totalLength > packet.size
        ) return null

        val fragmentField = u16(packet, 6)
        // A non-initial fragment does not contain the TCP/UDP header.
        if ((fragmentField and 0x1fff) != 0) return null

        val source = packet.copyOfRange(12, 16)
        val destination = packet.copyOfRange(16, 20)
        val sourceLocal = addressKey(source) in localAddressKeys
        val destinationLocal = addressKey(destination) in localAddressKeys
        val direction = direction(sourceLocal, destinationLocal) ?: return null

        return parseTransport(
            packet = packet,
            ipVersion = 4,
            protocolNumber = u8(packet, 9),
            transportOffset = headerLength,
            packetEnd = totalLength,
            sourceAddress = source,
            destinationAddress = destination,
            direction = direction,
            localIsSource = sourceLocal,
            fragmented = (fragmentField and 0x2000) != 0
        )
    }

    private fun parseIpv6(
        packet: ByteArray,
        localAddressKeys: Set<String>
    ): ParsedGamePacketMetadata? {
        if (packet.size < IPV6_HEADER_BYTES) return null

        val payloadLength = u16(packet, 4)
        // IPv6 jumbograms use a Hop-by-Hop Jumbo Payload option. They are not
        // needed for game socket metadata and are deliberately unsupported here.
        if (payloadLength == 0) return null

        val packetEnd = IPV6_HEADER_BYTES + payloadLength
        if (packetEnd > packet.size) return null

        val source = packet.copyOfRange(8, 24)
        val destination = packet.copyOfRange(24, 40)
        val sourceLocal = addressKey(source) in localAddressKeys
        val destinationLocal = addressKey(destination) in localAddressKeys
        val direction = direction(sourceLocal, destinationLocal) ?: return null

        var nextHeader = u8(packet, 6)
        var offset = IPV6_HEADER_BYTES
        var fragmented = false
        var extensionCount = 0

        while (nextHeader == 0 || nextHeader == 43 || nextHeader == 60 ||
            nextHeader == 44 || nextHeader == 51
        ) {
            if (extensionCount >= MAX_IPV6_EXTENSION_HEADERS) return null
            extensionCount++

            if (nextHeader == 44) {
                if (offset + 8 > packetEnd) return null
                val fragmentField = u16(packet, offset + 2)
                // The offset is encoded in bits 3..15. Only the first fragment
                // contains transport ports, so later fragments are not candidates.
                if ((fragmentField and 0xfff8) != 0) return null
                fragmented = (fragmentField and 0x0001) != 0
                nextHeader = u8(packet, offset)
                offset += 8
            } else {
                if (offset + 2 > packetEnd) return null
                val followingHeader = u8(packet, offset)
                val extensionLength = if (nextHeader == 51) {
                    (u8(packet, offset + 1) + 2) * 4
                } else {
                    (u8(packet, offset + 1) + 1) * 8
                }
                val minimumLength = if (nextHeader == 51) 12 else 8
                if (extensionLength < minimumLength || offset + extensionLength > packetEnd) return null
                nextHeader = followingHeader
                offset += extensionLength
            }
        }

        return parseTransport(
            packet = packet,
            ipVersion = 6,
            protocolNumber = nextHeader,
            transportOffset = offset,
            packetEnd = packetEnd,
            sourceAddress = source,
            destinationAddress = destination,
            direction = direction,
            localIsSource = sourceLocal,
            fragmented = fragmented
        )
    }

    private fun parseTransport(
        packet: ByteArray,
        ipVersion: Int,
        protocolNumber: Int,
        transportOffset: Int,
        packetEnd: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        direction: GamePacketDirection,
        localIsSource: Boolean,
        fragmented: Boolean
    ): ParsedGamePacketMetadata? {
        val availableBytes = packetEnd - transportOffset
        if (availableBytes < UDP_HEADER_BYTES) return null

        val transport = when (protocolNumber) {
            6 -> {
                if (availableBytes < TCP_MIN_HEADER_BYTES) return null
                val tcpHeaderLength = ((u8(packet, transportOffset + 12) ushr 4) and 0x0f) * 4
                if (tcpHeaderLength < TCP_MIN_HEADER_BYTES || tcpHeaderLength > availableBytes) return null
                GameEndpointTransport.TCP
            }
            17 -> {
                val udpLength = u16(packet, transportOffset + 4)
                if (udpLength < UDP_HEADER_BYTES) return null
                if (!fragmented && udpLength > availableBytes) return null
                GameEndpointTransport.UDP
            }
            else -> return null
        }

        val sourcePort = u16(packet, transportOffset)
        val destinationPort = u16(packet, transportOffset + 2)
        if (sourcePort == 0 || destinationPort == 0) return null

        val remoteBytes = if (localIsSource) destinationAddress else sourceAddress
        val remoteAddress = runCatching {
            InetAddress.getByAddress(remoteBytes).hostAddress
        }.getOrNull() ?: return null

        if (!GameEndpointObservationRegistry.isPublicUnicastAddress(remoteAddress)) return null

        return ParsedGamePacketMetadata(
            ipVersion = ipVersion,
            remoteAddress = remoteAddress,
            remotePort = if (localIsSource) destinationPort else sourcePort,
            transport = transport,
            direction = direction,
            packetLengthBytes = packetEnd,
            fragmented = fragmented
        )
    }

    private fun direction(
        sourceIsLocal: Boolean,
        destinationIsLocal: Boolean
    ): GamePacketDirection? = when {
        sourceIsLocal && !destinationIsLocal -> GamePacketDirection.OUTBOUND
        destinationIsLocal && !sourceIsLocal -> GamePacketDirection.INBOUND
        else -> null
    }

    private fun numericAddressKey(rawAddress: String): String? {
        if (rawAddress.isBlank() || rawAddress.contains('%')) return null
        val bytes = if (rawAddress.contains(':')) {
            if (!rawAddress.matches(Regex("[0-9a-fA-F:.]+"))) return null
            runCatching { InetAddress.getByName(rawAddress).address }
                .getOrNull()
                ?.takeIf { it.size == 16 }
        } else {
            if (!rawAddress.matches(Regex("[0-9.]+"))) return null
            val octets = rawAddress.split(".")
            if (octets.size != 4) return null
            val parsed = octets.map { octet ->
                if (octet.isEmpty() || (octet.length > 1 && octet.startsWith("0"))) return null
                val value = octet.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                value.toByte()
            }
            parsed.toByteArray()
        } ?: return null
        return addressKey(bytes)
    }

    private fun addressKey(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun u8(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)
}
