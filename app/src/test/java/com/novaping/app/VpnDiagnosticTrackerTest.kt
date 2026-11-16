package com.novaping.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDiagnosticTrackerTest {
    @Test
    fun reportSummarizesDnsTcpUdpAndEngineStates() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.setBridgeState("Prêt")
        tracker.setTunState("Établi")
        tracker.setEngineState("Appel natif actif")
        tracker.recordDnsAttempt()
        tracker.recordDnsSuccess()
        tracker.recordTcpAttempt()
        tracker.recordTcpFailure("connect_timeout")
        tracker.recordAuthentication(true)
        tracker.recordUdpAssociationStarted()
        tracker.recordUdpOutbound()
        tracker.recordUdpInbound()

        val report = tracker.report()

        assertTrue(report.contains("Session : active"))
        assertTrue(report.contains("Pont SOCKS5 : Prêt"))
        assertTrue(report.contains("Interface TUN Android : Établi"))
        assertTrue(report.contains("DNS : 1 succès / 1 essais / 0 échecs"))
        assertTrue(report.contains("TCP CONNECT : 0 succès / 1 essais / 1 échecs"))
        assertTrue(report.contains("connect_timeout=1"))
        assertTrue(report.contains("UDP sortants : 1"))
        assertTrue(report.contains("UDP entrants : 1"))
    }

    @Test
    fun reportDoesNotStoreHostnamesOrPacketPayloads() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.recordDnsFailure("udp_timeout")
        tracker.recordTcpFailure("protect_rejected")

        val report = tracker.report()

        assertFalse(report.contains("example.com"))
        assertFalse(report.contains("198.51.100.4"))
        assertTrue(report.contains("aucun domaine"))
        assertTrue(report.contains("aucune adresse IP"))
        assertTrue(report.contains("aucun contenu de paquet"))
    }

    @Test
    fun newSessionResetsPreviousCounters() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.recordUdpOutbound()
        tracker.finish()

        tracker.beginSession()
        val report = tracker.report()

        assertTrue(report.contains("UDP sortants : 0"))
        assertTrue(report.contains("Session : active"))
    }

    @Test
    fun finishMarksSessionAsTerminatedAndKeepsSummary() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.setTunnelState("HEV démarré")
        tracker.recordEngineExit(0)

        val report = tracker.finish()

        assertTrue(report.contains("Session : terminée"))
        assertTrue(report.contains("Moteur HEV : Arrêt normal (code 0)"))
    }
}
