package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkReadinessTest {
    @Test fun detectsMissingNetwork() {
        assertEquals(NetworkReadiness.NO_NETWORK, NetworkReadiness.from(false, false, false))
    }

    @Test fun detectsNetworkWithoutInternetCapability() {
        assertEquals(NetworkReadiness.NO_INTERNET_CAPABILITY, NetworkReadiness.from(true, false, false))
    }

    @Test fun detectsUnvalidatedInternet() {
        assertEquals(NetworkReadiness.NOT_VALIDATED, NetworkReadiness.from(true, true, false))
    }

    @Test fun acceptsValidatedInternet() {
        assertEquals(NetworkReadiness.READY, NetworkReadiness.from(true, true, true))
    }
}
