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
    fun reportSeparatesSocketProtectionRejectionsAndPreconditions() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.recordSocketProtection("TCP_CONNECT", "rejected", false)
        tracker.recordSocketProtection("DNS_UDP", "security_exception", true)

        val report = tracker.report()

        assertTrue(report.contains("TCP_CONNECT : 0 protégés / 1 rejetés"))
        assertTrue(report.contains("DNS_UDP : 0 protégés / 0 rejetés / 1 exceptions / 1 préconditions invalides"))
    }

    @Test
    fun reportSeparatesProtectRejectionsByAuthorizationSnapshot() {
        val tracker = VpnDiagnosticTracker()
        tracker.beginSession()
        tracker.recordVpnAuthorizationAtStart(true)
        tracker.recordSocketProtection("TCP_CONNECT", "rejected", false)
        tracker.recordSocketProtectionAuthorization("TCP_CONNECT", "rejected", true)
        tracker.recordSocketProtectionAuthorization("DNS_UDP", "security_exception", false)
        tracker.recordVpnRevocation()

        val report = tracker.report()

        assertTrue(report.contains("Autorisation VPN au démarrage : présente"))
        assertTrue(report.contains("Événements de révocation Android : 1"))
        assertTrue(report.contains("Refus protect() avec autorisation présente : TCP_CONNECT=1"))
        assertTrue(report.contains("Refus protect() avec autorisation absente : DNS_UDP=1"))
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
        tracker.recordVpnAuthorizationAtStart(true)
        tracker.recordVpnRevocation()
        tracker.recordSocketProtectionAuthorization("TCP_CONNECT", "rejected", false)
        tracker.finish()

        tracker.beginSession()
        val report = tracker.report()

        assertTrue(report.contains("UDP sortants : 0"))
        assertTrue(report.contains("Autorisation VPN au démarrage : non vérifiée"))
        assertTrue(report.contains("Événements de révocation Android : 0"))
        assertTrue(report.contains("Refus protect() avec autorisation absente : aucune"))
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
