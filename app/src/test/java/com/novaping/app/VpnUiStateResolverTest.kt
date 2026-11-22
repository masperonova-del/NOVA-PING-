package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnUiStateResolverTest {
    @Test fun staleStartingStateWithoutVpnBecomesStopped() {
        assertEquals("stopped", VpnUiStateResolver.resolve("starting", false))
    }

    @Test fun startingStateIsKeptWhileVpnTransportExists() {
        assertEquals("starting", VpnUiStateResolver.resolve("starting", true))
    }

    @Test fun readyStateIsKeptWhileVpnTransportExists() {
        assertEquals("ready", VpnUiStateResolver.resolve("ready", true))
    }

    @Test fun staleReadyStateWithoutVpnBecomesStopped() {
        assertEquals("stopped", VpnUiStateResolver.resolve("ready", false))
    }

    @Test fun activeVpnWithoutNovaStateIsReportedAsOtherVpn() {
        assertEquals("other_vpn", VpnUiStateResolver.resolve(null, true))
    }

    @Test fun replacedStateIsStoppedWhenNoVpnRemains() {
        assertEquals("stopped", VpnUiStateResolver.resolve("replaced", false))
    }

    @Test fun replacedStateIsPreservedWhileReplacementVpnIsActive() {
        assertEquals("replaced", VpnUiStateResolver.resolve("replaced", true))
    }

    @Test fun persistedErrorsRemainVisible() {
        assertEquals("error", VpnUiStateResolver.resolve("error", false))
    }
}
