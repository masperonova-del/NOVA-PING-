package com.novaping.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DnsAnswerCacheTest {
    @Test
    fun cachesUntilTtlExpiresAndNormalizesHostname() {
        var now = 1_000L
        val cache = DnsAnswerCache(maxTtlSeconds = 60) { now }
        val answer = DnsAnswer(DnsRecordType.A, byteArrayOf(9, 9, 9, 9), 10)
        cache.put("Example.COM.", DnsRecordType.A, listOf(answer))
        assertNotNull(cache.get("example.com", DnsRecordType.A))
        now += 9_999
        assertNotNull(cache.get("example.com", DnsRecordType.A))
        now += 1
        assertNull(cache.get("example.com", DnsRecordType.A))
        assertEquals(0, cache.size())
    }

    @Test
    fun doesNotCacheZeroTtlEmptyOrWrongTypeAnswers() {
        val cache = DnsAnswerCache { 5_000L }
        cache.put("example.com", DnsRecordType.A, emptyList())
        cache.put("example.com", DnsRecordType.A, listOf(DnsAnswer(DnsRecordType.A, byteArrayOf(1, 2, 3, 4), 0)))
        cache.put("example.com", DnsRecordType.A, listOf(DnsAnswer(DnsRecordType.AAAA, ByteArray(16), 30)))
        assertEquals(0, cache.size())
    }

    @Test
    fun capsTtlAndReturnsDefensiveAddressCopies() {
        val cache = DnsAnswerCache(maxTtlSeconds = 2) { 0L }
        val original = byteArrayOf(1, 1, 1, 1)
        cache.put("example.com", DnsRecordType.A, listOf(DnsAnswer(DnsRecordType.A, original, 500_000)))
        original[0] = 99
        val returned = cache.get("example.com", DnsRecordType.A)!!
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), returned[0].addressBytes)
        returned[0].addressBytes[0] = 88
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), cache.get("example.com", DnsRecordType.A)!![0].addressBytes)
    }
}
