package com.novaping.app

import android.os.SystemClock
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException

enum class ProbeFailure(val label: String) {
    DNS("DNS"),
    TIMEOUT("Délai dépassé"),
    TLS("Sécurité TLS"),
    CONNECTION("Connexion"),
    HTTP_STATUS("Réponse HTTP"),
    NETWORK_CHANGED("Réseau modifié pendant la requête"),
    UNKNOWN("Cause indéterminée")
}

data class ProbeOutcome(
    val elapsedMs: Long? = null,
    val failure: ProbeFailure? = null,
    val httpCode: Int? = null
)

/**
 * Measures HTTPS request round-trip time to a public control endpoint.
 * This is not ICMP ping and does not represent a game's server latency.
 */
object HttpsProbe {
    fun measureOnce(endpoint: String): ProbeOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 1800
                instanceFollowRedirects = false
                useCaches = false
                // Force a fresh TCP/TLS exchange for each sample instead of reusing keep-alive.
                setRequestProperty("Connection", "close")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val start = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start
            if (code in 200..299) {
                ProbeOutcome(elapsedMs = elapsed, httpCode = code)
            } else {
                ProbeOutcome(failure = ProbeFailure.HTTP_STATUS, httpCode = code)
            }
        } catch (_: UnknownHostException) {
            ProbeOutcome(failure = ProbeFailure.DNS)
        } catch (_: SocketTimeoutException) {
            ProbeOutcome(failure = ProbeFailure.TIMEOUT)
        } catch (_: SSLException) {
            ProbeOutcome(failure = ProbeFailure.TLS)
        } catch (_: ConnectException) {
            ProbeOutcome(failure = ProbeFailure.CONNECTION)
        } catch (_: NoRouteToHostException) {
            ProbeOutcome(failure = ProbeFailure.CONNECTION)
        } catch (_: IOException) {
            ProbeOutcome(failure = ProbeFailure.UNKNOWN)
        } catch (_: Exception) {
            ProbeOutcome(failure = ProbeFailure.UNKNOWN)
        } finally {
            connection?.disconnect()
        }
    }
}
