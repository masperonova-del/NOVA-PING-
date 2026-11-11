package com.novaping.app

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class RouteProbeFailure { DNS, TCP_CONNECT, TLS_HANDSHAKE, HTTP_RESPONSE, TIMEOUT, UNKNOWN }

enum class TlsFailureCategory(val label: String) {
    HOSTNAME_MISMATCH("Nom d’hôte du certificat incorrect"),
    CERTIFICATE_EXPIRED("Certificat expiré"),
    CERTIFICATE_UNTRUSTED("Certificat non approuvé"),
    TLS_PROTOCOL_ERROR("Erreur de protocole TLS"),
    TLS_TIMEOUT("Délai dépassé pendant TLS"),
    TLS_UNKNOWN("Erreur TLS non identifiée")
}

object TlsFailureClassifier {
    fun classify(errorType: String?, errorMessage: String?): TlsFailureCategory {
        val details = "${errorType.orEmpty()} ${errorMessage.orEmpty()}".lowercase()
        return when {
            listOf("subjectaltnames", "hostname", "host name", "subject alternative",
                "no name matching", "doesn't match", "does not match").any { details.contains(it) } ->
                TlsFailureCategory.HOSTNAME_MISMATCH
            listOf("expired", "notafter", "certificate_expired").any { details.contains(it) } ->
                TlsFailureCategory.CERTIFICATE_EXPIRED
            listOf("trust anchor", "certification path", "certpath", "not trusted",
                "unable to find valid certification path", "certificate_unknown").any { details.contains(it) } ->
                TlsFailureCategory.CERTIFICATE_UNTRUSTED
            listOf("protocol_version", "handshake_failure", "no appropriate protocol",
                "unsupported protocol", "no cipher", "cipher suite").any { details.contains(it) } ->
                TlsFailureCategory.TLS_PROTOCOL_ERROR
            errorType.orEmpty().contains("SocketTimeoutException", ignoreCase = true) ||
                listOf("timed out", "timeout").any { details.contains(it) } ->
                TlsFailureCategory.TLS_TIMEOUT
            else -> TlsFailureCategory.TLS_UNKNOWN
        }
    }
}

data class TcpAddressAttempt(
    val address: String,
    val addressFamily: String,
    val elapsedMs: Long,
    val errorType: String? = null,
    val errorMessage: String? = null
)

data class RouteProbeOutcome(
    val host: String,
    val dnsMs: Long? = null,
    val tcpConnectMs: Long? = null,
    val tlsHandshakeMs: Long? = null,
    val httpStatusLineMs: Long? = null,
    val httpStatus: Int? = null,
    val failure: RouteProbeFailure? = null,
    val totalMs: Long,
    val resolvedAddresses: List<String> = emptyList(),
    val connectedAddress: String? = null,
    val tcpAttempts: List<TcpAddressAttempt> = emptyList(),
    val errorType: String? = null,
    val errorMessage: String? = null,
    val tlsFailureCategory: TlsFailureCategory? = null
) {
    /** Sum of sequential TCP address attempts; null when no address was attempted. */
    val tcpAttemptTotalMs: Long?
        get() = tcpAttempts.takeIf { it.isNotEmpty() }?.sumOf { it.elapsedMs }

    /** True when DNS, TCP, TLS and an HTTP status line were all observed. */
    val completed: Boolean
        get() = dnsMs != null && tcpConnectMs != null && tlsHandshakeMs != null &&
            httpStatusLineMs != null && httpStatus != null

    /** HTTP application success is separate from successful network transport. */
    val httpResponseSuccessful: Boolean
        get() = completed && httpStatus in 200..299
}

