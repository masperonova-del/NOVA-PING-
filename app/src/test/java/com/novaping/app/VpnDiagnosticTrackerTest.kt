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
        assertTrue(report.contains("DNS SOCKS5 (résolution des noms) : 1 succès / 1 essais / 0 échecs"))
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
    fun reportSeparatesProtectOutcomesAuthorizationSnapshotAndRevocation() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.recordVpnAuthorizationAtStart(true)
        tracker.recordSocketProtection("TCP", accepted = false, authorizationPresent = true)
        tracker.recordSocketProtection("TCP", accepted = false, authorizationPresent = false)
        tracker.recordSocketProtection("TCP", accepted = false, authorizationPresent = true, threw = true)
        tracker.recordSocketProtection("UDP", accepted = true, authorizationPresent = true)
        tracker.recordVpnRevocation()

        val report = tracker.report()

        assertTrue(report.contains("Autorisation VPN au démarrage : présente"))
        assertTrue(report.contains("Événements de révocation Android : 1"))
        assertTrue(report.contains("Protection sockets TCP : 0 acceptés / 2 refusés / 1 exceptions sur 3 appels"))
        assertTrue(report.contains("Protection sockets UDP : 1 acceptés / 0 refusés / 0 exceptions sur 1 appels"))
        assertTrue(report.contains("Refus protect() avec autorisation présente : 2"))
        assertTrue(report.contains("Refus protect() avec autorisation absente : 1"))
    }

    @Test
    fun newSessionResetsPreviousCounters() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.recordUdpOutbound()
        tracker.recordVpnRevocation()
        tracker.recordSocketProtection("TCP", accepted = false, authorizationPresent = false)
        tracker.finish()

        tracker.beginSession()
        val report = tracker.report()

        assertTrue(report.contains("UDP sortants : 0"))
        assertTrue(report.contains("Événements de révocation Android : 0"))
        assertTrue(report.contains("Protection sockets TCP : 0 acceptés / 0 refusés / 0 exceptions sur 0 appels"))
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
