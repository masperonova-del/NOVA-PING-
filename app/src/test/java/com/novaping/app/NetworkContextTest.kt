package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkContextTest {
    @Test fun identifiesWifi() {
        val context = NetworkContext.from(
            NetworkTransport.WIFI, true, true, false, null, 54000, 12000
        )
        assertTrue(context.isWifi)
        assertFalse(context.isMobile)
        assertEquals(NetworkTransport.WIFI, context.transport)
        assertNull(context.roaming)
    }

    @Test fun identifiesCellularWithoutGuessingRoaming() {
        val context = NetworkContext.from(
            NetworkTransport.CELLULAR, true, true, true, null, null, null
        )
        assertTrue(context.isMobile)
        assertFalse(context.isWifi)
        assertTrue(context.metered)
        assertNull(context.roaming)
    }
}
