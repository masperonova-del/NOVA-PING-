package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DnsWireCodecTest {
    private fun cnameResponse(
        target: String,
        address: ByteArray,
        cnameTtl: Int = 60,
        addressTtl: Int = 120
    ): ByteArray {
        val query = DnsWireCodec.encodeQuery("example.com", DnsRecordType.A, 0x4a21)
        val targetWire = target.split('.').flatMap { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            listOf(bytes.size.toByte()) + bytes.toList()
        }.toMutableList().apply { add(0) }.toByteArray()
        val cnameRecordSize = 2 + 10 + targetWire.size
        val addressRecordSize = 2 + 10 + address.size
        val result = query.copyOf(query.size + cnameRecordSize + addressRecordSize)
        result[2] = 0x81.toByte()
        result[3] = 0x80.toByte()
        result[6] = 0
        result[7] = 2
        result[8] = 0
        result[9] = 0
        result[10] = 0
        result[11] = 0
        var offset = query.size

        // CNAME: example.com -> target
        result[offset++] = 0xc0.toByte()
        result[offset++] = 0x0c
        result[offset++] = 0
        result[offset++] = 5
        result[offset++] = 0
        result[offset++] = 1
        result[offset++] = (cnameTtl ushr 24).toByte()
        result[offset++] = (cnameTtl ushr 16).toByte()
        result[offset++] = (cnameTtl ushr 8).toByte()
        result[offset++] = cnameTtl.toByte()
        result[offset++] = (targetWire.size ushr 8).toByte()
        result[offset++] = targetWire.size.toByte()
        val targetOffset = offset
        targetWire.copyInto(result, offset)
        offset += targetWire.size

        // A record owner uses a compression pointer to the CNAME target.
        result[offset++] = (0xc0 or (targetOffset ushr 8)).toByte()
        result[offset++] = targetOffset.toByte()
        result[offset++] = 0
        result[offset++] = 1
        result[offset++] = 0
        result[offset++] = 1
        result[offset++] = (addressTtl ushr 24).toByte()
        result[offset++] = (addressTtl ushr 16).toByte()
        result[offset++] = (addressTtl ushr 8).toByte()
        result[offset++] = addressTtl.toByte()
        result[offset++] = (address.size ushr 8).toByte()
        result[offset++] = address.size.toByte()
        address.copyInto(result, offset)
        return result
    }

    private fun response(type: DnsRecordType, data: ByteArray, ttl: Int = 120): ByteArray {
        val query = DnsWireCodec.encodeQuery("example.com", type, 0x4a21)
        val result = query.copyOf(query.size + 12 + data.size)
        result[2] = 0x81.toByte()
        result[3] = 0x80.toByte()
        result[6] = 0
        result[7] = 1
        result[8] = 0
        result[9] = 0
        result[10] = 0
        result[11] = 0
        var offset = query.size
        result[offset++] = 0xc0.toByte()
        result[offset++] = 0x0c
        result[offset++] = (type.wireValue ushr 8).toByte()
        result[offset++] = type.wireValue.toByte()
        result[offset++] = 0
        result[offset++] = 1
        result[offset++] = (ttl ushr 24).toByte()
        result[offset++] = (ttl ushr 16).toByte()
        result[offset++] = (ttl ushr 8).toByte()
        result[offset++] = ttl.toByte()
        result[offset++] = (data.size ushr 8).toByte()
        result[offset++] = data.size.toByte()
        data.copyInto(result, offset)
        return result
    }

    @Test
    fun encodesAAndAaaaQuestionsWithExpectedId() {
        val a = DnsWireCodec.encodeQuery("Example.COM.", DnsRecordType.A, 0x1234)
        val aaaa = DnsWireCodec.encodeQuery("example.com", DnsRecordType.AAAA, 0x5678)
        assertEquals(0x12, a[0].toInt() and 0xff)
        assertEquals(0x34, a[1].toInt() and 0xff)
        assertEquals(1, a[a.size - 3].toInt() and 0xff)
        assertEquals(28, aaaa[aaaa.size - 3].toInt() and 0xff)
        assertEquals("example.com", DnsWireCodec.normalizeHostname("Example.COM."))
    }

    @Test
    fun parsesValidatedIpv4AnswerAndTtl() {
        val packet = response(DnsRecordType.A, byteArrayOf(1, 1, 1, 1), 300)
        val parsed = DnsWireCodec.parseResponse(packet, "example.com", DnsRecordType.A, 0x4a21)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), parsed[0].addressBytes)
        assertEquals(300L, parsed[0].ttlSeconds)
    }

    @Test
    fun parsesValidatedIpv6Answer() {
        val address = ByteArray(16).also { it[15] = 1 }
        val parsed = DnsWireCodec.parseResponse(
            response(DnsRecordType.AAAA, address),
            "example.com",
            DnsRecordType.AAAA,
            0x4a21
        )
        assertNotNull(parsed)
        assertArrayEquals(address, parsed!![0].addressBytes)
    }

    @Test
    fun followsCnameChainAndUsesShortestTtlForAddressAnswer() {
        val packet = cnameResponse(
            target = "edge.example.net",
            address = byteArrayOf(8, 8, 8, 8),
            cnameTtl = 45,
            addressTtl = 120
        )
        val parsed = DnsWireCodec.parseResponse(
            packet, "example.com", DnsRecordType.A, 0x4a21
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), parsed[0].addressBytes)
        assertEquals(45L, parsed[0].ttlSeconds)
    }

    @Test
    fun rejectsCnameLoops() {
        val packet = cnameResponse(
            target = "example.com",
            address = byteArrayOf(8, 8, 8, 8)
        )
        assertNull(DnsWireCodec.parseResponse(
            packet, "example.com", DnsRecordType.A, 0x4a21
        ))
    }

    @Test
    fun rejectsWrongTransactionIdAndTruncatedAnswer() {
        val packet = response(DnsRecordType.A, byteArrayOf(8, 8, 8, 8))
        assertNull(DnsWireCodec.parseResponse(packet, "example.com", DnsRecordType.A, 0x4a22))
        assertNull(DnsWireCodec.parseResponse(packet.copyOf(packet.size - 1), "example.com", DnsRecordType.A, 0x4a21))
    }

    @Test
    fun rejectsQuestionMismatchAndInvalidHostname() {
        val packet = response(DnsRecordType.A, byteArrayOf(8, 8, 4, 4))
        assertNull(DnsWireCodec.parseResponse(packet, "other.example", DnsRecordType.A, 0x4a21))
        try {
            DnsWireCodec.encodeQuery("bad name.example", DnsRecordType.A, 1)
            throw AssertionError("Invalid hostname must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
