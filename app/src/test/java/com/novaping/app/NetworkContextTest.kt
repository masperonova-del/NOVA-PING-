package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkContextTest {
    @Test fun identifiesWifi() {
        val context = NetworkContext.from(
            NetworkTransport.WIFI, true, true, false, false, 54000, 12000
        )
        assertTrue(context.isWifi)
        assertFalse(context.isMobile)
        assertEquals(NetworkTransport.WIFI, context.transport)
    }

    @Test fun identifiesCellular() {
        val context = NetworkContext.from(
            NetworkTransport.CELLULAR, true, true, true, false, null, null
        )
        assertTrue(context.isMobile)
        assertFalse(context.isWifi)
        assertTrue(context.metered)
    }
}
