package com.novaping.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-started foreground monitor. It remains active when MainActivity is not visible.
 * It records Android network state only; it does not measure game-server ping or inspect traffic.
 */
class NetworkMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.novaping.app.action.START_NETWORK_MONITOR"
        const val ACTION_STOP = "com.novaping.app.action.STOP_NETWORK_MONITOR"
        private const val CHANNEL_ID = "nova_network_monitor"
        private const val NOTIFICATION_ID = 4101
        private const val SAMPLE_INTERVAL_MS = 60_000L
        private const val MAX_EVENTS = 15
        private const val PREFS_NAME = "nova_ping_history"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tracker = NetworkStabilityTracker()
    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private lateinit var connectivityManager: ConnectivityManager
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var mobileCallback: ConnectivityManager.NetworkCallback? = null
    private val wifiNetworks = NetworkPresenceRegistry()
    private val mobileNetworks = NetworkPresenceRegistry()
    @Volatile private var defaultNetwork: Network? = null
    private var monitoring = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            recordPeriodicSnapshot()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            if (!monitoring) startMonitoring()
            return START_STICKY
        } catch (error: Exception) {
            preferences.edit()
                .putBoolean("stability_monitor_running", false)
                .putString("stability_monitor_error", "Surveillance indisponible : ${error.message ?: "erreur Android"}")
                .apply()
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    private fun notifyMonitorStateChanged() {
        sendBroadcast(
            Intent("com.novaping.app.action.STABILITY_HISTORY_UPDATED").setPackage(packageName)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Surveillance réseau NOVA PING",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indique lorsque la surveillance réseau continue est active."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, NetworkMonitorService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            4102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("NOVA PING — surveillance active")
            .setContentText("État du réseau enregistré environ chaque minute")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Arrêter", stopPendingIntent)
            .build()
    }

    private fun startMonitoring() {
        val now = System.currentTimeMillis()
        val wifi = wifiNetworks.synchronize(networksForTransport(NetworkCapabilities.TRANSPORT_WIFI))
        tracker.synchronizeWifiState(
            available = wifi.available,
            internetValidated = wifi.internetValidated,
            timestampMillis = now
        ).forEach(::recordEvent)
        val mobile = mobileNetworks.synchronize(networksForTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        tracker.synchronizeMobileState(
            available = mobile.available,
            internetValidated = mobile.internetValidated,
            timestampMillis = now
        ).forEach(::recordEvent)
        defaultNetwork = connectivityManager.activeNetwork
        tracker.synchronizeDefaultTransport(transportName(currentActiveCapabilities()))

        defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Update identity before capabilities arrive so a late onLost for
                // the previous default network cannot overwrite the new network.
                defaultNetwork = network
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                defaultNetwork = network
                recordEvent(tracker.onDefaultTransportChanged(transportName(capabilities), System.currentTimeMillis()))
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    recordEvent(tracker.onDefaultTransportChanged("Aucun", System.currentTimeMillis()))
                }
            }
        }
        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val before = wifiNetworks.snapshot()
                val after = wifiNetworks.onAvailable(network.toString())
                applyWifiSnapshot(before, after)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val before = wifiNetworks.snapshot()
                    val after = wifiNetworks.onCapabilitiesChanged(
                        network.toString(),
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    )
                    applyWifiSnapshot(before, after)
                }
            }

            override fun onLost(network: Network) {
                val before = wifiNetworks.snapshot()
                val after = wifiNetworks.onLost(network.toString())
                applyWifiSnapshot(before, after)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(defaultCallback!!)
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            wifiCallback!!
        )
        mobileCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val before = mobileNetworks.snapshot()
                val after = mobileNetworks.onAvailable(network.toString())
                applyMobileSnapshot(before, after)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    val before = mobileNetworks.snapshot()
                    val after = mobileNetworks.onCapabilitiesChanged(
                        network.toString(),
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    )
                    applyMobileSnapshot(before, after)
                }
            }

            override fun onLost(network: Network) {
                val before = mobileNetworks.snapshot()
                val after = mobileNetworks.onLost(network.toString())
                applyMobileSnapshot(before, after)
            }
        }
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build(),
            mobileCallback!!
        )

        monitoring = true
        preferences.edit()
            .putBoolean("stability_monitor_running", true)
            .remove("stability_monitor_error")
            .apply()
        notifyMonitorStateChanged()
        recordPeriodicSnapshot()
        handler.removeCallbacks(sampleRunnable)
        handler.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS)
    }

    private fun networksForTransport(transport: Int): Map<String, Boolean> =
        connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(transport) == true) {
                network.toString() to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else null
        }.toMap()

    private fun applyWifiSnapshot(
        before: NetworkPresenceSnapshot,
        after: NetworkPresenceSnapshot
    ) {
        val nowEpochMillis = System.currentTimeMillis()
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (before.available != after.available) {
            recordEvent(tracker.onWifiAvailabilityChanged(
                after.available, nowEpochMillis, nowElapsedMillis
            ))
        }
        if (after.available && after.internetValidated != null &&
            before.internetValidated != after.internetValidated
        ) {
            recordEvent(tracker.onWifiInternetValidationChanged(
                after.internetValidated, nowEpochMillis, nowElapsedMillis
            ))
        }
    }

    private fun applyMobileSnapshot(
        before: NetworkPresenceSnapshot,
        after: NetworkPresenceSnapshot
    ) {
        val now = System.currentTimeMillis()
        if (before.available != after.available) {
            recordEvent(tracker.onMobileAvailabilityChanged(after.available, now))
        }
        if (after.available && after.internetValidated != null &&
            before.internetValidated != after.internetValidated
        ) {
            recordEvent(tracker.onMobileInternetValidationChanged(after.internetValidated, now))
        }
    }

    private fun currentActiveCapabilities(): NetworkCapabilities? =
        connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

    private fun transportName(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "Aucun"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Autre"
    }

    private fun recordPeriodicSnapshot() {
        val wifi = wifiNetworks.snapshot()
        val active = currentActiveCapabilities()
        recordEvent(
            tracker.periodicSnapshot(
                wifiAvailable = wifi.available,
                internetValidated = wifi.internetValidated,
                activeTransport = transportName(active),
                activeInternetAvailable = active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                activeInternetValidated = active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                timestampMillis = System.currentTimeMillis()
            )
        )
    }

    private fun recordEvent(event: NetworkStabilityEvent?) {
        if (event == null) return
        val history = try {
            JSONArray(preferences.getString("stability_events", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        history.put(JSONObject().apply {
            put("type", event.type.name)
            put("timestamp", event.timestampMillis)
            put("details", event.details)
        })
        val trimmed = JSONArray()
        for (index in maxOf(0, history.length() - MAX_EVENTS) until history.length()) {
            trimmed.put(history.get(index))
        }
        preferences.edit().putString("stability_events", trimmed.toString()).apply()
        sendBroadcast(Intent("com.novaping.app.action.STABILITY_HISTORY_UPDATED").setPackage(packageName))
    }

    private fun stopMonitoring() {
        monitoring = false
        handler.removeCallbacks(sampleRunnable)
        defaultCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        wifiCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        mobileCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        defaultCallback = null
        wifiCallback = null
        mobileCallback = null
        preferences.edit().putBoolean("stability_monitor_running", false).apply()
        notifyMonitorStateChanged()
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
