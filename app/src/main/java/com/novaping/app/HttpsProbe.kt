package com.novaping.app

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures HTTPS request round-trip time to a public control endpoint.
 * This is not ICMP ping and does not represent a game's server latency.
 */
object HttpsProbe {
    fun measureOnce(endpoint: String): Long? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
                useCaches = false
            }
            val start = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start
            if (code in 200..299) elapsed else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
