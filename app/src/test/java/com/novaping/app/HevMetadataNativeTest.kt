package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HevMetadataNativeTest {
    @Test
    fun decodesIpv4MetadataRecord() {
        val record = HevMetadataNative.decode(
            longArrayOf(
                4, 4, 17, 1,
                0x0102030400000000L, 0L,
                27015, 128, 0
            )
        )

        assertNotNull(record)
        assertEquals("1.2.3.4", record?.remoteAddress)
        assertEquals(27015, record?.remotePort)
        assertEquals(17, record?.transport)
        assertEquals(1, record?.direction)
        assertEquals(128, record?.packetLengthBytes)
    }

    @Test
    fun rejectsInvalidNativeRecord() {
        assertNull(
            HevMetadataNative.decode(
                longArrayOf(4, 16, 17, 1, 0L, 0L, 27015, 128, 0)
            )
        )
    }
}
