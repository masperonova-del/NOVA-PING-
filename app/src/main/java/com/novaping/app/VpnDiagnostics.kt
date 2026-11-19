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
    private var upstreamSocketConnectCalls = 0
    private var upstreamSocketConnectSuccesses = 0
    private var upstreamSocketConnectFailures = 0
    private val upstreamSocketConnectFailureCategories = linkedMapOf<String, Int>()
    private var tcpSuccesses = 0
    private var tcpFailures = 0
    private var tcpProtectionFailures = 0
    private var lastTcp = "Non testé"
    private val tcpFailureCategories = linkedMapOf<String, Int>()
    private val socketProtectionResults = linkedMapOf<String, MutableMap<String, Int>>()
    private val socketProtectionInvalidPreconditions = linkedMapOf<String, Int>()
    private val socketProtectionContexts = linkedMapOf<String, Int>()
    private val socketProtectionRejectedWithAuthorizationPresent = linkedMapOf<String, Int>()
    private val socketProtectionRejectedWithAuthorizationMissing = linkedMapOf<String, Int>()
    private var vpnAuthorizationAtStart = "non vérifiée"
    private var vpnRevocationEvents = 0

    private var authSuccesses = 0
    private var authFailures = 0

    private var udpAssociations = 0
    private var udpAssociationFailures = 0
    private var udpOutbound = 0
    private var udpInbound = 0
    private var udpSendFailures = 0
    private var udpDrops = 0

    // HEV metadata is reduced immediately to transport/direction/size totals.
    // Remote addresses, ports and packet payloads are never stored here.
    private var hevTcpOutboundPackets = 0L
    private var hevTcpInboundPackets = 0L
    private var hevUdpOutboundPackets = 0L
    private var hevUdpInboundPackets = 0L
    private var hevTcpOutboundBytes = 0L
    private var hevTcpInboundBytes = 0L
    private var hevUdpOutboundBytes = 0L
    private var hevUdpInboundBytes = 0L
    private var hevMetadataDroppedSamples = 0L
    private var hevMetadataPollingFailures = 0L

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
        upstreamSocketConnectCalls = 0
        upstreamSocketConnectSuccesses = 0
        upstreamSocketConnectFailures = 0
        upstreamSocketConnectFailureCategories.clear()
        tcpSuccesses = 0
        tcpFailures = 0
        tcpProtectionFailures = 0
        lastTcp = "Non testé"
        tcpFailureCategories.clear()
        socketProtectionResults.clear()
        socketProtectionInvalidPreconditions.clear()
        socketProtectionContexts.clear()
        socketProtectionRejectedWithAuthorizationPresent.clear()
        socketProtectionRejectedWithAuthorizationMissing.clear()
        vpnAuthorizationAtStart = "non vérifiée"
        vpnRevocationEvents = 0

        authSuccesses = 0
        authFailures = 0
        udpAssociations = 0
        udpAssociationFailures = 0
        udpOutbound = 0
        udpInbound = 0
        udpSendFailures = 0
        udpDrops = 0
        hevTcpOutboundPackets = 0L
        hevTcpInboundPackets = 0L
        hevUdpOutboundPackets = 0L
        hevUdpInboundPackets = 0L
        hevTcpOutboundBytes = 0L
        hevTcpInboundBytes = 0L
        hevUdpOutboundBytes = 0L
        hevUdpInboundBytes = 0L
        hevMetadataDroppedSamples = 0L
        hevMetadataPollingFailures = 0L
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
    fun recordUpstreamSocketConnectAttempt() {
        upstreamSocketConnectCalls++
    }

    @Synchronized
    fun recordUpstreamSocketConnectSuccess() {
        upstreamSocketConnectSuccesses++
    }

    @Synchronized
    fun recordUpstreamSocketConnectFailure(category: String) {
        upstreamSocketConnectFailures++
        val safe = safeCategory(category)
        upstreamSocketConnectFailureCategories[safe] =
            (upstreamSocketConnectFailureCategories[safe] ?: 0) + 1
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
    fun recordVpnAuthorizationAtStart(present: Boolean) {
        vpnAuthorizationAtStart = if (present) "présente" else "absente"
    }

    @Synchronized
    fun recordVpnRevocation() {
        vpnRevocationEvents++
    }

    @Synchronized
    fun recordSocketProtectionAuthorization(
        purpose: String,
        outcome: String,
        authorizationPresent: Boolean
    ) {
        if (outcome == "protected") return
        val safePurpose = safeCategory(purpose)
        val counts = if (authorizationPresent) {
            socketProtectionRejectedWithAuthorizationPresent
        } else {
            socketProtectionRejectedWithAuthorizationMissing
        }
        counts[safePurpose] = (counts[safePurpose] ?: 0) + 1
    }

    @Synchronized
    fun recordSocketProtection(purpose: String, outcome: String, invalidPrecondition: Boolean) {
        val safePurpose = safeCategory(purpose)
        val safeOutcome = when (outcome) {
            "protected", "rejected", "security_exception", "invalid_socket", "exception" -> outcome
            else -> "other"
        }
        val results = socketProtectionResults.getOrPut(safePurpose) { linkedMapOf() }
        results[safeOutcome] = (results[safeOutcome] ?: 0) + 1
        if (invalidPrecondition) {
            socketProtectionInvalidPreconditions[safePurpose] =
                (socketProtectionInvalidPreconditions[safePurpose] ?: 0) + 1
        }
    }

    @Synchronized
    fun recordSocketProtectionContext(
        purpose: String,
        outcome: String,
        authorizationPresentBefore: Boolean,
        authorizationPresentAfter: Boolean,
        tunnelEstablished: Boolean,
        tunDescriptorPresent: Boolean,
        engineStarted: Boolean,
        engineThreadAlive: Boolean,
        stopping: Boolean,
        socketClosed: Boolean,
        socketConnected: Boolean,
        socketBound: Boolean
    ) {
        if (outcome != "rejected") return
        val safePurpose = safeCategory(purpose)
        val key = "$safePurpose auth=${yesNo(authorizationPresentBefore)}>${yesNo(authorizationPresentAfter)} " +
            "TUN=${yesNo(tunnelEstablished)} fd=${yesNo(tunDescriptorPresent)} " +
            "HEV=${yesNo(engineStarted)} thread=${yesNo(engineThreadAlive)} " +
            "arrêt=${yesNo(stopping)} socket_closed=${yesNo(socketClosed)} " +
            "connected=${yesNo(socketConnected)} bound=${yesNo(socketBound)}"
        socketProtectionContexts[key] = (socketProtectionContexts[key] ?: 0) + 1
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
    fun recordHevPacket(transport: Int, direction: Int, packetLengthBytes: Int) {
        if (packetLengthBytes <= 0) return
        val size = packetLengthBytes.toLong()
        when {
            transport == 6 && direction == 1 -> {
                hevTcpOutboundPackets++
                hevTcpOutboundBytes += size
            }
            transport == 6 && direction == 2 -> {
                hevTcpInboundPackets++
                hevTcpInboundBytes += size
            }
            transport == 17 && direction == 1 -> {
                hevUdpOutboundPackets++
                hevUdpOutboundBytes += size
            }
            transport == 17 && direction == 2 -> {
                hevUdpInboundPackets++
                hevUdpInboundBytes += size
            }
        }
    }

    @Synchronized
    fun setHevMetadataDroppedSamples(total: Long) {
        hevMetadataDroppedSamples = total.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordHevMetadataPollingFailure() {
        hevMetadataPollingFailures++
    }

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
        appendLine("Autorisation VPN au démarrage : $vpnAuthorizationAtStart")
        appendLine("Événements de révocation Android : $vpnRevocationEvents")
        appendLine("Refus protect() avec autorisation présente : ${formatCategories(socketProtectionRejectedWithAuthorizationPresent)}")
        appendLine("Refus protect() avec autorisation absente : ${formatCategories(socketProtectionRejectedWithAuthorizationMissing)}")
        appendLine()
        appendLine("DNS SOCKS5 (résolution des noms) : $dnsSuccesses succès / $dnsAttempts essais / $dnsFailures échecs")
        appendLine("Dernier DNS : $lastDns")
        appendLine("Catégories DNS : ${formatCategories(dnsFailureCategories)}")
        appendLine()
        appendLine("Demandes SOCKS5 TCP CONNECT : $tcpSuccesses succès / $tcpAttempts demandes / $tcpFailures échecs")
        appendLine("Dernier TCP : $lastTcp")
        appendLine("Appels réels Socket.connect() : $upstreamSocketConnectCalls")
        appendLine("Connexions socket établies : $upstreamSocketConnectSuccesses")
        appendLine("Échecs Socket.connect() : $upstreamSocketConnectFailures")
        appendLine("Catégories Socket.connect() : ${formatCategories(upstreamSocketConnectFailureCategories)}")
        appendLine("Échecs de protection socket : $tcpProtectionFailures")
        appendLine("Catégories TCP : ${formatCategories(tcpFailureCategories)}")
        appendLine()
        appendLine("Protection des sockets :")
        if (socketProtectionResults.isEmpty()) {
            appendLine("Aucun appel de protection enregistré")
        } else {
            socketProtectionResults.forEach { (purpose, results) ->
                appendLine(
                    "$purpose : ${results["protected"] ?: 0} protégés / " +
                        "${results["rejected"] ?: 0} rejetés / " +
                        "${(results["security_exception"] ?: 0) + (results["invalid_socket"] ?: 0) + (results["exception"] ?: 0)} exceptions / " +
                        "${socketProtectionInvalidPreconditions[purpose] ?: 0} préconditions invalides"
                )
            }
        }
        appendLine("Contexte agrégé des refus protect() :")
        if (socketProtectionContexts.isEmpty()) {
            appendLine("Aucun refus enregistré")
        } else {
            socketProtectionContexts.forEach { (context, count) ->
                appendLine("$context : $count")
            }
        }
        appendLine()
        appendLine("Authentification SOCKS5 : $authSuccesses réussites / $authFailures échecs")
        appendLine("Associations UDP : $udpAssociations démarrées / $udpAssociationFailures échecs")
        appendLine("UDP sortants : $udpOutbound")
        appendLine("UDP entrants : $udpInbound")
        appendLine("Échecs d'envoi UDP : $udpSendFailures")
        appendLine("Datagrammes ignorés : $udpDrops")
        appendLine()
        appendLine("HEV — paquets observés (en-têtes uniquement) :")
        appendLine("TCP sortants : $hevTcpOutboundPackets paquets / $hevTcpOutboundBytes octets")
        appendLine("TCP entrants : $hevTcpInboundPackets paquets / $hevTcpInboundBytes octets")
        appendLine("UDP sortants : $hevUdpOutboundPackets paquets / $hevUdpOutboundBytes octets")
        appendLine("UDP entrants : $hevUdpInboundPackets paquets / $hevUdpInboundBytes octets")
        appendLine("Échantillons HEV abandonnés : $hevMetadataDroppedSamples")
        appendLine("Erreurs de lecture des métadonnées HEV : $hevMetadataPollingFailures")
        appendLine()
        append("Confidentialité : aucun domaine, aucune adresse IP, aucun port distant et aucun contenu de paquet enregistré.")
    }

    private fun yesNo(value: Boolean): String = if (value) "oui" else "non"

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
