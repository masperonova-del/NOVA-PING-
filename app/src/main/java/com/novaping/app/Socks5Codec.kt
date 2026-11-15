package com.novaping.app

import java.net.IDN
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/** SOCKS5 wire-format primitives (RFC 1928), independent of sockets and VPN state. */
enum class Socks5Command(val wireValue: Int) {
    CONNECT(0x01),
    UDP_ASSOCIATE(0x03)
}

enum class Socks5Reply(val wireValue: Int) {
    SUCCEEDED(0x00),
    GENERAL_FAILURE(0x01),
    NOT_ALLOWED(0x02),
    NETWORK_UNREACHABLE(0x03),
    HOST_UNREACHABLE(0x04),
    CONNECTION_REFUSED(0x05),
    TTL_EXPIRED(0x06),
    COMMAND_NOT_SUPPORTED(0x07),
    ADDRESS_TYPE_NOT_SUPPORTED(0x08);

    companion object {
        fun fromWire(value: Int): Socks5Reply? = entries.firstOrNull { it.wireValue == value }
    }
}

sealed class Socks5Address {
    data class Ipv4(val bytes: ByteArray) : Socks5Address() {
        init { require(bytes.size == 4) }
        override fun equals(other: Any?) = other is Ipv4 && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    data class Ipv6(val bytes: ByteArray) : Socks5Address() {
        init { require(bytes.size == 16) }
        override fun equals(other: Any?) = other is Ipv6 && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    data class Domain(val asciiName: String) : Socks5Address() {
        init {
            require(asciiName.isNotBlank())
            require(asciiName.toByteArray(StandardCharsets.US_ASCII).size in 1..255)
        }
    }
}

data class Socks5Endpoint(val address: Socks5Address, val port: Int) {
    init { require(port in 0..65535) }
}

data class Socks5UdpDatagram(val destination: Socks5Endpoint, val payload: ByteArray)

object Socks5Codec {
    private const val VERSION = 0x05
    private const val NO_AUTH = 0x00
    private const val RESERVED = 0x00
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    /** Client greeting offering only unauthenticated local/controlled SOCKS5 mode. */
    fun noAuthGreeting(): ByteArray = byteArrayOf(VERSION.toByte(), 0x01, NO_AUTH.toByte())

    /** Returns true only for the exact SOCKS5 no-authentication selection. */
    fun acceptsNoAuthSelection(response: ByteArray): Boolean =
        response.size == 2 &&
            u8(response, 0) == VERSION &&
            u8(response, 1) == NO_AUTH

    fun commandRequest(command: Socks5Command, endpoint: Socks5Endpoint): ByteArray {
        val address = encodeAddress(endpoint.address)
        return byteArrayOf(VERSION.toByte(), command.wireValue.toByte(), RESERVED.toByte()) +
            address +
            byteArrayOf((endpoint.port ushr 8).toByte(), endpoint.port.toByte())
    }

    /**
     * Parses one complete SOCKS5 command reply. The caller must first read the
     * four-byte prefix, then exactly the address bytes indicated by ATYP.
     */
    fun parseCommandReply(response: ByteArray): Pair<Socks5Reply, Socks5Endpoint>? {
        if (response.size < 4 || u8(response, 0) != VERSION || u8(response, 2) != RESERVED) return null
        val reply = Socks5Reply.fromWire(u8(response, 1)) ?: return null
        val parsed = decodeAddress(response, 3) ?: return null
        val (address, afterAddress) = parsed
        if (response.size != afterAddress + 2) return null
        val port = (u8(response, afterAddress) shl 8) or u8(response, afterAddress + 1)
        return reply to Socks5Endpoint(address, port)
    }

    /** Encapsulates a UDP payload using RSV=0 and FRAG=0 (no fragmentation). */
    fun encodeUdpDatagram(datagram: Socks5UdpDatagram): ByteArray {
        val address = encodeAddress(datagram.destination.address)
        return byteArrayOf(0, 0, 0) + address +
            byteArrayOf(
                (datagram.destination.port ushr 8).toByte(),
                datagram.destination.port.toByte()
            ) + datagram.payload
    }

    /** Decodes only complete, unfragmented SOCKS5 UDP datagrams. */
    fun parseUdpDatagram(packet: ByteArray): Socks5UdpDatagram? {
        if (packet.size < 4 || u8(packet, 0) != 0 || u8(packet, 1) != 0 || u8(packet, 2) != 0) {
            return null
        }
        val parsed = decodeAddress(packet, 3) ?: return null
        val (address, afterAddress) = parsed
        if (packet.size < afterAddress + 2) return null
        val port = (u8(packet, afterAddress) shl 8) or u8(packet, afterAddress + 1)
        val payloadStart = afterAddress + 2
        return Socks5UdpDatagram(
            destination = Socks5Endpoint(address, port),
            payload = packet.copyOfRange(payloadStart, packet.size)
        )
    }

    private fun encodeAddress(address: Socks5Address): ByteArray = when (address) {
        is Socks5Address.Ipv4 -> byteArrayOf(ATYP_IPV4.toByte()) + address.bytes
        is Socks5Address.Ipv6 -> byteArrayOf(ATYP_IPV6.toByte()) + address.bytes
        is Socks5Address.Domain -> {
            val bytes = address.asciiName.toByteArray(StandardCharsets.US_ASCII)
            byteArrayOf(ATYP_DOMAIN.toByte(), bytes.size.toByte()) + bytes
        }
    }

    private fun decodeAddress(
        bytes: ByteArray,
        offset: Int
    ): Pair<Socks5Address, Int>? {
        if (offset >= bytes.size) return null
        return when (u8(bytes, offset)) {
            ATYP_IPV4 -> {
                val start = offset + 1
                if (bytes.size < start + 4) null
                else Socks5Address.Ipv4(bytes.copyOfRange(start, start + 4)) to (start + 4)
            }
            ATYP_IPV6 -> {
                val start = offset + 1
                if (bytes.size < start + 16) null
                else Socks5Address.Ipv6(bytes.copyOfRange(start, start + 16)) to (start + 16)
            }
            ATYP_DOMAIN -> {
                if (bytes.size < offset + 2) return null
                val length = u8(bytes, offset + 1)
                val start = offset + 2
                if (length == 0 || bytes.size < start + length) null
                else {
                    val name = String(bytes, start, length, StandardCharsets.US_ASCII)
                    if (name.any { it.code !in 0x21..0x7e }) null
                    else Socks5Address.Domain(name) to (start + length)
                }
            }
            else -> null
        }
    }

    /** Numeric IP literals only; hostnames are converted to IDNA ASCII without DNS. */
    fun addressFromHost(host: String): Socks5Address? {
        if (host.isBlank() || host.contains('%')) return null
        if (host.contains(':')) {
            if (!host.matches(Regex("[0-9a-fA-F:.]+"))) return null
            val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return null
            return bytes.takeIf { it.size == 16 }?.let(Socks5Address::Ipv6)
        }
        if (host.matches(Regex("[0-9.]+"))) {
            val octets = host.split(".")
            if (octets.size != 4) return null
            val values = octets.map {
                if (it.isEmpty() || (it.length > 1 && it.startsWith("0"))) return null
                val value = it.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                value.toByte()
            }
            return Socks5Address.Ipv4(values.toByteArray())
        }
        val ascii = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase() }
            .getOrNull() ?: return null
        if (ascii.length !in 1..255 || ascii.split(".").any { it.isEmpty() || it.length > 63 }) return null
        return Socks5Address.Domain(ascii)
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff
}
