package com.novaping.app

import android.content.Context

/**
 * In-memory, header-free tunnel diagnostics.
 *
 * Only aggregate counters, lifecycle states and fixed failure categories are kept.
 * Hostnames, IP addresses, ports, payloads and packet contents are never recorded.
 */
internal class VpnDiagnosticTracker {
    private var active = false
    private var tunnelState = "Aucune session"
    private var bridgeState = "Non démarré"
    private var tunState = "Non établi"
    private var engineState = "Non démarré"
    private var engineExitCode: Int? = null

    private var dnsAttempts = 0
    private var dnsSuccesses = 0
    private var dnsFailures = 0
    private var lastDns = "Non testé"
    private val dnsFailureCategories = linkedMapOf<String, Int>()

    private var tcpAttempts = 0
    private var tcpSuccesses = 0
    private var tcpFailures = 0
    private var tcpProtectionFailures = 0
    private var lastTcp = "Non testé"
    private val tcpFailureCategories = linkedMapOf<String, Int>()

    private var authSuccesses = 0
    private var authFailures = 0

    private var udpAssociations = 0
    private var udpAssociationFailures = 0
    private var udpOutbound = 0
    private var udpInbound = 0
    private var udpSendFailures = 0
    private var udpDrops = 0

    @Synchronized
    fun beginSession() {
        active = true
        tunnelState = "Démarrage"
        bridgeState = "Non démarré"
        tunState = "Non établi"
        engineState = "En attente"
        engineExitCode = null

        dnsAttempts = 0
        dnsSuccesses = 0
        dnsFailures = 0
        lastDns = "Non testé"
        dnsFailureCategories.clear()

        tcpAttempts = 0
        tcpSuccesses = 0
        tcpFailures = 0
        tcpProtectionFailures = 0
        lastTcp = "Non testé"
        tcpFailureCategories.clear()

        authSuccesses = 0
        authFailures = 0
        udpAssociations = 0
        udpAssociationFailures = 0
        udpOutbound = 0
        udpInbound = 0
        udpSendFailures = 0
        udpDrops = 0
    }

    @Synchronized fun isSessionActive(): Boolean = active
    @Synchronized fun setTunnelState(value: String) { tunnelState = safeState(value) }
    @Synchronized fun setBridgeState(value: String) { bridgeState = safeState(value) }
    @Synchronized fun setTunState(value: String) { tunState = safeState(value) }
    @Synchronized fun setEngineState(value: String) { engineState = safeState(value) }

    @Synchronized
    fun recordEngineExit(code: Int) {
        engineExitCode = code
        engineState = if (code == 0) "Arrêt normal" else "Arrêt avec erreur"
    }

    @Synchronized
    fun recordEngineFailure() {
        engineState = "Exception native"
    }

    @Synchronized
    fun recordDnsAttempt() {
        dnsAttempts++
        lastDns = "Requête en cours"
    }

    @Synchronized
    fun recordDnsSuccess() {
        dnsSuccesses++
        lastDns = "Réponse valide"
    }

    @Synchronized
    fun recordDnsFailure(category: String) {
        dnsFailures++
        val safe = safeCategory(category)
        lastDns = "Échec ($safe)"
        dnsFailureCategories[safe] = (dnsFailureCategories[safe] ?: 0) + 1
    }

    @Synchronized
    fun recordTcpAttempt() {
        tcpAttempts++
        lastTcp = "Connexion en cours"
    }

    @Synchronized
    fun recordTcpSuccess() {
        tcpSuccesses++
        lastTcp = "Connexion établie"
    }

    @Synchronized
    fun recordTcpFailure(category: String) {
        tcpFailures++
        val safe = safeCategory(category)
        if (safe == "protect_rejected") tcpProtectionFailures++
        lastTcp = "Échec ($safe)"
        tcpFailureCategories[safe] = (tcpFailureCategories[safe] ?: 0) + 1
    }

    @Synchronized
    fun recordAuthentication(success: Boolean) {
        if (success) authSuccesses++ else authFailures++
    }

    @Synchronized
    fun recordUdpAssociationStarted() { udpAssociations++ }

    @Synchronized
    fun recordUdpAssociationFailure() { udpAssociationFailures++ }

    @Synchronized
    fun recordUdpOutbound() { udpOutbound++ }

    @Synchronized
    fun recordUdpInbound() { udpInbound++ }

    @Synchronized
    fun recordUdpSendFailure() { udpSendFailures++ }

    @Synchronized
    fun recordUdpDrop() { udpDrops++ }

    @Synchronized
    fun report(): String = render()

    @Synchronized
    fun finish(): String {
        active = false
        if (tunnelState == "Démarrage") tunnelState = "Arrêté avant la fin du démarrage"
        return render()
    }

    @Synchronized
    private fun render(): String = buildString {
        appendLine("NOVA PING — RAPPORT DU TUNNEL")
        appendLine("Session : ${if (active) "active" else "terminée"}")
        appendLine("État : $tunnelState")
        appendLine("Pont SOCKS5 : $bridgeState")
        appendLine("Interface TUN Android : $tunState")
        append("Moteur HEV : $engineState")
        engineExitCode?.let { append(" (code $it)") }
        appendLine()
        appendLine()
        appendLine("DNS SOCKS5 (résolution des noms) : $dnsSuccesses succès / $dnsAttempts essais / $dnsFailures échecs")
        appendLine("Dernier DNS : $lastDns")
        appendLine("Catégories DNS : ${formatCategories(dnsFailureCategories)}")
        appendLine()
        appendLine("TCP CONNECT : $tcpSuccesses succès / $tcpAttempts essais / $tcpFailures échecs")
        appendLine("Dernier TCP : $lastTcp")
        appendLine("Échecs de protection socket : $tcpProtectionFailures")
        appendLine("Catégories TCP : ${formatCategories(tcpFailureCategories)}")
        appendLine()
        appendLine("Authentification SOCKS5 : $authSuccesses réussites / $authFailures échecs")
        appendLine("Associations UDP : $udpAssociations démarrées / $udpAssociationFailures échecs")
        appendLine("UDP sortants : $udpOutbound")
        appendLine("UDP entrants : $udpInbound")
        appendLine("Échecs d'envoi UDP : $udpSendFailures")
        appendLine("Datagrammes ignorés : $udpDrops")
        appendLine()
        append("Confidentialité : aucun domaine, aucune adresse IP, aucun port distant et aucun contenu de paquet enregistré.")
    }

    private fun formatCategories(values: Map<String, Int>): String =
        if (values.isEmpty()) "aucune" else values.entries.joinToString { "${it.key}=${it.value}" }

    private fun safeState(value: String): String =
        value.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.take(48)

    private fun safeCategory(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32).ifBlank { "other" }
}

internal object VpnDiagnostics {
    private const val PREFERENCES = "nova_ping_vpn_diagnostics"
    private const val LAST_REPORT = "last_report"

    internal val tracker = VpnDiagnosticTracker()

    fun readReport(context: Context): String {
        if (tracker.isSessionActive()) return tracker.report()
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LAST_REPORT, null) ?: tracker.report()
    }

    fun finish(context: Context) {
        val report = tracker.finish()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_REPORT, report)
            .apply()
    }
}
