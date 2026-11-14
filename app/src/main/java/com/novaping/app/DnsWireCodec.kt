package com.novaping.app

import java.net.IDN
import java.nio.charset.StandardCharsets

internal enum class DnsRecordType(val wireValue: Int) {
    A(1), AAAA(28)
}

internal data class DnsAnswer(val type: DnsRecordType, val addressBytes: ByteArray, val ttlSeconds: Long) {
    init {
        require(addressBytes.size == if (type == DnsRecordType.A) 4 else 16)
        require(ttlSeconds >= 0)
    }
    override fun equals(other: Any?) = other is DnsAnswer && type == other.type &&
        addressBytes.contentEquals(other.addressBytes) && ttlSeconds == other.ttlSeconds
    override fun hashCode() = 31 * (31 * type.hashCode() + addressBytes.contentHashCode()) + ttlSeconds.hashCode()
}

/** Strict wire codec for single-question DNS A/AAAA queries and direct address answers. */
internal object DnsWireCodec {
    private const val HEADER = 12
    private const val CLASS_IN = 1
    private const val MAX_RECORDS = 512

    fun encodeQuery(hostname: String, type: DnsRecordType, transactionId: Int): ByteArray {
        require(transactionId in 0..65535)
        val name = normalizeHostname(hostname)
        val question = ArrayList<Byte>()
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            require(bytes.size in 1..63)
            question.add(bytes.size.toByte())
            bytes.forEach(question::add)
        }
        question.add(0)
        question.add((type.wireValue ushr 8).toByte())
        question.add(type.wireValue.toByte())
        question.add(0)
        question.add(CLASS_IN.toByte())
        return ByteArray(HEADER + question.size).also {
            putU16(it, 0, transactionId)
            putU16(it, 2, 0x0100)
            putU16(it, 4, 1)
            question.toByteArray().copyInto(it, HEADER)
        }
    }

    fun parseResponse(packet: ByteArray, expectedHostname: String, expectedType: DnsRecordType, expectedTransactionId: Int): List<DnsAnswer>? {
        if (packet.size < HEADER || expectedTransactionId !in 0..65535 || u16(packet, 0) != expectedTransactionId) return null
        val flags = u16(packet, 2)
        if ((flags and 0x8000) == 0 || (flags and 0x7800) != 0 || (flags and 0x0200) != 0 || (flags and 0x000f) != 0) return null
        val qd = u16(packet, 4)
        val an = u16(packet, 6)
        val ns = u16(packet, 8)
        val ar = u16(packet, 10)
        if (qd != 1 || an > MAX_RECORDS || ns > MAX_RECORDS || ar > MAX_RECORDS) return null

        val expected = runCatching { normalizeHostname(expectedHostname) }.getOrNull() ?: return null
        val question = decodeName(packet, HEADER) ?: return null
        var offset = question.nextOffset
        if (question.name != expected || offset + 4 > packet.size ||
            u16(packet, offset) != expectedType.wireValue || u16(packet, offset + 2) != CLASS_IN) return null
        offset += 4

        val answers = ArrayList<DnsAnswer>()
        repeat(an) {
            val owner = decodeName(packet, offset) ?: return null
            offset = owner.nextOffset
            if (offset + 10 > packet.size) return null
            val type = u16(packet, offset)
            val klass = u16(packet, offset + 2)
            val ttl = u32(packet, offset + 4)
            val length = u16(packet, offset + 8)
            offset += 10
            if (offset + length > packet.size) return null
            if (owner.name == expected && klass == CLASS_IN && type == expectedType.wireValue) {
                val required = if (expectedType == DnsRecordType.A) 4 else 16
                if (length != required) return null
                answers.add(DnsAnswer(expectedType, packet.copyOfRange(offset, offset + length), ttl))
            }
            offset += length
        }
        repeat(ns + ar) {
            val owner = decodeName(packet, offset) ?: return null
            offset = owner.nextOffset
            if (offset + 10 > packet.size) return null
            val length = u16(packet, offset + 8)
            offset += 10
            if (offset + length > packet.size) return null
            offset += length
        }
        if (offset != packet.size) return null
        return answers
    }

    fun normalizeHostname(hostname: String): String {
        val input = hostname.trim().removeSuffix(".")
        require(input.isNotEmpty() && input.length <= 253)
        require(!input.contains('/') && !input.contains(':') && !input.contains('@'))
        val ascii = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).lowercase()
        require(ascii.length <= 253 && ascii.split('.').all { it.isNotEmpty() && it.length <= 63 })
        require(ascii.split('.').none { it.startsWith('-') || it.endsWith('-') })
        return ascii
    }

    private data class DecodedName(val name: String, val nextOffset: Int)

    private fun decodeName(packet: ByteArray, start: Int): DecodedName? {
        if (start !in packet.indices) return null
        val labels = ArrayList<String>()
        val visited = HashSet<Int>()
        var cursor = start
        var next = -1
        var jumps = 0
        var wireLength = 1
        while (true) {
            if (cursor !in packet.indices) return null
            val length = u8(packet, cursor)
            if ((length and 0xc0) == 0xc0) {
                if (cursor + 1 >= packet.size || jumps++ >= 16) return null
                val pointer = ((length and 0x3f) shl 8) or u8(packet, cursor + 1)
                if (pointer !in packet.indices || !visited.add(pointer)) return null
                if (next == -1) next = cursor + 2
                cursor = pointer
                continue
            }
            if ((length and 0xc0) != 0 || length > 63) return null
            cursor++
            if (length == 0) {
                if (next == -1) next = cursor
                break
            }
            if (cursor + length > packet.size) return null
            wireLength += length + 1
            if (wireLength > 255) return null
            val bytes = packet.copyOfRange(cursor, cursor + length)
            if (bytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) return null
            labels.add(String(bytes, StandardCharsets.US_ASCII).lowercase())
            cursor += length
        }
        return DecodedName(labels.joinToString("."), next)
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
    private fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()
    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }
}
