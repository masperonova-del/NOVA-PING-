package com.novaping.app

import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock

/**
 * Measures HTTPS request round-trip time to a public connectivity-check endpoint.
 * This is not ICMP ping and does not represent a game's server latency.
 */
object HttpsProbe {
    private const val ENDPOINT = "https://connectivitycheck.gstatic.com/generate_204"

    fun measureOnce(): Long? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
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