/** Measures fresh HTTPS connections to public control endpoints, not game servers. */
object RouteProbe {
    fun measure(endpoint: String, timeoutMs: Int = 1800): RouteProbeOutcome {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val totalStart = SystemClock.elapsedRealtime()
        var dnsMs: Long? = null
        var tcpMs: Long? = null
        var tlsMs: Long? = null
        var statusLineMs: Long? = null
        var status: Int? = null
        var failure: RouteProbeFailure? = null
        var rawSocket: Socket? = null
        var tlsSocket: SSLSocket? = null
        var host = "destination inconnue"
        var resolvedAddresses = emptyList<String>()
        var connectedAddress: String? = null
        val tcpAttempts = mutableListOf<TcpAddressAttempt>()
        var errorType: String? = null
        var errorMessage: String? = null
        var tlsFailureCategory: TlsFailureCategory? = null

        try {
            val url = URL(endpoint)
            require(url.protocol.equals("https", ignoreCase = true)) {
                "Seules les destinations HTTPS sont acceptées"
            }
            host = url.host
            val port = if (url.port > 0) url.port else 443

            val dnsStart = SystemClock.elapsedRealtime()
            val addresses = InetAddress.getAllByName(host)
            dnsMs = SystemClock.elapsedRealtime() - dnsStart
            resolvedAddresses = addresses.map { it.hostAddress ?: it.toString() }
            if (addresses.isEmpty()) throw java.net.UnknownHostException(host)

            var lastConnectError: java.io.IOException? = null
            for (address in addresses) {
                val candidate = Socket()
                val connectStart = SystemClock.elapsedRealtime()
                val family = if (address is Inet6Address) "IPv6" else "IPv4"
                try {
                    candidate.connect(InetSocketAddress(address, port), timeoutMs)
                    val elapsed = SystemClock.elapsedRealtime() - connectStart
                    tcpMs = elapsed
                    connectedAddress = address.hostAddress
                    tcpAttempts += TcpAddressAttempt(address.hostAddress ?: address.toString(), family, elapsed)
                    rawSocket = candidate
                    break
                } catch (error: java.io.IOException) {
                    val elapsed = SystemClock.elapsedRealtime() - connectStart
                    lastConnectError = error
                    tcpAttempts += TcpAddressAttempt(
                        address = address.hostAddress ?: address.toString(),
                        addressFamily = family,
                        elapsedMs = elapsed,
                        errorType = error.javaClass.simpleName,
                        errorMessage = error.message?.take(180)
                    )
                    try { candidate.close() } catch (_: Exception) { }
                }
            }
            if (rawSocket == null) throw lastConnectError ?: java.io.IOException("Connexion TCP impossible")
            rawSocket.soTimeout = timeoutMs

            tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, host, port, true) as SSLSocket
            tlsSocket.soTimeout = timeoutMs
            tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            val tlsStart = SystemClock.elapsedRealtime()
            tlsSocket.startHandshake()
            tlsMs = SystemClock.elapsedRealtime() - tlsStart

            val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
            val request = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: NOVA-PING\r\n\r\n"
            val output: OutputStream = tlsSocket.outputStream
            val responseStart = SystemClock.elapsedRealtime()
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val statusLine = BufferedReader(
                InputStreamReader(tlsSocket.inputStream, Charsets.US_ASCII)
            ).readLine() ?: throw java.io.IOException("Réponse HTTP vide")
            statusLineMs = SystemClock.elapsedRealtime() - responseStart
            status = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})")
                .find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (status == null) failure = RouteProbeFailure.HTTP_RESPONSE
        } catch (error: java.net.UnknownHostException) {
            failure = RouteProbeFailure.DNS
            errorType = error.javaClass.simpleName
            errorMessage = error.message?.take(180)
        } catch (error: SocketTimeoutException) {
            failure = when {
                dnsMs == null -> RouteProbeFailure.DNS
                tcpMs == null -> RouteProbeFailure.TCP_CONNECT
                tlsMs == null -> RouteProbeFailure.TLS_HANDSHAKE
                else -> RouteProbeFailure.TIMEOUT
            }
            errorType = error.javaClass.simpleName
            errorMessage = error.message?.take(180)
            if (failure == RouteProbeFailure.TLS_HANDSHAKE) tlsFailureCategory = TlsFailureCategory.TLS_TIMEOUT
        } catch (error: SSLException) {
            failure = RouteProbeFailure.TLS_HANDSHAKE
            errorType = error.javaClass.simpleName
            errorMessage = error.message?.take(180)
            tlsFailureCategory = TlsFailureClassifier.classify(errorType, errorMessage)
        } catch (error: java.io.IOException) {
            failure = when {
                dnsMs == null -> RouteProbeFailure.DNS
                tcpMs == null -> RouteProbeFailure.TCP_CONNECT
                tlsMs == null -> RouteProbeFailure.TLS_HANDSHAKE
                else -> RouteProbeFailure.HTTP_RESPONSE
            }
            errorType = error.javaClass.simpleName
            errorMessage = error.message?.take(180)
            if (failure == RouteProbeFailure.TLS_HANDSHAKE) {
                tlsFailureCategory = TlsFailureClassifier.classify(errorType, errorMessage)
            }
        } catch (error: Exception) {
            failure = RouteProbeFailure.UNKNOWN
            errorType = error.javaClass.simpleName
            errorMessage = error.message?.take(180)
            if (failure == RouteProbeFailure.TLS_HANDSHAKE) {
                tlsFailureCategory = TlsFailureClassifier.classify(errorType, errorMessage)
            }
        } finally {
            try { tlsSocket?.close() } catch (_: Exception) { }
            try { rawSocket?.close() } catch (_: Exception) { }
        }

        return RouteProbeOutcome(
            host = host, dnsMs = dnsMs, tcpConnectMs = tcpMs,
            tlsHandshakeMs = tlsMs, httpStatusLineMs = statusLineMs,
            httpStatus = status, failure = failure,
            totalMs = SystemClock.elapsedRealtime() - totalStart,
            resolvedAddresses = resolvedAddresses,
            connectedAddress = connectedAddress,
            tcpAttempts = tcpAttempts.toList(),
            errorType = errorType,
            errorMessage = errorMessage,
            tlsFailureCategory = tlsFailureCategory
        )
    }
}
