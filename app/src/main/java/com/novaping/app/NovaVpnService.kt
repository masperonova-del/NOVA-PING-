package com.novaping.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.net.InetAddress
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
        private const val CHANNEL_ID = "nova_ping_vpn"
        private const val NOTIFICATION_ID = 4201
        private const val VPN_PREFERENCES = "nova_ping_vpn"
        private const val KEY_SELECTED_GAME_PACKAGE = "selected_game_package"
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
    @Volatile private var engineStarted = false
    @Volatile private var engineThread: Thread? = null
    @Volatile private var bridge: LocalSocks5Bridge? = null
    @Volatile private var tunDescriptor: Int? = null
    @Volatile private var tunnelEstablished = false

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
                try {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startTunnel()
                } catch (error: Exception) {
                    publishState("error", error.message ?: "Démarrage VPN impossible")
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
        check(VpnService.prepare(this) == null) {
            "Le consentement VPN Android est requis avant le démarrage"
        }
        val gamePackage = readSelectedGamePackage(this)
            ?: throw IllegalStateException("Aucun jeu installé n'est sélectionné")

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val underlying = connectivity.activeNetwork
            ?: throw IllegalStateException("Aucun réseau Internet actif")
        val linkProperties: LinkProperties = connectivity.getLinkProperties(underlying)
            ?: throw IllegalStateException("Configuration du réseau indisponible")
        val dnsServer = linkProperties.dnsServers.firstOrNull()
            ?: throw IllegalStateException("Aucun serveur DNS numérique fourni par le réseau")

        val resolver = ProtectedDnsResolver(
            resolverAddress = dnsServer,
            protectDatagramSocket = { protect(it) },
            protectTcpSocket = { protect(it) }
        )
        val localBridge = LocalSocks5Bridge(
            protectUpstreamSocket = { protect(it) },
            bindAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            protectUpstreamDatagramSocket = { protect(it) },
            protectedDnsResolver = resolver
        )
        bridge = localBridge
        val socksPort = localBridge.start()

        val builder = Builder()
            .setSession("NOVA PING — diagnostic réseau")
            .setMtu(1500)
            .addAddress(TUN_IPV4, 32)
            .addAddress(TUN_IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addAllowedApplication(gamePackage)

        val pfd = builder.establish()
            ?: throw IllegalStateException("Android n'a pas pu établir l'interface VPN")
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
        publishState("starting", null)
        val thread = Thread({
            engineStarted = true
            try {
                val result = HevTunnelNative.runBlocking(config, fd)
                if (!stopping.get() && result != 0) {
                    publishState("error", "Le moteur HEV s'est arrêté avec le code $result")
                }
            } catch (error: Throwable) {
                if (!stopping.get()) publishState("error", error.message ?: "Échec du moteur HEV")
            } finally {
                engineStarted = false
                tunDescriptor = null
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                mainHandler.post { finishTunnel() }
            }
        }, "NovaHevEngine").apply { isDaemon = true }
        engineThread = thread
        thread.start()
    }

    private fun stopTunnel() {
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
        bridge?.close()
        bridge = null
        tunDescriptor?.let { runCatching { ParcelFileDescriptor.adoptFd(it).close() } }
        tunDescriptor = null
        engineThread = null
        engineStarted = false
        tunnelEstablished = false
        stopping.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishState("stopped", null)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    private fun publishState(state: String, error: String?) {
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
            .setContentTitle("NOVA PING — tunnel actif")
            .setContentText("Diagnostic limité à l'application de jeu choisie")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Arrêter", stopPendingIntent)
            .build()
    }
}
