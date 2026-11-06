package com.novaping.app

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class RouteProbeFailure {
    DNS, TCP_CONNECT, TLS_HANDSHAKE, HTTP_RESPONSE, TIMEOUT, UNKNOWN
}

data class RouteProbeOutcome(
    val host: String,
    val dnsMs: Long? = null,
    val tcpConnectMs: Long? = null,
    val tlsHandshakeMs: Long? = null,
    val httpStatusLineMs: Long? = null,
    val httpStatus: Int? = null,
    val failure: RouteProbeFailure? = null,
    val totalMs: Long
) {
    val completed: Boolean
        get() = dnsMs != null && tcpConnectMs != null && tlsHandshakeMs != null &&
            httpStatusLineMs != null && httpStatus != null
}

/**
 * Measures sequential phases of HTTPS connections to public control endpoints.
 * DNS resolution uses Android's resolver; TCP tries each returned address until one connects.
 * It does not discover router hops or measure a game's match server.
 */
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
            if (addresses.isEmpty()) throw java.net.UnknownHostException(host)

            var lastConnectError: java.io.IOException? = null
            for (address in addresses) {
                val candidate = Socket()
                val connectStart = SystemClock.elapsedRealtime()
                try {
                    candidate.connect(InetSocketAddress(address, port), timeoutMs)
                    tcpMs = SystemClock.elapsedRealtime() - connectStart
                    rawSocket = candidate
                    break
                } catch (error: java.io.IOException) {
                    lastConnectError = error
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
            val request = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: NOVA-PING/1.4\r\n\r\n"
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
        } catch (_: java.net.UnknownHostException) {
            failure = RouteProbeFailure.DNS
        } catch (_: SocketTimeoutException) {
            failure = when {
                dnsMs == null -> RouteProbeFailure.DNS
                tcpMs == null -> RouteProbeFailure.TCP_CONNECT
                tlsMs == null -> RouteProbeFailure.TLS_HANDSHAKE
                else -> RouteProbeFailure.TIMEOUT
            }
        } catch (_: SSLException) {
            failure = RouteProbeFailure.TLS_HANDSHAKE
        } catch (_: java.io.IOException) {
            failure = when {
                dnsMs == null -> RouteProbeFailure.DNS
                tcpMs == null -> RouteProbeFailure.TCP_CONNECT
                tlsMs == null -> RouteProbeFailure.TLS_HANDSHAKE
                else -> RouteProbeFailure.HTTP_RESPONSE
            }
        } catch (_: Exception) {
            failure = RouteProbeFailure.UNKNOWN
        } finally {
            try { tlsSocket?.close() } catch (_: Exception) { }
            try { rawSocket?.close() } catch (_: Exception) { }
        }

        return RouteProbeOutcome(
            host, dnsMs, tcpMs, tlsMs, statusLineMs, status, failure,
            SystemClock.elapsedRealtime() - totalStart
        )
    }
}
