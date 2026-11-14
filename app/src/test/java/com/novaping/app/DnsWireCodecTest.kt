package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DnsWireCodecTest {
    private fun response(type: DnsRecordType, data: ByteArray, ttl: Int = 120): ByteArray {
        val query = DnsWireCodec.encodeQuery("example.com", type, 0x4a21)
        val result = query.copyOf(query.size + 16 + data.size)
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
        assertEquals(1, a[a.size - 4].toInt() and 0xff)
        assertEquals(28, aaaa[aaaa.size - 4].toInt() and 0xff)
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
