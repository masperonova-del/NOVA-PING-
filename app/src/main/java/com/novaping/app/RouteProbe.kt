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
    val httpFirstByteMs: Long? = null,
    val httpStatus: Int? = null,
    val failure: RouteProbeFailure? = null,
    val totalMs: Long
) {
    val completed: Boolean
        get() = dnsMs != null && tcpConnectMs != null && tlsHandshakeMs != null &&
            httpFirstByteMs != null && httpStatus != null
}

/**
 * Measures the sequential phases of one HTTPS connection to a public control endpoint.
 * It does not discover intermediate router hops and does not measure a game's match server.
 */
object RouteProbe {
    fun measure(endpoint: String, timeoutMs: Int = 1800): RouteProbeOutcome {
        val totalStart = SystemClock.elapsedRealtime()
        var dnsMs: Long? = null
        var tcpMs: Long? = null
        var tlsMs: Long? = null
        var responseMs: Long? = null
        var status: Int? = null
        var failure: RouteProbeFailure? = null
        var rawSocket: Socket? = null
        var tlsSocket: SSLSocket? = null
        var host = "destination inconnue"

        try {
            val url = URL(endpoint)
            host = url.host
            val dnsStart = SystemClock.elapsedRealtime()
            val address = InetAddress.getAllByName(host).firstOrNull()
                ?: throw java.net.UnknownHostException(host)
            dnsMs = SystemClock.elapsedRealtime() - dnsStart

            rawSocket = Socket()
            val tcpStart = SystemClock.elapsedRealtime()
            rawSocket.connect(InetSocketAddress(address, if (url.port > 0) url.port else 443), timeoutMs)
            tcpMs = SystemClock.elapsedRealtime() - tcpStart
            rawSocket.soTimeout = timeoutMs

            tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, host, if (url.port > 0) url.port else 443, true) as SSLSocket
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
            val statusLine = BufferedReader(InputStreamReader(tlsSocket.inputStream, Charsets.US_ASCII)).readLine()
                ?: throw java.io.IOException("Réponse HTTP vide")
            responseMs = SystemClock.elapsedRealtime() - responseStart
            status = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})")
                .find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (status == null) failure = RouteProbeFailure.HTTP_RESPONSE
        } catch (_: java.net.UnknownHostException) {
            failure = RouteProbeFailure.DNS
        } catch (_: SocketTimeoutException) {
            failure = if (dnsMs == null) RouteProbeFailure.DNS
                else if (tcpMs == null) RouteProbeFailure.TCP_CONNECT
                else if (tlsMs == null) RouteProbeFailure.TLS_HANDSHAKE
                else RouteProbeFailure.TIMEOUT
        } catch (_: SSLException) {
            failure = RouteProbeFailure.TLS_HANDSHAKE
        } catch (_: java.io.IOException) {
            failure = if (dnsMs == null) RouteProbeFailure.DNS
                else if (tcpMs == null) RouteProbeFailure.TCP_CONNECT
                else if (tlsMs == null) RouteProbeFailure.TLS_HANDSHAKE
                else RouteProbeFailure.HTTP_RESPONSE
        } catch (_: Exception) {
            failure = RouteProbeFailure.UNKNOWN
        } finally {
            try { tlsSocket?.close() } catch (_: Exception) { }
            try { rawSocket?.close() } catch (_: Exception) { }
        }

        return RouteProbeOutcome(
            host, dnsMs, tcpMs, tlsMs, responseMs, status, failure,
            SystemClock.elapsedRealtime() - totalStart
        )
    }
}
