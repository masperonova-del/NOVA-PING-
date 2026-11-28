package com.novaping.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started per-app VPN lifecycle. Only the selected game package is routed.
 * HEV and the protected local SOCKS5/DNS upstream run outside the VPN itself.
 */
internal class NovaVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.novaping.app.action.START_GAME_TUNNEL"
        const val ACTION_STOP = "com.novaping.app.action.STOP_GAME_TUNNEL"
        const val ACTION_STATE = "com.novaping.app.action.GAME_TUNNEL_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val EXTRA_TRANSPORT_STATUS = "mobile_transport_status"
        internal const val VPN_PREFERENCES = "nova_ping_vpn"
        internal const val KEY_MOBILE_TRANSPORT_STATUS = "mobile_transport_status"
        private const val CHANNEL_ID = "nova_ping_vpn"
        private const val NOTIFICATION_ID = 4201
        private const val KEY_SELECTED_GAME_PACKAGE = "selected_game_package"
        private const val KEY_LAST_TUNNEL_STATE = "last_tunnel_state"
        private const val KEY_LAST_TUNNEL_ERROR = "last_tunnel_error"
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"

        internal fun saveSelectedGamePackage(context: Context, packageName: String) {
            val validPackage = VpnGameSelection.validatePackageName(packageName)
            require(validPackage != context.packageName) {
                "NOVA PING cannot select itself as the game app"
            }
            context.packageManager.getApplicationInfo(validPackage, 0)
            context.getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY_SELECTED_GAME_PACKAGE, validPackage).apply()
        }

        internal fun readLastTunnelState(context: Context): Pair<String?, String?> {
            val preferences = context.getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE)
            return preferences.getString(KEY_LAST_TUNNEL_STATE, null) to
                preferences.getString(KEY_LAST_TUNNEL_ERROR, null)
        }

        internal fun readSelectedGamePackage(context: Context): String? {
            val saved = context.getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_GAME_PACKAGE, null) ?: return null
            return try {
                val validPackage = VpnGameSelection.validatePackageName(saved)
                if (validPackage == context.packageName) return null
                context.packageManager.getApplicationInfo(validPackage, 0)
                validPackage
            } catch (_: Exception) {
                null
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private val metadataPolling = AtomicBoolean(false)
    @Volatile private var metadataThread: Thread? = null
    @Volatile private var readinessThread: Thread? = null
    @Volatile private var engineStarted = false
    @Volatile private var engineThread: Thread? = null
    @Volatile private var bridge: LocalSocks5Bridge? = null
    @Volatile private var tunDescriptor: Int? = null
    @Volatile private var tunnelEstablished = false
    @Volatile private var terminalError: String? = null
    @Volatile private var terminalState: String? = null
    @Volatile private var mobileTransportPreferenceEnabled = false
    @Volatile private var preferredMobileNetwork: Network? = null
    @Volatile private var mobileNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var connectivityManager: ConnectivityManager? = null

    private fun publishMobileTransportStatus(status: String) {
        getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_MOBILE_TRANSPORT_STATUS, status)
            .commit()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, readLastTunnelState(this@NovaVpnService).first)
            putExtra(EXTRA_TRANSPORT_STATUS, status)
        })
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (tunnelEstablished || engineThread?.isAlive == true) return START_NOT_STICKY
                VpnDiagnostics.tracker.beginSession()
                terminalState = null
                terminalError = null
                try {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startTunnel()
                } catch (error: Exception) {
                    terminalError = error.message ?: "Démarrage VPN impossible"
                    if (mobileTransportPreferenceEnabled) {
                        val transportStatus = getSharedPreferences(
                            VPN_PREFERENCES, Context.MODE_PRIVATE
                        ).getString(KEY_MOBILE_TRANSPORT_STATUS, null)
                        if (transportStatus !in setOf("unavailable", "bind_failed", "selection_failed", "monitor_failed", "mobile_lost")) {
                            publishMobileTransportStatus("start_failed")
                        }
                    }
                    VpnDiagnostics.tracker.setTunnelState("Échec au démarrage")
                    VpnDiagnostics.tracker.setBridgeState("Échec ou non démarré")
                    VpnDiagnostics.tracker.setTunState("Non établi")
                    stopHevReadinessMonitor()
                    stopHevMetadataPolling()
                    unregisterPreferredMobileNetworkMonitor()
                    preferredMobileNetwork = null
                    mobileTransportPreferenceEnabled = false
                    runCatching { setUnderlyingNetworks(null) }
                    bridge?.close()
                    bridge = null
                    tunDescriptor?.let { fd ->
                        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    }
                    tunDescriptor = null
                    tunnelEstablished = false
                    engineStarted = false
                    engineThread = null
                    stopping.set(false)
                    publishState("error", terminalError)
                    VpnDiagnostics.finish(this)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startTunnel() {
        val vpnAuthorizationPresent = VpnService.prepare(this) == null
        VpnDiagnostics.tracker.recordVpnAuthorizationAtStart(vpnAuthorizationPresent)
        check(vpnAuthorizationPresent) {
            "Le consentement VPN Android est requis avant le démarrage"
        }
        val gamePackage = readSelectedGamePackage(this)
            ?: throw IllegalStateException("Aucun jeu installé n'est sélectionné")
        // In per-app mode, only the selected game is routed into TUN. NOVA PING's
        // own process is outside the VPN unless its package is explicitly allowed.
        val allowedVpnPackages = setOf(gamePackage)
        val upstreamSocketsRequireProtection =
            VpnRoutingPolicy.requiresSocketProtection(packageName, allowedVpnPackages)

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        mobileTransportPreferenceEnabled = getSharedPreferences(
            "nova_mobile_data_lock", Context.MODE_PRIVATE
        ).getBoolean("enabled", false)
        publishMobileTransportStatus(if (mobileTransportPreferenceEnabled) "selecting" else "disabled")

        val mobileNetwork = if (mobileTransportPreferenceEnabled) {
            connectivity.allNetworks
                .mapNotNull { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    if (capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) {
                        network to capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    } else null
                }
                .sortedByDescending { it.second }
                .firstOrNull()?.first
                ?: run {
                    publishMobileTransportStatus("unavailable")
                    throw IllegalStateException(
                        "Mobile Data Lock est activé, mais aucun réseau mobile Internet n'est disponible. Le Wi-Fi ne sera pas utilisé en remplacement."
                    )
                }
        } else null

        preferredMobileNetwork = mobileNetwork

        val underlying = mobileNetwork ?: connectivity.activeNetwork
            ?: throw IllegalStateException("Aucun réseau Internet actif")
        val linkProperties: LinkProperties = connectivity.getLinkProperties(underlying)
            ?: throw IllegalStateException("Configuration du réseau indisponible")
        val dnsServer = linkProperties.dnsServers.firstOrNull()
            ?: throw IllegalStateException("Aucun serveur DNS numérique fourni par le réseau")

        val resolver = ProtectedDnsResolver(
            resolverAddress = dnsServer,
            protectDatagramSocket = { protectSocket(it, "DNS_UDP", upstreamSocketsRequireProtection) },
            protectTcpSocket = { protectSocket(it, "DNS_TCP", upstreamSocketsRequireProtection) }
        )
        val localBridge = LocalSocks5Bridge(
            protectUpstreamSocket = { protectSocket(it, "TCP_CONNECT", upstreamSocketsRequireProtection) },
            bindAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            protectUpstreamDatagramSocket = { protectSocket(it, "UDP_RELAY", upstreamSocketsRequireProtection) },
            protectedDnsResolver = resolver
        )
        bridge = localBridge
        val socksPort = localBridge.start()
        VpnDiagnostics.tracker.setBridgeState("Prêt")

        val builder = Builder()
            .setSession("NOVA PING — diagnostic réseau")
            .setMtu(1500)
            .addAddress(TUN_IPV4, 32)
            .addAddress(TUN_IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addAllowedApplication(gamePackage)

        // Configure the VPN's underlying network before establish().
        // VpnService.setUnderlyingNetworks() is only valid after the VPN is established.
        if (mobileNetwork != null) {
            try {
                builder.setUnderlyingNetworks(arrayOf(mobileNetwork))
                publishMobileTransportStatus("selected")
            } catch (error: Exception) {
                publishMobileTransportStatus("selection_failed")
                throw IllegalStateException(
                    "Impossible de configurer le réseau mobile sous-jacent du VPN.",
                    error
                )
            }
        }

        val pfd = builder.establish()
            ?: throw IllegalStateException("Android n'a pas pu établir l'interface VPN")
        VpnDiagnostics.tracker.setTunState("Établi")
        VpnDiagnostics.tracker.setTunnelState("Interface VPN établie")
        val fd = try {
            pfd.detachFd()
        } finally {
            runCatching { pfd.close() }
        }
        tunDescriptor = fd

        val config = HevTunnelConfig.create(socksPort, localBridge.credentials)
        check(HevTunnelNative.isAvailable()) {
            "Bibliothèque HEV indisponible pour cette architecture"
        }

        stopping.set(false)
        tunnelEstablished = true
        if (mobileTransportPreferenceEnabled && mobileNetwork != null) {
            registerPreferredMobileNetworkMonitor(connectivity, mobileNetwork)
        }
        publishState("starting", null)
        val thread = Thread({
            engineStarted = true
            VpnDiagnostics.tracker.setEngineState("Initialisation native en cours")
            VpnDiagnostics.tracker.setTunnelState("Initialisation HEV non confirmée")
            try {
                val result = HevTunnelNative.runBlocking(config, fd)
                VpnDiagnostics.tracker.recordEngineExit(result)
                if (!stopping.get() && result != 0) {
                    terminalError = "Le moteur HEV s'est arrêté avec le code $result"
                    publishState("error", terminalError)
                }
            } catch (error: Throwable) {
                VpnDiagnostics.tracker.recordEngineFailure()
                if (!stopping.get()) {
                    terminalError = error.message ?: "Échec du moteur HEV"
                    publishState("error", terminalError)
                }
            } finally {
                stopHevReadinessMonitor()
                stopHevMetadataPolling()
                engineStarted = false
                tunDescriptor = null
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                mainHandler.post { finishTunnel() }
            }
        }, "NovaHevEngine").apply { isDaemon = true }
        engineThread = thread
        startHevMetadataPolling()
        thread.start()
        startHevReadinessMonitor(thread)
    }

    /**
     * Confirms that HEV has entered its native event loop before reporting the tunnel active.
     * The probe is a native atomic flag set by HEV's event task, not by Kotlin thread creation.
     */
    private fun startHevReadinessMonitor(engine: Thread) {
        val monitor = Thread({
            while (!stopping.get() && engine.isAlive) {
                val ready = runCatching { HevTunnelNative.isEngineReady() }.getOrDefault(false)
                if (ready) {
                    if (!stopping.get() && engine.isAlive) {
                        VpnDiagnostics.tracker.setEngineState("Initialisé — boucle native active")
                        VpnDiagnostics.tracker.setTunnelState("HEV actif")
                        publishState("ready", null)
                    }
                    return@Thread
                }
                try {
                    Thread.sleep(50L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "NovaHevReadinessMonitor").apply { isDaemon = true }
        readinessThread = monitor
        monitor.start()
    }

    private fun stopHevReadinessMonitor() {
        val monitor = readinessThread
        readinessThread = null
        monitor?.interrupt()
    }

    /**
     * Reads the native HEV metadata queues on their single supported consumer.
     * Only protocol, direction and packet size are copied into diagnostics.
     */
    private fun startHevMetadataPolling() {
        if (!HevMetadataNative.isAvailable() ||
            !metadataPolling.compareAndSet(false, true)
        ) return

        val poller = Thread({
            while (metadataPolling.get()) {
                try {
                    val records = HevMetadataNative.drainAvailable().get(1, TimeUnit.SECONDS)
                    records.forEach { record ->
                        VpnDiagnostics.tracker.recordHevPacket(
                            transport = record.transport,
                            direction = record.direction,
                            packetLengthBytes = record.packetLengthBytes
                        )
                    }
                    val dropped = HevMetadataNative.droppedSamples().get(1, TimeUnit.SECONDS)
                    VpnDiagnostics.tracker.setHevMetadataDroppedSamples(dropped)
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {
                    VpnDiagnostics.tracker.recordHevMetadataPollingFailure()
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }, "NovaHevMetadataPoller").apply { isDaemon = true }

        metadataThread = poller
        poller.start()
    }

    private fun stopHevMetadataPolling() {
        metadataPolling.set(false)
        val poller = metadataThread
        metadataThread = null
        poller?.interrupt()
        if (poller != null && poller !== Thread.currentThread()) {
            try {
                poller.join(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Fail closed if the selected cellular network disappears. Existing TCP/UDP
     * sockets are bound to that Network and cannot be transparently migrated;
     * stopping the VPN avoids an unannounced fallback through Wi-Fi.
     */
    private fun registerPreferredMobileNetworkMonitor(
        connectivity: ConnectivityManager,
        selectedNetwork: Network
    ) {
        connectivityManager = connectivity
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network != selectedNetwork) return
                mainHandler.post {
                    if (preferredMobileNetwork != selectedNetwork ||
                        !mobileTransportPreferenceEnabled ||
                        !tunnelEstablished
                    ) return@post

                    val message =
                        "Le réseau mobile utilisé par le tunnel a disparu. NOVA PING arrête le tunnel pour éviter un basculement silencieux vers le Wi-Fi."
                    terminalError = message
                    publishMobileTransportStatus("mobile_lost")
                    publishState("error", message)
                    stopTunnel()
                }
            }
        }
        try {
            connectivity.registerNetworkCallback(request, callback)
            mobileNetworkCallback = callback
        } catch (error: Exception) {
            publishMobileTransportStatus("monitor_failed")
            throw IllegalStateException(
                "Impossible de surveiller la disponibilité du réseau mobile. Le tunnel ne sera pas lancé sans cette protection.",
                error
            )
        }

        val capabilities = connectivity.getNetworkCapabilities(selectedNetwork)
        val stillAvailable = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!stillAvailable) {
            publishMobileTransportStatus("mobile_lost")
            throw IllegalStateException(
                "Le réseau mobile a disparu pendant le démarrage du tunnel."
            )
        }
    }

    private fun unregisterPreferredMobileNetworkMonitor() {
        val callback = mobileNetworkCallback
        mobileNetworkCallback = null
        val connectivity = connectivityManager
        connectivityManager = null
        if (callback != null && connectivity != null) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * Records only the outcome and coarse socket precondition. No endpoint or
     * socket address is retained. Android's protect() boolean is authoritative;
     * a false result is never bypassed or retried on the same socket.
     */
    private fun bindToPreferredMobileNetwork(socket: Socket): Boolean {
        if (!mobileTransportPreferenceEnabled) return true
        val network = preferredMobileNetwork ?: return false
        return try {
            network.bindSocket(socket)
            publishMobileTransportStatus("bound")
            true
        } catch (_: Exception) {
            publishMobileTransportStatus("bind_failed")
            false
        }
    }

    private fun bindToPreferredMobileNetwork(socket: DatagramSocket): Boolean {
        if (!mobileTransportPreferenceEnabled) return true
        val network = preferredMobileNetwork ?: return false
        return try {
            network.bindSocket(socket)
            publishMobileTransportStatus("bound")
            true
        } catch (_: Exception) {
            publishMobileTransportStatus("bind_failed")
            false
        }
    }

    private fun protectSocket(
        socket: Socket,
        purpose: String,
        requiresProtection: Boolean
    ): Boolean {
        val invalidPrecondition = socket.isClosed || socket.isConnected
        if (!requiresProtection) {
            // Per-app allowlisting keeps NOVA PING's upstream sockets outside the TUN.
            // When requested, explicitly bind those sockets to the selected cellular network.
            val bound = bindToPreferredMobileNetwork(socket)
            VpnDiagnostics.tracker.recordSocketProtection(
                purpose = purpose,
                outcome = if (bound) "bypassed_per_app_allowlist" else "mobile_network_bind_failed",
                invalidPrecondition = invalidPrecondition
            )
            return !invalidPrecondition && bound
        }
        val protected = evaluateSocketProtection(
            purpose = purpose,
            invalidPrecondition = invalidPrecondition,
            socketClosed = socket.isClosed,
            socketConnected = socket.isConnected,
            socketBound = socket.isBound
        ) {
            protect(socket)
        }
        return protected && bindToPreferredMobileNetwork(socket)
    }

    private fun protectSocket(
        socket: DatagramSocket,
        purpose: String,
        requiresProtection: Boolean
    ): Boolean {
        val invalidPrecondition = socket.isClosed || socket.isConnected
        if (!requiresProtection) {
            val bound = bindToPreferredMobileNetwork(socket)
            VpnDiagnostics.tracker.recordSocketProtection(
                purpose = purpose,
                outcome = if (bound) "bypassed_per_app_allowlist" else "mobile_network_bind_failed",
                invalidPrecondition = invalidPrecondition
            )
            return !invalidPrecondition && bound
        }
        val protected = evaluateSocketProtection(
            purpose = purpose,
            invalidPrecondition = invalidPrecondition,
            socketClosed = socket.isClosed,
            socketConnected = socket.isConnected,
            socketBound = socket.isBound
        ) {
            protect(socket)
        }
        return protected && bindToPreferredMobileNetwork(socket)
    }

    private inline fun evaluateSocketProtection(
        purpose: String,
        invalidPrecondition: Boolean,
        socketClosed: Boolean,
        socketConnected: Boolean,
        socketBound: Boolean,
        protectCall: () -> Boolean
    ): Boolean {
        // Capture the state at the actual call boundary. Permission alone does not
        // prove that this service still owns a live TUN descriptor.
        val authorizationPresentBefore = VpnService.prepare(this) == null
        val tunnelEstablishedAtCall = tunnelEstablished
        val tunDescriptorPresentAtCall = tunDescriptor != null
        val engineStartedAtCall = engineStarted
        val engineThreadAliveAtCall = engineThread?.isAlive == true
        val stoppingAtCall = stopping.get()

        val outcome = SocketProtectionGuard.evaluate(
            invalidPrecondition = invalidPrecondition,
            protectCall = protectCall
        )
        val authorizationPresentAfter = VpnService.prepare(this) == null
        VpnDiagnostics.tracker.recordSocketProtection(purpose, outcome, invalidPrecondition)
        VpnDiagnostics.tracker.recordSocketProtectionAuthorization(
            purpose = purpose,
            outcome = outcome,
            authorizationPresent = authorizationPresentBefore
        )
        VpnDiagnostics.tracker.recordSocketProtectionContext(
            purpose = purpose,
            outcome = outcome,
            authorizationPresentBefore = authorizationPresentBefore,
            authorizationPresentAfter = authorizationPresentAfter,
            tunnelEstablished = tunnelEstablishedAtCall,
            tunDescriptorPresent = tunDescriptorPresentAtCall,
            engineStarted = engineStartedAtCall,
            engineThreadAlive = engineThreadAliveAtCall,
            stopping = stoppingAtCall,
            socketClosed = socketClosed,
            socketConnected = socketConnected,
            socketBound = socketBound
        )
        return outcome == "protected"
    }

    private fun stopTunnel() {
        if (!tunnelEstablished && engineThread == null && bridge == null && tunDescriptor == null) return
        if (!stopping.compareAndSet(false, true)) return
        bridge?.close()
        bridge = null
        if (engineStarted || engineThread?.isAlive == true) {
            HevTunnelNative.requestStop()
        } else {
            tunDescriptor?.let { runCatching { ParcelFileDescriptor.adoptFd(it).close() } }
            tunDescriptor = null
            finishTunnel()
        }
    }

    private fun finishTunnel() {
        if (engineThread?.isAlive == true && engineStarted) return
        stopHevReadinessMonitor()
        bridge?.close()
        bridge = null
        tunDescriptor?.let { runCatching { ParcelFileDescriptor.adoptFd(it).close() } }
        tunDescriptor = null
        engineThread = null
        engineStarted = false
        tunnelEstablished = false
        unregisterPreferredMobileNetworkMonitor()
        preferredMobileNetwork = null
        mobileTransportPreferenceEnabled = false
        val transportStatus = getSharedPreferences(
            VPN_PREFERENCES, Context.MODE_PRIVATE
        ).getString(KEY_MOBILE_TRANSPORT_STATUS, null)
        if (transportStatus != "mobile_lost") {
            publishMobileTransportStatus("stopped")
        }
        runCatching { setUnderlyingNetworks(null) }
        stopping.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val error = terminalError
        val finalState = terminalState ?: if (error == null) "stopped" else "error"
        VpnDiagnostics.tracker.setTunnelState(
            when (finalState) {
                "replaced" -> "Remplacé par un autre VPN"
                "error" -> "Arrêt sur erreur"
                else -> "Arrêté"
            }
        )
        VpnDiagnostics.finish(this)
        terminalError = null
        terminalState = null
        publishState(finalState, error)
        stopSelf()
    }

    override fun onRevoke() {
        VpnDiagnostics.tracker.recordVpnRevocation()
        // Android revokes the current VpnService when another VPN takes ownership.
        // Publish this immediately: the engine may need time to unwind its native thread.
        terminalState = "replaced"
        terminalError = null
        publishState("replaced", "Un autre VPN a pris le relais. Le tunnel NOVA PING est arrêté.")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        unregisterPreferredMobileNetworkMonitor()
        preferredMobileNetwork = null
        mobileTransportPreferenceEnabled = false
        publishMobileTransportStatus("stopped")
        runCatching { setUnderlyingNetworks(null) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    private fun publishState(state: String, error: String?) {
        getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_TUNNEL_STATE, state)
            .putString(KEY_LAST_TUNNEL_ERROR, error)
            .commit()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, state)
            if (error != null) putExtra(EXTRA_ERROR, error)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Tunnel de diagnostic NOVA PING",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "État du tunnel VPN démarré par l'utilisateur"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, NovaVpnService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 4202, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NOVA PING — tunnel en démarrage")
            .setContentText("Initialisation du moteur HEV pour le jeu choisi")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Arrêter", stopPendingIntent)
            .build()
    }
}
