package com.novaping.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.VpnService
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

private const val MAX_STABILITY_EVENTS = 15
private const val ACTION_STABILITY_HISTORY_UPDATED = "com.novaping.app.action.STABILITY_HISTORY_UPDATED"

private data class ProbeTarget(val name: String, val endpoint: String)
private data class InstalledLaunchableApp(val label: String, val packageName: String)
private data class ComparisonNetworkSnapshot(
    val transport: NetworkTransport,
    val fingerprint: String,
    val vpnActive: Boolean
)
private data class TargetResult(
    val target: ProbeTarget,
    val metrics: PingMetrics?,
    val attemptedRequests: Int,
    val roundMedians: List<Long>,
    val failures: Map<ProbeFailure, Int>,
    val cycleOutcomes: List<List<ProbeOutcome>>,
    val attemptTraces: List<List<ProbeAttemptTrace>>
)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var stabilityReceiverRegistered = false
    private var wifiScanReceiverRegistered = false
    private var defaultNetworkCallbackRegistered = false
    private val wifiLockDefaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshWifiLockFromNetworkCallback()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshWifiLockFromNetworkCallback()
        override fun onLost(network: Network) = refreshWifiLockFromNetworkCallback()
    }
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            if (wifiScanReceiverRegistered) {
                runCatching { unregisterReceiver(this) }
                wifiScanReceiverRegistered = false
            }
            displayWifiScanResults()
        }
    }
    private val stabilityHistoryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STABILITY_HISTORY_UPDATED) {
                refreshStabilityMonitorUi()
                refreshMobileDataLockUi()
            }
        }
    }
    private lateinit var status: TextView
    private lateinit var networkIndicator: NetworkStatusIndicator
    private lateinit var networkInfo: TextView
    private lateinit var networkTransportValue: TextView
    private lateinit var networkInternetValue: TextView
    private lateinit var networkMeteredValue: TextView
    private lateinit var wifiLockStatus: TextView
    private lateinit var wifiLockToggleButton: Button
    private lateinit var mobileDataLockStatus: TextView
    private lateinit var mobileDataLockTunnelStatus: TextView
    private lateinit var mobileDataLockToggleButton: Button
    private lateinit var testButton: Button
    private lateinit var gameSpinner: Spinner
    private lateinit var resultsContainer: LinearLayout
    private lateinit var historyText: TextView
    private lateinit var routeButton: Button
    private lateinit var routeResults: LinearLayout
    private lateinit var comparisonStatusView: TextView
    private lateinit var comparisonDirectButton: Button
    private lateinit var comparisonCandidateButton: Button
    private lateinit var comparisonCandidateLabelInput: EditText
    private lateinit var stabilityStatus: TextView
    private lateinit var stabilityToggleButton: Button
    private lateinit var stabilityResults: LinearLayout
    private var stabilityHasEvents = false
    private var pendingCsvExport: String? = null
    private var pendingTunnelReportExport: String? = null
    private val exportRequestCode = 9104
    private val tunnelReportExportRequestCode = 9106
    private val vpnConsentRequestCode = 9105
    private lateinit var vpnGameSpinner: Spinner
    private lateinit var vpnConsentStatus: TextView
    private lateinit var vpnConsentButton: Button
    private lateinit var endpointObservationOptInCheckBox: CheckBox
    private lateinit var endpointObservationResults: TextView
    private lateinit var endpointObservationRefreshButton: Button
    private var installedGameApps: List<InstalledLaunchableApp> = emptyList()
    private var selectedVpnGamePackage: String? = null
    private var vpnTunnelStartRequested = false
    private var vpnStateReceiverRegistered = false
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NovaVpnService.ACTION_STATE -> {
                    val state = intent.getStringExtra(NovaVpnService.EXTRA_STATE) ?: return
                    applyVpnUiState(state, intent.getStringExtra(NovaVpnService.EXTRA_ERROR))
                    refreshMobileDataLockUi()
                    if (state == "ready" && ::endpointObservationOptInCheckBox.isInitialized &&
                        endpointObservationOptInCheckBox.isChecked
                    ) requestEndpointObservations()
                }
                NovaVpnService.ACTION_ENDPOINTS -> renderEndpointObservations(intent)
            }
        }
    }

    private fun applyVpnUiState(state: String, error: String?) {
        if (::endpointObservationOptInCheckBox.isInitialized) {
            endpointObservationOptInCheckBox.isEnabled = state !in setOf("starting", "ready")
        }
        when (state) {
            "starting" -> {
                vpnTunnelStartRequested = true
                vpnConsentButton.text = "ARRÊTER LE TUNNEL"
                vpnConsentStatus.text = "Interface Android établie ; démarrage du moteur HEV."
                vpnConsentStatus.setTextColor(Color.rgb(100, 210, 150))
            }
            "ready" -> {
                vpnTunnelStartRequested = true
                vpnConsentButton.text = "ARRÊTER LE TUNNEL"
                vpnConsentStatus.text = "Moteur HEV initialisé ; tunnel actif pour le jeu sélectionné."
                vpnConsentStatus.setTextColor(Color.rgb(100, 210, 150))
            }
            "stopped" -> {
                vpnTunnelStartRequested = false
                vpnConsentButton.text = "DÉMARRER LE TUNNEL VPN"
                vpnConsentStatus.text = "Tunnel arrêté. Aucun trafic n'est redirigé."
                vpnConsentStatus.setTextColor(mutedColor)
            }
            "replaced", "other_vpn" -> {
                vpnTunnelStartRequested = false
                vpnConsentButton.text = "DÉMARRER LE TUNNEL VPN"
                vpnConsentStatus.text = if (state == "replaced") {
                    "Un autre VPN est actif. Le tunnel NOVA PING a été arrêté."
                } else {
                    "Un autre VPN est détecté. NOVA PING n'est pas actif."
                }
                vpnConsentStatus.setTextColor(Color.rgb(255, 190, 110))
            }
            "error" -> {
                vpnTunnelStartRequested = false
                vpnConsentButton.text = "RÉESSAYER LE TUNNEL VPN"
                vpnConsentStatus.text = "Échec du tunnel : ${error ?: "erreur inconnue"}"
                vpnConsentStatus.setTextColor(Color.rgb(255, 150, 130))
            }
        }
        if (::endpointObservationRefreshButton.isInitialized) {
            endpointObservationRefreshButton.isEnabled =
                state == "ready" && endpointObservationOptInCheckBox.isChecked
            if (state in setOf("stopped", "error", "replaced", "other_vpn")) {
                endpointObservationResults.text =
                    "Aucune observation conservée. Le tunnel est arrêté."
            }
        }
    }

    private fun hasActiveVpnTransport(): Boolean {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun refreshVpnUiState() {
        val (savedState, savedError) = NovaVpnService.readLastTunnelState(this)
        val vpnActive = hasActiveVpnTransport()
        val state = VpnUiStateResolver.resolve(savedState, vpnActive)
        applyVpnUiState(state, if (state == savedState) savedError else null)
    }

    private val backgroundColor = Color.rgb(10, 15, 27)
    private val panelColor = Color.rgb(21, 29, 47)
    private val accentColor = Color.rgb(64, 160, 255)
    private val mutedColor = Color.rgb(160, 174, 195)
    private val preferences by lazy { getSharedPreferences("nova_ping_history", Context.MODE_PRIVATE) }
    private val wifiLockPreferences by lazy { getSharedPreferences("nova_wifi_lock", Context.MODE_PRIVATE) }
    private val mobileDataLockPreferences by lazy { getSharedPreferences("nova_mobile_data_lock", Context.MODE_PRIVATE) }
    private val routeComparisonRepository by lazy {
        RouteComparisonRepository(getSharedPreferences("nova_route_comparison", Context.MODE_PRIVATE))
    }

    private val targets = listOf(
        ProbeTarget("Google Connectivity", "https://connectivitycheck.gstatic.com/generate_204"),
        ProbeTarget("Cloudflare Connectivity", "https://cp.cloudflare.com/generate_204"),
        ProbeTarget("Microsoft Connectivity", "https://www.msftconnecttest.com/connecttest.txt")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = 0

        val scroll = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(NovaBrandMark(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)))
        root.addView(label("NOVA PING", 30f, Color.WHITE, true, Gravity.CENTER))
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "inconnue"
        root.addView(label("VERSION $installedVersion", 10f, mutedColor, true, Gravity.START))
        root.addView(label("COMPRENDRE. MESURER. OPTIMISER.", 11f, accentColor, true, Gravity.START).apply {
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(sectionTitle("TABLEAU DE BORD"))
        val networkCard = card()
        networkCard.addView(label("ÉTAT DU RÉSEAU", 12f, mutedColor, true, Gravity.START))
        val networkStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        networkIndicator = NetworkStatusIndicator(this)
        networkStatusRow.addView(networkIndicator, LinearLayout.LayoutParams(dp(42), dp(42)))
        status = label("Analyse de la connexion…", 18f, Color.WHITE, true, Gravity.START).apply {
            setPadding(dp(10), 0, 0, 0)
        }
        networkStatusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        networkCard.addView(networkStatusRow)

        val networkMetricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        val (transportTile, transportValue) = dashboardMetric("TRANSPORT", "—")
        val (internetTile, internetValue) = dashboardMetric("INTERNET", "—")
        val (meteredTile, meteredValue) = dashboardMetric("RÉSEAU FACTURÉ", "—")
        networkTransportValue = transportValue
        networkInternetValue = internetValue
        networkMeteredValue = meteredValue
        listOf(transportTile, internetTile, meteredTile).forEachIndexed { index, tile ->
            val tileParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index < 2) tileParams.marginEnd = dp(6)
            networkMetricsRow.addView(tile, tileParams)
        }
        networkCard.addView(networkMetricsRow)

        networkInfo = label("", 12f, mutedColor, false, Gravity.START).apply {
            setPadding(0, dp(2), 0, 0)
        }
        networkCard.addView(networkInfo)
        root.addView(networkCard)
        refreshNetworkInfo()

        root.addView(sectionTitle("WI-FI LOCK — DEUX MODES"))
        val wifiLockCard = card()
        wifiLockCard.addView(label(
            "Choisir un réseau Wi-Fi préféré",
            16f, Color.WHITE, true, Gravity.START
        ))
        wifiLockCard.addView(label(
            "NOVA PING mémorise le réseau choisi et vérifie le réseau Wi-Fi réellement connecté. Si le réseau diffère, Android ouvre ses paramètres pour que tu confirmes la connexion. Android garde le contrôle final : aucun changement silencieux ni mot de passe récupéré.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(10)) })
        wifiLockStatus = label(
            "Aucun réseau prioritaire enregistré.",
            12f, accentColor, true, Gravity.START
        ).apply { setPadding(0, dp(10), 0, dp(8)) }
        wifiLockCard.addView(wifiLockStatus)
        wifiLockCard.addView(Button(this).apply {
            text = "DÉTECTER LES RÉSEAUX WI-FI"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        9202
                    )
                } else {
                    scanWifiNetworks()
                }
            }
        })
        wifiLockCard.addView(Button(this).apply {
            text = "CHOISIR LE MODE DE VERROUILLAGE"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                val modes = arrayOf("Verrouillage manuel", "Verrouillage intelligent")
                val current = wifiLockPreferences.getString("mode", "manual")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Mode Wi-Fi Lock")
                    .setSingleChoiceItems(modes, if (current == "smart") 1 else 0) { dialog, which ->
                        dialog.dismiss()
                        if (which == 1) {
                            wifiLockPreferences.edit().putString("mode", "manual").apply()
                            Toast.makeText(
                                this@MainActivity,
                                "Le verrouillage intelligent n'est pas encore implémenté. Le mode manuel reste sélectionné.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            wifiLockPreferences.edit().putString("mode", "manual").apply()
                        }
                        refreshWifiLockUi()
                    }
                    .setNegativeButton("ANNULER", null)
                    .show()
            }
        })
        wifiLockCard.addView(Button(this).apply {
            text = "OUVRIR LES PARAMÈTRES WI-FI"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { openWifiSettings() }
        })
        wifiLockToggleButton = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                val enabled = wifiLockPreferences.getBoolean("enabled", false)
                val ssid = wifiLockPreferences.getString("preferred_ssid", null)
                if (enabled) {
                    wifiLockPreferences.edit().putBoolean("enabled", false).apply()
                    refreshWifiLockUi()
                } else if (ssid.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Sélectionne d'abord un réseau Wi-Fi.", Toast.LENGTH_SHORT).show()
                } else {
                    wifiLockPreferences.edit().putBoolean("enabled", true).apply()
                    refreshWifiLockUi()
                    val connected = currentConnectedWifiSsid()
                    if (connected != ssid) {
                        Toast.makeText(
                            this@MainActivity,
                            "Le réseau préféré n'est pas connecté. Sélectionne-le dans les paramètres Android.",
                            Toast.LENGTH_LONG
                        ).show()
                        openWifiSettings()
                    }
                }
            }
        }
        wifiLockCard.addView(wifiLockToggleButton)
        wifiLockCard.addView(Button(this).apply {
            text = "VÉRIFIER LE RÉSEAU ACTUEL"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                refreshWifiLockUi()
                Toast.makeText(
                    this@MainActivity,
                    "État de la connexion Wi-Fi actualisé.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
        refreshWifiLockUi()
        root.addView(wifiLockCard)

        root.addView(sectionTitle("MOBILE DATA LOCK"))
        val mobileDataLockCard = card()
        mobileDataLockCard.addView(label(
            "Préférer le réseau mobile pour le tunnel du jeu",
            16f, Color.WHITE, true, Gravity.START
        ))
        mobileDataLockCard.addView(label(
            "Surveille le réseau réellement utilisé par Android. Lorsque cette préférence est activée avant le démarrage du tunnel VPN NOVA PING, le trafic du jeu sélectionné est lié au réseau mobile disponible. Le réseau par défaut des autres applications reste contrôlé par Android ; aucune activation silencieuse des données mobiles ni bascule globale n'est effectuée.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(10)) })
        mobileDataLockStatus = label(
            "Réseau Android : en attente de détection.",
            12f, accentColor, true, Gravity.START
        ).apply { setPadding(0, dp(8), 0, dp(4)) }
        mobileDataLockCard.addView(mobileDataLockStatus)
        mobileDataLockTunnelStatus = label(
            "Transport du tunnel : non confirmé.",
            12f, mutedColor, true, Gravity.START
        ).apply { setPadding(0, dp(4), 0, dp(8)) }
        mobileDataLockCard.addView(mobileDataLockTunnelStatus)
        mobileDataLockToggleButton = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                val enabled = mobileDataLockPreferences.getBoolean("enabled", false)
                val nextEnabled = !enabled
                mobileDataLockPreferences.edit().putBoolean("enabled", nextEnabled).apply()
                if (nextEnabled) {
                    refreshMobileDataLockUi()
                    requestNotificationPermissionAndStartMobileDataLockMonitor()
                } else {
                    if (!preferences.getBoolean("stability_monitor_requested", false)) {
                        stopNetworkMonitorService()
                    } else {
                        startNetworkMonitorService()
                    }
                    refreshMobileDataLockUi()
                    refreshStabilityMonitorUi()
                }
            }
        }
        mobileDataLockCard.addView(mobileDataLockToggleButton)
        mobileDataLockCard.addView(Button(this).apply {
            text = "OUVRIR LES PARAMÈTRES INTERNET"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { openMobileNetworkSettings() }
        })
        refreshMobileDataLockUi()
        root.addView(mobileDataLockCard)

        root.addView(sectionTitle("SURVEILLANCE RÉSEAU"))
        val stabilityCard = card()
        stabilityCard.addView(label("Surveillance des coupures", 16f, Color.WHITE, true, Gravity.START))
        stabilityCard.addView(label(
            "Surveille le Wi-Fi et le réseau actif même lorsque tu quittes l'application. Un service visible enregistre les transitions réseau et ajoute des relevés périodiques (cible : environ 60 secondes ; Android peut les retarder lorsque le téléphone dort). Tu peux l'arrêter à tout moment.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(8)) })
        stabilityStatus = label("Surveillance arrêtée", 13f, accentColor, true, Gravity.START)
        stabilityCard.addView(stabilityStatus)
        stabilityToggleButton = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                if (preferences.getBoolean("stability_monitor_requested", false)) {
                    stopNetworkMonitor()
                } else {
                    requestNotificationPermissionAndStartMonitor()
                }
            }
        }
        stabilityCard.addView(stabilityToggleButton)
        refreshStabilityMonitorUi()
        stabilityResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        stabilityCard.addView(stabilityResults)
        renderStabilityHistory()
        root.addView(stabilityCard)

        root.addView(sectionTitle("GAME INTELLIGENCE — SERVEURS DE JEU"))
        val gameCard = card()
        gameCard.addView(label("Sélectionne ton jeu", 14f, Color.WHITE, true, Gravity.START))
        gameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                GameCatalog.profiles.map { it.displayName }
            )
        }
        gameCard.addView(gameSpinner)
        val gameEvidenceStatus = label("", 13f, Color.WHITE, true, Gravity.START).apply {
            setPadding(0, dp(12), 0, dp(4))
        }
        gameCard.addView(gameEvidenceStatus)
        val gameEvidenceDetails = label("", 12f, mutedColor, false, Gravity.START)
        gameCard.addView(gameEvidenceDetails)
        val gameNetworkReferenceStatus = label("", 12f, accentColor, true, Gravity.START).apply {
            setPadding(0, dp(12), 0, dp(4))
        }
        gameCard.addView(gameNetworkReferenceStatus)
        val gameNetworkReferenceDetails = label("", 12f, mutedColor, false, Gravity.START)
        gameCard.addView(gameNetworkReferenceDetails)
        // No endpoint is seeded until its source and game-server role are independently verified.
        val gameEvidenceRecords = emptyList<GameServerEvidence>()
        fun refreshGameEvidenceStatus(position: Int) {
            val profile = GameCatalog.profiles.getOrNull(position) ?: GameCatalog.profiles.last()
            val usable = GameEvidenceRegistry.usableForGame(
                gameEvidenceRecords, profile.id, System.currentTimeMillis()
            )
            gameEvidenceStatus.text = if (usable.isEmpty()) {
                "AUCUN SERVEUR DE MATCH VÉRIFIÉ"
            } else {
                "${usable.size} preuve(s) serveur actuellement valide(s)"
            }
            gameEvidenceStatus.setTextColor(
                if (usable.isEmpty()) Color.rgb(255, 190, 110)
                else Color.rgb(100, 210, 150)
            )
            gameEvidenceDetails.text = if (usable.isEmpty()) {
                "Jeu : ${profile.displayName}\nRégions et adresses : non confirmées\nAucune IP inventée. Les points Google/Cloudflare/Microsoft restent des contrôles Internet, pas des serveurs de match."
            } else {
                usable.joinToString("\n") { evidence ->
                    "${evidence.region} • ${evidence.endpointHost} • ${evidence.protocol}"
                }
            }
        }
        fun refreshGameNetworkReference(position: Int) {
            val profile = GameCatalog.profiles.getOrNull(position) ?: GameCatalog.profiles.last()
            val references = GameNetworkReferenceRegistry.forGame(profile.id)
            gameNetworkReferenceStatus.text = if (references.isEmpty()) {
                "AUCUNE RÉFÉRENCE DE PORT PUBLIÉE POUR CE JEU"
            } else {
                "RÉFÉRENCE OFFICIELLE DE CONNECTIVITÉ DISPONIBLE — AUCUNE IP DE MATCH CONFIRMÉE"
            }
            gameNetworkReferenceDetails.text = references.joinToString("\n\n") { reference ->
                val portLines = reference.ports.joinToString("\n") { port ->
                    "${port.protocol} ${port.displayRange()} — ${port.purpose}"
                }
                "${reference.sourceTitle} (${reference.platform})\n" +
                    "Consultée le ${reference.checkedOn}\n" +
                    portLines + "\nSource : ${reference.sourceUrl}\n" +
                    "Cette documentation décrit des ports, pas des adresses IP. Elle ne prouve pas qu'un hôte est un serveur de partie et ne fournit pas un ping CODM."
            }
        }
        gameSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                refreshGameEvidenceStatus(position)
                refreshGameNetworkReference(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        refreshGameEvidenceStatus(gameSpinner.selectedItemPosition)
        refreshGameNetworkReference(gameSpinner.selectedItemPosition)
        gameCard.addView(label(
            "Une destination ne sera affichée qu'après vérification de sa source, de son rôle de serveur de match et de sa disponibilité de mesure.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(gameCard)

        root.addView(sectionTitle("COMPARAISON DE DESTINATIONS"))
        val testCard = card()
        testCard.addView(label("Diagnostic multi-points", 16f, Color.WHITE, true, Gravity.START))
        testCard.addView(label(
            "Vérification préalable de l’accès Internet, puis 5 cycles indépendants avec 5 mesures par destination (25 mesures). Les échecs sont classés lorsque possible. Le test est plus long qu'avant, surtout si une destination ne répond pas. Les résultats restent des temps de réponse HTTPS, pas le ping du jeu.",
            13f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(14)) })
        testButton = Button(this).apply {
            text = "LANCER LES 5 CYCLES"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { NovaMotion.pulse(it); runNetworkTest() }
        }
        testCard.addView(testButton)
        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        resultsContainer.addView(label("Aucune comparaison effectuée.", 14f, Color.WHITE, false, Gravity.START))
        testCard.addView(resultsContainer)
        root.addView(testCard)

        root.addView(sectionTitle("ROUTE ENGINE — ANALYSE DES PHASES"))
        val routeCard = card()
        routeCard.addView(label("Décomposition d'une connexion HTTPS", 16f, Color.WHITE, true, Gravity.START))
        routeCard.addView(label(
            "Effectue 5 essais indépendants par destination et résume séparément DNS, TCP, TLS et le délai jusqu'à la ligne de statut HTTP. Avec 5 essais, le P95 de phase reste le maximum observé. Ce test ne trace pas les routeurs intermédiaires et ne mesure pas le serveur de match.",
            13f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(14)) })
        routeButton = Button(this).apply {
            text = "ANALYSER LES PHASES"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { NovaMotion.pulse(it); runRouteAnalysis() }
        }
        routeCard.addView(routeButton)
        routeResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        routeResults.addView(label("Aucune analyse de phase effectuée.", 13f, mutedColor, false, Gravity.START))
        routeCard.addView(routeResults)

        routeCard.addView(label(
            "COMPARAISON DIRECT / CANDIDATE",
            14f, accentColor, true, Gravity.START
        ).apply { setPadding(0, dp(18), 0, dp(6)) })
        routeCard.addView(label(
            "Effectue trois paires dans l'ordre : DIRECT, puis CANDIDATE, puis recommence. Pour CANDIDATE, utilise un VPN déjà configuré, distinct du tunnel NOVA PING actuel, et garde le même nom de profil/serveur. Désactive-le avant la capture DIRECT suivante. Les mesures restent des contrôles HTTPS.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(2), 0, dp(8)) })
        comparisonStatusView = label("", 12f, Color.WHITE, true, Gravity.START)
            .apply { setPadding(0, dp(4), 0, dp(8)) }
        routeCard.addView(comparisonStatusView)
        comparisonCandidateLabelInput = EditText(this).apply {
            hint = "Nom du profil / serveur candidat"
            setTextColor(Color.WHITE)
            setHintTextColor(mutedColor)
            textSize = 13f
            setSingleLine(true)
        }
        routeCard.addView(comparisonCandidateLabelInput)
        comparisonDirectButton = Button(this).apply {
            text = "CAPTURER DIRECT"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                NovaMotion.pulse(it)
                captureRouteComparison(RouteComparisonPath.DIRECT)
            }
        }
        routeCard.addView(comparisonDirectButton)
        comparisonCandidateButton = Button(this).apply {
            text = "CAPTURER CANDIDATE (VPN)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                NovaMotion.pulse(it)
                captureRouteComparison(RouteComparisonPath.CANDIDATE)
            }
        }
        routeCard.addView(comparisonCandidateButton)
        routeCard.addView(Button(this).apply {
            text = "COMPARER LES SÉRIES"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { compareRouteComparison() }
        })
        routeCard.addView(Button(this).apply {
            text = "RÉINITIALISER L'EXPÉRIENCE"
            isAllCaps = false
            setTextColor(mutedColor)
            background = actionButtonBackground()
            setOnClickListener { confirmResetRouteComparison() }
        })
        refreshRouteComparisonStatus()
        root.addView(routeCard)

        root.addView(sectionTitle("TUNNEL VPN — APPLICATION DE JEU"))
        val vpnConsentCard = card()
        vpnConsentCard.addView(label(
            "Choisir une application de jeu installée",
            15f, Color.WHITE, true, Gravity.START
        ))
        vpnConsentCard.addView(label(
            "Après ton accord, NOVA PING démarre un tunnel limité au jeu choisi. Android affiche sa demande officielle ; une notification permanente permet de l'arrêter.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(10)) })
        installedGameApps = loadInstalledLaunchableApps()
        vpnGameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                installedGameApps.map { it.label }
            )
            isEnabled = installedGameApps.isNotEmpty()
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long
                ) {
                    selectedVpnGamePackage = installedGameApps.getOrNull(position)?.packageName
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    selectedVpnGamePackage = null
                }
            }
        }
        vpnConsentCard.addView(vpnGameSpinner)
        vpnConsentCard.addView(label(
            "OBSERVATION DES ENDPOINTS — OPTIONNELLE",
            11f, accentColor, true, Gravity.START
        ).apply { setPadding(0, dp(12), 0, dp(4)) })
        endpointObservationOptInCheckBox = CheckBox(this).apply {
            text = "Autoriser NOVA PING à observer les adresses IP et ports distants du jeu pendant cette session"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = false
            isEnabled = installedGameApps.isNotEmpty()
        }
        vpnConsentCard.addView(endpointObservationOptInCheckBox)
        vpnConsentCard.addView(label(
            "Désactivée par défaut. Cette option est indépendante de l’autorisation VPN Android. Les observations restent en mémoire pendant le tunnel et sont effacées à son arrêt ; aucun contenu de paquet n’est lu ni conservé.",
            11f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(2), 0, dp(8)) })
        vpnConsentStatus = label(
            if (installedGameApps.isEmpty()) "Aucune application de jeu lançable détectée."
            else "Aucun tunnel actif. Choisis un jeu puis démarre le diagnostic.",
            12f, accentColor, false, Gravity.START
        ).apply { setPadding(0, dp(8), 0, dp(8)) }
        vpnConsentCard.addView(vpnConsentStatus)
        endpointObservationResults = label(
            "Aucun candidat chargé. Active l'option avant de démarrer le tunnel.",
            12f, mutedColor, false, Gravity.START
        ).apply {
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
        }
        vpnConsentCard.addView(endpointObservationResults)
        endpointObservationRefreshButton = Button(this).apply {
            text = "ACTUALISER LES ENDPOINTS OBSERVÉS"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            isEnabled = false
            setOnClickListener { requestEndpointObservations() }
        }
        vpnConsentCard.addView(endpointObservationRefreshButton)
        vpnConsentButton = Button(this).apply {
            text = "DÉMARRER LE TUNNEL VPN"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            isEnabled = installedGameApps.isNotEmpty()
            setOnClickListener { if (vpnTunnelStartRequested) stopGameTunnel() else requestVpnConsent() }
        }
        vpnConsentCard.addView(vpnConsentButton)
        root.addView(vpnConsentCard)

        root.addView(sectionTitle("DIAGNOSTIC INTERNE DU TUNNEL"))
        val vpnDiagnosticCard = card()
        vpnDiagnosticCard.addView(label(
            "Rapport technique — DNS / TCP / UDP / HEV",
            15f, Color.WHITE, true, Gravity.START
        ))
        vpnDiagnosticCard.addView(label(
            "Actualise ce rapport après un essai avec le jeu. DNS désigne la résolution de noms du pont SOCKS5 ; les éventuels DNS directs sont inclus dans les totaux UDP. Ce sont des compteurs de transport, pas un ping de serveur de match. Aucun domaine, aucune adresse IP distante et aucun contenu de paquet n'est conservé.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(10)) })
        val vpnDiagnosticReport = label(
            VpnDiagnostics.readReport(this),
            12f, Color.WHITE, false, Gravity.START
        ).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(10))
        }
        vpnDiagnosticCard.addView(vpnDiagnosticReport)
        vpnDiagnosticCard.addView(Button(this).apply {
            text = "ACTUALISER LE RAPPORT"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                vpnDiagnosticReport.text = VpnDiagnostics.readReport(this@MainActivity)
            }
        })
        vpnDiagnosticCard.addView(Button(this).apply {
            text = "EXPORTER LE RAPPORT DU TUNNEL"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener {
                pendingTunnelReportExport = VpnDiagnostics.readReport(this@MainActivity)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "NOVA-PING-rapport-tunnel.txt")
                }
                try {
                    startActivityForResult(intent, tunnelReportExportRequestCode)
                } catch (_: Exception) {
                    pendingTunnelReportExport = null
                    Toast.makeText(this@MainActivity, "Impossible d'ouvrir le sélecteur de fichiers.", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(vpnDiagnosticCard)

        root.addView(sectionTitle("MOTEUR DE TUNNEL"))
        val tunnelCard = card()
        tunnelCard.addView(label(
            "Moteur HEV natif",
            15f, Color.WHITE, true, Gravity.START
        ))
        val tunnelEngineStatus = label(
            "Vérification du moteur HEV…",
            12f, accentColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, 0) }
        tunnelCard.addView(tunnelEngineStatus)
        tunnelCard.addView(label(
            "Le moteur HEV utilise le relais SOCKS5 local authentifié. Le tunnel ne démarre qu'après consentement Android et action explicite.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(tunnelCard)
        executor.execute {
            val engineResult = runCatching {
                HevTunnelNative.isAvailable()
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    tunnelEngineStatus.text = engineResult.fold(
                        onSuccess = { if (it) "Bibliothèque HEV chargée" else "Bibliothèque HEV indisponible" },
                        onFailure = { "Moteur non chargé : ${it.message ?: "erreur inconnue"}" }
                    )
                    tunnelEngineStatus.setTextColor(
                        if (engineResult.getOrNull() == true) Color.rgb(100, 210, 150)
                        else Color.rgb(255, 150, 130)
                    )
                }
            }
        }

        root.addView(sectionTitle("HISTORIQUE LOCAL"))
        val historyCard = card()
        historyCard.addView(label("10 dernières comparaisons", 15f, Color.WHITE, true, Gravity.START))
        historyText = label("", 12f, mutedColor, false, Gravity.START).apply {
            setPadding(0, dp(8), 0, 0)
        }
        historyCard.addView(historyText)
        historyCard.addView(Button(this).apply {
            text = "COMPARER LES 2 DERNIÈRES SESSIONS COMPARABLES"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { compareRecentSessions() }
        }.apply { setPadding(0, dp(8), 0, 0) })
        root.addView(historyCard)
        renderHistory()

        root.addView(sectionTitle("DÉTAILS DES MESURES"))
        val measurementDetailCard = card()
        measurementDetailCard.addView(label(
            "Consulte les tentatives individuelles de la dernière comparaison et les cinq essais de chaque phase DNS, TCP, TLS et HTTP.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, 0, 0, dp(10)) })
        measurementDetailCard.addView(Button(this).apply {
            text = "VOIR LES MESURES HTTPS"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { showLatestMeasurementDetails() }
        })
        measurementDetailCard.addView(Button(this).apply {
            text = "VOIR LES PHASES DNS / TCP / TLS / HTTP"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { showLatestRouteDetails() }
        })
        measurementDetailCard.addView(Button(this).apply {
            text = "EXPORTER LES DONNÉES CSV"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = actionButtonBackground()
            setOnClickListener { exportLatestMeasurements() }
        })
        root.addView(measurementDetailCard)

        root.addView(sectionTitle("FEUILLE DE ROUTE"))
        val roadmapCard = card()
        listOf(
            "01  Diagnostic réseau : contexte Wi-Fi / mobile et état Internet",
            "02  Game Intelligence : profils de jeux et statut des preuves serveur",
            "03  Route Engine : phases DNS, TCP, TLS et réponse HTTP",
            "04  Decision Engine : recommandations explicables et niveau de confiance",
            "05  NOVA BOOST : recherche future, uniquement après validation de gains réels"
        ).forEach { item ->
            roadmapCard.addView(label(item, 13f, Color.WHITE, false, Gravity.START).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        root.addView(roadmapCard)

        root.addView(label(
            "TRANSPARENCE : les mesures HTTPS incluent le temps de réponse du service et ne sont pas le ping d'un serveur de jeu. L'historique est conservé uniquement sur cet appareil. Cette version ne modifie pas la connexion.",
            11f, mutedColor, false, Gravity.CENTER
        ).apply { setPadding(dp(4), dp(18), dp(4), 0) })

        scroll.addView(root)
        setContentView(scroll)
        NovaMotion.animateEntrance(root)
    }

    private fun transportName(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "Aucun"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Autre"
    }

    private fun stabilityEventColor(type: NetworkStabilityEventType, details: String): Int =
        when (type) {
            NetworkStabilityEventType.WIFI_DISCONNECTED,
            NetworkStabilityEventType.INTERNET_LOST -> Color.rgb(255, 115, 105)
            NetworkStabilityEventType.INTERNET_RESTORED -> Color.rgb(100, 210, 150)
            NetworkStabilityEventType.WIFI_CONNECTED,
            NetworkStabilityEventType.MOBILE_DATA_CONNECTED,
            NetworkStabilityEventType.DEFAULT_NETWORK_CHANGED -> accentColor
            NetworkStabilityEventType.MOBILE_DATA_LOCK_WIFI_FALLBACK -> Color.rgb(255, 185, 90)
            NetworkStabilityEventType.MOBILE_DATA_LOCK_MOBILE_RESTORED -> Color.rgb(100, 210, 150)
            NetworkStabilityEventType.MOBILE_DATA_DISCONNECTED -> Color.rgb(255, 115, 105)
            NetworkStabilityEventType.MOBILE_INTERNET_STATUS ->
                if (details.contains("validé", ignoreCase = true) &&
                    !details.contains("non validé", ignoreCase = true)) Color.rgb(100, 210, 150)
                else Color.rgb(255, 185, 90)
            NetworkStabilityEventType.INTERNET_STATUS ->
                if (details.contains("validé", ignoreCase = true) && !details.contains("non validé", ignoreCase = true)) Color.rgb(100, 210, 150)
                else Color.rgb(255, 185, 90)
            NetworkStabilityEventType.WIFI_STATUS ->
                if (details.contains("aucun réseau", ignoreCase = true) ||
                    details.contains("indisponible", ignoreCase = true)) Color.rgb(255, 115, 105)
                else accentColor
            NetworkStabilityEventType.PERIODIC_STATUS -> when {
                details.contains("aucun réseau", ignoreCase = true) -> Color.rgb(255, 115, 105)
                details.contains("Internet validé", ignoreCase = true) -> Color.rgb(100, 210, 150)
                details.contains("non validé", ignoreCase = true) -> Color.rgb(255, 185, 90)
                else -> accentColor
            }
        }

    private fun renderStabilityHistory() {
        if (!::stabilityResults.isInitialized) return
        val events = try {
            JSONArray(preferences.getString("stability_events", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        stabilityResults.removeAllViews()
        stabilityHasEvents = events.length() > 0
        if (!stabilityHasEvents) {
            stabilityResults.addView(label(
                "Aucun événement observé pour le moment.",
                12f, mutedColor, false, Gravity.START
            ))
            return
        }
        val start = maxOf(0, events.length() - MAX_STABILITY_EVENTS)
        for (i in start until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            val type = runCatching {
                NetworkStabilityEventType.valueOf(item.optString("type"))
            }.getOrNull() ?: continue
            val timestamp = item.optLong("timestamp", 0L)
            val details = item.optString("details", "Événement réseau")
            val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
                .format(Date(timestamp))
            stabilityResults.addView(label(
                "$time — $details",
                12f, stabilityEventColor(type, details), false, Gravity.START
            ).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
    }

    private fun refreshStabilityMonitorUi() {
        if (!::stabilityStatus.isInitialized || !::stabilityToggleButton.isInitialized) return
        val running = preferences.getBoolean("stability_monitor_running", false)
        val stabilityRequested = preferences.getBoolean("stability_monitor_requested", false)
        val mobileLockEnabled = mobileDataLockPreferences.getBoolean("enabled", false)
        stabilityStatus.text = when {
            running && stabilityRequested ->
                "Surveillance active en arrière-plan · relevés périodiques susceptibles d’être retardés en veille"
            running && mobileLockEnabled ->
                "Surveillance active en arrière-plan pour Mobile Data Lock"
            running -> "Surveillance réseau active en arrière-plan"
            else -> preferences.getString("stability_monitor_error", null)
                ?: "Surveillance arrêtée"
        }
        stabilityToggleButton.text = when {
            stabilityRequested -> "ARRÊTER LA SURVEILLANCE"
            running && mobileLockEnabled -> "ACTIVER AUSSI L’HISTORIQUE DES COUPURES"
            else -> "DÉMARRER LA SURVEILLANCE"
        }
        renderStabilityHistory()
    }

    private fun requestNotificationPermissionAndStartMonitor() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9201)
            return
        }
        startNetworkMonitor()
    }

    private fun requestNotificationPermissionAndStartMobileDataLockMonitor() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9203)
            return
        }
        startNetworkMonitorService()
    }

    private fun startNetworkMonitor() {
        preferences.edit().putBoolean("stability_monitor_requested", true).apply()
        startNetworkMonitorService()
    }

    private fun startNetworkMonitorService() {
        preferences.edit().remove("stability_monitor_error").apply()
        val intent = Intent(this, NetworkMonitorService::class.java)
            .setAction(NetworkMonitorService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            stabilityStatus.text = "Démarrage de la surveillance…"
        } catch (error: Exception) {
            stabilityStatus.text = "Impossible de démarrer : ${error.message ?: "erreur Android"}"
        }
    }

    private fun stopNetworkMonitor() {
        preferences.edit().putBoolean("stability_monitor_requested", false).apply()
        if (mobileDataLockPreferences.getBoolean("enabled", false)) {
            stabilityStatus.text = "Surveillance maintenue : Mobile Data Lock en dépend."
            refreshStabilityMonitorUi()
            return
        }
        stopNetworkMonitorService()
    }

    private fun stopNetworkMonitorService() {
        val intent = Intent(this, NetworkMonitorService::class.java)
            .setAction(NetworkMonitorService.ACTION_STOP)
        try {
            startService(intent)
            stabilityStatus.text = "Arrêt de la surveillance…"
        } catch (error: Exception) {
            stabilityStatus.text = "Impossible d'arrêter : ${error.message ?: "erreur Android"}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9201) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startNetworkMonitor()
            } else {
                stabilityStatus.text = "Autorisation de notification refusée : surveillance non démarrée"
            }
        }
        if (requestCode == 9203) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startNetworkMonitorService()
            } else {
                mobileDataLockPreferences.edit().putBoolean("enabled", false).apply()
                refreshMobileDataLockUi()
                Toast.makeText(
                    this,
                    "Autorisation de notification refusée : surveillance en arrière-plan non démarrée.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (requestCode == 9202) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                scanWifiNetworks()
            } else {
                Toast.makeText(this, "Autorisation de localisation refusée : détection Wi-Fi impossible.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerWifiLockNetworkCallback()
        refreshStabilityMonitorUi()
    }

    private fun registerWifiLockNetworkCallback() {
        if (defaultNetworkCallbackRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            manager.registerDefaultNetworkCallback(wifiLockDefaultNetworkCallback)
            defaultNetworkCallbackRegistered = true
        } catch (_: Exception) {
            // The screen still refreshes on resume if Android refuses callback registration.
        }
    }

    private fun unregisterWifiLockNetworkCallback() {
        if (!defaultNetworkCallbackRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { manager.unregisterNetworkCallback(wifiLockDefaultNetworkCallback) }
        defaultNetworkCallbackRegistered = false
    }

    private fun refreshWifiLockFromNetworkCallback() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (::wifiLockStatus.isInitialized) refreshWifiLockUi()
            if (::mobileDataLockStatus.isInitialized) refreshMobileDataLockUi()
            if (::status.isInitialized) refreshNetworkInfo()
        }
    }

    override fun onStop() {
        unregisterWifiLockNetworkCallback()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshNetworkInfo()
        if (::wifiLockStatus.isInitialized) refreshWifiLockUi()
        if (::mobileDataLockStatus.isInitialized) refreshMobileDataLockUi()
        if (!stabilityReceiverRegistered) {
            val filter = IntentFilter(ACTION_STABILITY_HISTORY_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stabilityHistoryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                registerReceiver(stabilityHistoryReceiver, filter)
            }
            stabilityReceiverRegistered = true
        }
        refreshStabilityMonitorUi()
        if (!vpnStateReceiverRegistered) {
            val vpnFilter = IntentFilter(NovaVpnService.ACTION_STATE).apply {
                addAction(NovaVpnService.ACTION_ENDPOINTS)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(vpnStateReceiver, vpnFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                registerReceiver(vpnStateReceiver, vpnFilter)
            }
            vpnStateReceiverRegistered = true
        }
        // Broadcasts are not sticky: reconcile persisted service state whenever the
        // activity returns, including after another VPN has replaced NOVA PING.
        refreshVpnUiState()
        if (vpnTunnelStartRequested && ::endpointObservationOptInCheckBox.isInitialized &&
            endpointObservationOptInCheckBox.isChecked
        ) requestEndpointObservations()
    }

    override fun onPause() {
        if (vpnStateReceiverRegistered) {
            unregisterReceiver(vpnStateReceiver)
            vpnStateReceiverRegistered = false
        }
        if (stabilityReceiverRegistered) {
            unregisterReceiver(stabilityHistoryReceiver)
            stabilityReceiverRegistered = false
        }
        super.onPause()
    }

    private fun refreshMobileDataLockUi() {
        if (!::mobileDataLockStatus.isInitialized ||
            !::mobileDataLockToggleButton.isInitialized
        ) return

        val enabled = mobileDataLockPreferences.getBoolean("enabled", false)
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val activeCapabilities = active?.let { manager.getNetworkCapabilities(it) }
        val activeTransport = when {
            activeCapabilities == null -> "Aucun"
            activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
            activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Autre"
        }
        val mobileCapabilities = manager.allNetworks.mapNotNull { network ->
            manager.getNetworkCapabilities(network)
                ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
        }
        val mobileAvailable = mobileCapabilities.isNotEmpty()
        val mobileInternetValidated = when {
            mobileCapabilities.any {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } -> true
            mobileCapabilities.isEmpty() -> null
            mobileCapabilities.all {
                !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } -> false
            else -> null
        }
        val snapshot = MobileDataLockResolver.resolve(
            enabled = enabled,
            activeTransport = activeTransport,
            activeInternetValidated = activeCapabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ),
            mobileNetworkAvailable = mobileAvailable,
            mobileInternetValidated = mobileInternetValidated
        )
        val monitorRunning = preferences.getBoolean("stability_monitor_running", false)
        val monitorError = preferences.getString("stability_monitor_error", null)
        mobileDataLockStatus.text = buildString {
            append("Réseau Android : ")
            append(snapshot.details)
            if (enabled) {
                append(if (monitorRunning) "\nSurveillance en arrière-plan active." else "\nSurveillance en arrière-plan inactive.")
                if (!monitorRunning && !monitorError.isNullOrBlank()) {
                    append("\n")
                    append(monitorError)
                }
            }
        }
        mobileDataLockStatus.setTextColor(
            when (snapshot.state) {
                MobileDataLockState.MOBILE_ACTIVE_VALIDATED -> Color.rgb(100, 210, 150)
                MobileDataLockState.DISABLED -> mutedColor
                MobileDataLockState.WIFI_ACTIVE_MOBILE_AVAILABLE,
                MobileDataLockState.WIFI_ACTIVE_MOBILE_UNAVAILABLE,
                MobileDataLockState.MOBILE_ACTIVE_UNVALIDATED,
                MobileDataLockState.VPN_ACTIVE -> Color.rgb(255, 185, 90)
                MobileDataLockState.OTHER_NETWORK_ACTIVE,
                MobileDataLockState.NO_NETWORK -> Color.rgb(255, 150, 130)
            }
        )
        val tunnelTransportStatus = getSharedPreferences(
            NovaVpnService.VPN_PREFERENCES, Context.MODE_PRIVATE
        ).getString(NovaVpnService.KEY_MOBILE_TRANSPORT_STATUS, "disabled")
        mobileDataLockTunnelStatus.text = when (tunnelTransportStatus) {
            "selecting" -> "Transport du tunnel : recherche d'un réseau mobile Internet."
            "selected" -> "Transport du tunnel : réseau mobile sélectionné ; liaison des sockets en attente."
            "bound" -> "Transport du tunnel : au moins une socket amont liée au réseau mobile. Cela ne prouve pas le transport de chaque paquet du jeu."
            "bind_failed" -> "Transport du tunnel : échec de liaison au réseau mobile. Le verrouillage n'est pas confirmé."
            "selection_failed" -> "Transport du tunnel : Android n'a pas accepté la configuration du réseau mobile."
            "start_failed" -> "Transport du tunnel : échec du démarrage après sélection du réseau mobile."
            "unavailable" -> "Transport du tunnel : réseau mobile indisponible au démarrage."
            "mobile_lost" -> "Transport du tunnel : réseau mobile perdu ; tunnel arrêté pour empêcher le basculement vers le Wi-Fi."
            "monitor_failed" -> "Transport du tunnel : surveillance du réseau mobile impossible ; tunnel non lancé."
            else -> "Transport du tunnel : inactif ou non confirmé."
        }
        mobileDataLockTunnelStatus.setTextColor(
            when (tunnelTransportStatus) {
                "bound" -> Color.rgb(100, 210, 150)
                "selected" -> Color.rgb(255, 185, 90)
                "bind_failed", "unavailable", "selection_failed", "start_failed", "mobile_lost", "monitor_failed" -> Color.rgb(255, 150, 130)
                else -> mutedColor
            }
        )
        mobileDataLockToggleButton.text = if (enabled) {
            "DÉSACTIVER MOBILE DATA LOCK"
        } else {
            "ACTIVER MOBILE DATA LOCK"
        }
    }

    private fun openMobileNetworkSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Impossible d'ouvrir les paramètres réseau Android.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun currentNetworkObservation(): NetworkObservation {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> NetworkTransport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }
        return NetworkObservation(
            networkId = network?.toString(),
            transport = transport,
            internetAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
    }

    private fun currentNetworkContext(): NetworkContext {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> NetworkTransport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }
        return NetworkContext.from(
            transport = transport,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = network != null && caps != null && manager.isActiveNetworkMetered,
            roaming = if (transport == NetworkTransport.CELLULAR &&
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == true) false else null,
            downstreamKbps = caps?.linkDownstreamBandwidthKbps,
            upstreamKbps = caps?.linkUpstreamBandwidthKbps
        )
    }

    private fun runRouteAnalysis(
        comparisonPath: RouteComparisonPath? = null,
        comparisonSnapshot: ComparisonNetworkSnapshot? = null,
        comparisonCandidateLabel: String? = null
    ) {
        routeButton.isEnabled = false
        comparisonDirectButton.isEnabled = false
        comparisonCandidateButton.isEnabled = false
        val sessionStartNetwork = currentNetworkObservation()
        val context = currentNetworkContext()
        if (!sessionStartNetwork.isReady || !context.internetValidated) {
            routeButton.isEnabled = true
            refreshRouteComparisonStatus()
            routeResults.removeAllViews()
            routeResults.addView(label(
                "Android ne confirme pas l'accès à Internet. Connecte-toi puis relance l'analyse.",
                13f, Color.rgb(255, 170, 120), false, Gravity.START
            ))
            return
        }

        routeResults.removeAllViews()
        routeResults.addView(label("5 essais indépendants par destination — analyse DNS, TCP, TLS et HTTP…", 13f, mutedColor, false, Gravity.START))
        val sessionStartedAtEpochMs = System.currentTimeMillis()
        executor.execute {
            val samplesByTarget = targets.associateWith { mutableListOf<RouteProbeOutcome>() }
            var interrupted: String? = null
            var networkIssue: NetworkSessionIssue? = null
            try {
                routeLoop@ for (cycleIndex in 0 until MeasurementPlan.ROUTE_SAMPLES_PER_TARGET) {
                    val offset = cycleIndex % targets.size
                    val cycleOrder = targets.drop(offset) + targets.take(offset)
                    for ((targetIndex, target) in cycleOrder.withIndex()) {
                        runOnUiThread {
                            routeResults.removeAllViews()
                            routeResults.addView(label(
                                "Essai ${cycleIndex + 1}/${MeasurementPlan.ROUTE_SAMPLES_PER_TARGET} — ${target.name}",
                                13f, mutedColor, false, Gravity.START
                            ))
                        }
                        networkIssue = sessionStartNetwork.issueComparedTo(currentNetworkObservation())
                        if (networkIssue != null) break@routeLoop

                        samplesByTarget.getValue(target).add(RouteProbe.measure(target.endpoint))

                        networkIssue = sessionStartNetwork.issueComparedTo(currentNetworkObservation())
                        if (networkIssue != null) break@routeLoop
                        if (targetIndex < cycleOrder.lastIndex) Thread.sleep(200)
                    }
                }
            } catch (error: Exception) {
                interrupted = error.message ?: "Erreur inconnue pendant l'analyse"
            }

            val sessionEndedAtEpochMs = System.currentTimeMillis()
            val sessionEndNetwork = currentNetworkObservation()
            if (networkIssue == null) {
                networkIssue = sessionStartNetwork.issueComparedTo(sessionEndNetwork)
            }
            saveRouteDetails(
                samplesByTarget,
                sessionStartedAtEpochMs,
                sessionEndedAtEpochMs,
                sessionStartNetwork,
                sessionEndNetwork,
                networkIssue
            )
            val aggregates = targets.mapNotNull { target ->
                samplesByTarget.getValue(target).takeIf { it.isNotEmpty() }
                    ?.let { RouteStatistics.aggregate(it) }
            }
            var comparisonCaptureMessage: String? = null
            if (comparisonPath != null && comparisonSnapshot != null) {
                val endSnapshot = currentComparisonNetworkSnapshot()
                val ownVpnState = NovaVpnService.readLastTunnelState(this).first
                val isCandidate = comparisonPath == RouteComparisonPath.CANDIDATE
                val ownNovaTunnelActive = isCandidate && ownVpnState in setOf("starting", "ready")
                val stablePhysicalNetwork = endSnapshot != null &&
                    endSnapshot.fingerprint == comparisonSnapshot.fingerprint &&
                    endSnapshot.transport == comparisonSnapshot.transport &&
                    endSnapshot.vpnActive == comparisonSnapshot.vpnActive
                if (interrupted == null && networkIssue == null &&
                    stablePhysicalNetwork && !ownNovaTunnelActive
                ) {
                    val batch = RouteComparisonBatch(
                        label = comparisonPath.name,
                        transport = comparisonSnapshot.transport,
                        vpnLabel = if (isCandidate) comparisonCandidateLabel else null,
                        aggregates = aggregates,
                        experimentId = routeComparisonRepository.experimentId(),
                        batchId = UUID.randomUUID().toString(),
                        networkFingerprint = comparisonSnapshot.fingerprint,
                        startedAtEpochMs = sessionStartedAtEpochMs,
                        completedAtEpochMs = sessionEndedAtEpochMs
                    )
                    if (RouteComparisonEngine.isBatchEligible(batch)) {
                        routeComparisonRepository.save(comparisonPath, batch)
                        comparisonCaptureMessage = "Lot ${comparisonPath.name} enregistré : ${routeComparisonRepository.batches(comparisonPath).size}/${MeasurementPlan.ROUTE_COMPARISON_REQUIRED_PAIRS}."
                    } else {
                        comparisonCaptureMessage = "Capture refusée : une ou plusieurs destinations n'ont pas assez de mesures complètes pour une comparaison fiable."
                    }
                } else {
                    comparisonCaptureMessage = when {
                        ownNovaTunnelActive ->
                            "Capture refusée : le tunnel NOVA PING actuel ne constitue pas encore un relais distant."
                        interrupted != null -> "Capture refusée : analyse interrompue."
                        networkIssue != null -> "Capture refusée : ${networkIssue.userMessage}"
                        else -> "Capture refusée : réseau physique ambigu ou modifié pendant la mesure."
                    }
                }
            }

            val recommendations = if (networkIssue == null) {
                DecisionEngine.recommend(context, aggregates)
            } else {
                listOf(NetworkRecommendation(
                    "Analyse non classée",
                    networkIssue.userMessage,
                    "Rétablir une connexion stable puis relancer l’analyse complète.",
                    RecommendationConfidence.LOW
                ))
            }

            runOnUiThread {
                routeButton.isEnabled = true
                comparisonDirectButton.isEnabled = true
                comparisonCandidateButton.isEnabled = true
                refreshRouteComparisonStatus()
                routeResults.removeAllViews()
                comparisonCaptureMessage?.let { message ->
                    routeResults.addView(label(
                        message, 12f,
                        if (message.startsWith("Lot ")) Color.rgb(100, 210, 150) else Color.rgb(255, 185, 90),
                        true, Gravity.START
                    ).apply { setPadding(0, dp(6), 0, dp(6)) })
                }
                if (interrupted != null) {
                    routeResults.addView(label(
                        "Analyse interrompue : $interrupted. Les résultats partiels ne permettent pas une conclusion complète.",
                        12f, Color.rgb(255, 150, 130), false, Gravity.START
                    ))
                }
                if (networkIssue != null) {
                    routeResults.addView(label(
                        networkIssue.userMessage + " Les mesures partielles sont conservées sans classement.",
                        12f, Color.rgb(255, 185, 90), false, Gravity.START
                    ))
                }
                aggregates.forEach { aggregate ->
                    val detail = card()
                    detail.addView(label(
                        aggregate.host, 14f, Color.WHITE, true, Gravity.START
                    ))
                    fun phaseLine(name: String, phase: PhaseStatistics): String {
                        val median = phase.medianMs?.let { "${it} ms" } ?: "indisponible"
                        val p95 = phase.p95ObservedMs?.let { "${it} ms" } ?: "indisponible"
                        val variation = phase.meanAbsoluteVariationMs?.let { "${it} ms" } ?: "n/c"
                        return "$name — médiane : $median | P95 observé : $p95 | variation : $variation | mesures : ${phase.successfulSamples}/${phase.attemptedSamples}"
                    }
                    val details = listOf(
                        phaseLine("DNS", aggregate.dns),
                        phaseLine("TCP — adresse connectée", aggregate.tcp),
                        phaseLine("TCP — total des tentatives d’adresses", aggregate.tcpAttemptTotal),
                        phaseLine("TLS", aggregate.tls),
                        phaseLine("Ligne de statut HTTP", aggregate.httpStatusLine),
                        "Échanges HTTPS complets : ${aggregate.completeSamples}/${aggregate.sampleCount}",
                        "Réponses HTTP réussies (2xx) : ${aggregate.httpSuccessfulSamples}/${aggregate.sampleCount}",
                        "Codes HTTP : " + if (aggregate.httpStatuses.isEmpty()) "aucun" else
                            aggregate.httpStatuses.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} : ${it.value}" },
                        "Échecs par phase : " + if (aggregate.failures.isEmpty()) "aucun" else
                            aggregate.failures.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} : ${it.value}" }
                    ).joinToString("\n")
                    detail.addView(label(details, 12f, mutedColor, false, Gravity.START)
                        .apply {
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            setLineSpacing(dp(3).toFloat(), 1f)
                            background = resultDetailBackground()
                        })
                    routeResults.addView(detail)
                }

                val boostAssessment = if (networkIssue == null) {
                    NovaBoostEngine.assess(
                        context = context,
                        aggregates = aggregates,
                        gameProfile = GameCatalog.findByDisplayName(gameSpinner.selectedItem?.toString() ?: "Autre jeu")
                    )
                } else null

                routeResults.addView(label(
                    "NOVA BOOST — ÉVALUATION", 13f, accentColor, true, Gravity.START
                ).apply { setPadding(0, dp(12), 0, dp(6)) })

                if (boostAssessment != null) {
                    val boostCard = card()
                    boostCard.addView(label(
                        "ÉTAT : ${boostAssessment.mode}",
                        14f, Color.WHITE, true, Gravity.START
                    ))
                    boostCard.addView(label(
                        boostAssessment.summary,
                        12f, mutedColor, false, Gravity.START
                    ).apply { setPadding(0, dp(6), 0, dp(6)) })
                    boostCard.addView(label(
                        boostAssessment.evidenceSummary,
                        11f, accentColor, true, Gravity.START
                    ).apply { setPadding(0, dp(2), 0, dp(6)) })
                    boostCard.addView(label(
                        "Jeu : ${boostAssessment.gameProfile.displayName}\nRoutage automatique : non disponible\nVPN démarré : non",
                        11f, accentColor, false, Gravity.START
                    ).apply { setPadding(0, dp(4), 0, dp(4)) })
                    boostAssessment.limitations.forEach { limitation ->
                        boostCard.addView(label(
                            "• $limitation", 11f, mutedColor, false, Gravity.START
                        ).apply { setPadding(0, dp(3), 0, dp(3)) })
                    }
                    routeResults.addView(boostCard)
                }

                routeResults.addView(label(
                    "RECOMMANDATIONS FONDÉES SUR LES MESURES", 13f, accentColor, true, Gravity.START
                ).apply { setPadding(0, dp(12), 0, dp(6)) })
                (boostAssessment?.recommendations ?: recommendations).forEach { recommendation ->
                    val item = card()
                    item.addView(label(recommendation.title, 14f, Color.WHITE, true, Gravity.START))
                    item.addView(label(recommendation.explanation, 12f, mutedColor, false, Gravity.START)
                        .apply { setPadding(0, dp(5), 0, 0) })
                    item.addView(label(
                        "Action : ${recommendation.action}\nConfiance : ${recommendation.confidence}",
                        12f, accentColor, false, Gravity.START
                    ).apply { setPadding(0, dp(5), 0, 0) })
                    routeResults.addView(item)
                }
                routeResults.addView(label(
                    "Statistiques calculées séparément sur les réponses disponibles. Avec 5 essais par phase, le P95 observé est le maximum de cet échantillon et ne constitue pas une garantie. Les points de contrôle HTTPS ne représentent pas les serveurs de jeu.",
                    11f, mutedColor, false, Gravity.START
                ).apply { setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    private fun captureRouteComparison(path: RouteComparisonPath) {
        val snapshot = currentComparisonNetworkSnapshot()
        if (snapshot == null) {
            showMeasurementDialog(
                "Réseau non comparable",
                "Android ne permet pas d'identifier un seul réseau physique validé sous la connexion actuelle. NOVA PING refuse d'enregistrer une comparaison incertaine."
            )
            return
        }
        if (path == RouteComparisonPath.DIRECT && snapshot.vpnActive) {
            showMeasurementDialog(
                "Connexion DIRECT requise",
                "Désactive le VPN candidat avant de capturer la série DIRECT."
            )
            return
        }
        if (path == RouteComparisonPath.CANDIDATE && !snapshot.vpnActive) {
            showMeasurementDialog(
                "Connexion candidate requise",
                "Active le chemin candidat déjà configuré, puis relance la capture. Aucun VPN n'est démarré par cette fonction."
            )
            return
        }
        val candidateLabel = if (path == RouteComparisonPath.CANDIDATE) {
            comparisonCandidateLabelInput.text.toString().trim()
        } else null
        if (path == RouteComparisonPath.CANDIDATE && candidateLabel.isNullOrBlank()) {
            showMeasurementDialog(
                "Nom du candidat requis",
                "Saisis un nom stable pour ce profil ou serveur candidat. Le même nom devra être conservé pour les trois captures."
            )
            return
        }
        if (path == RouteComparisonPath.CANDIDATE &&
            NovaVpnService.readLastTunnelState(this).first in setOf("starting", "ready")
        ) {
            showMeasurementDialog(
                "Relais NOVA indisponible",
                "Le tunnel NOVA PING actuellement intégré n'est pas encore un relais distant configuré. Il ne peut donc pas être évalué comme une route candidate."
            )
            return
        }
        runRouteAnalysis(path, snapshot, candidateLabel)
    }

    private fun currentComparisonNetworkSnapshot(): ComparisonNetworkSnapshot? {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return null
        val activeCapabilities = manager.getNetworkCapabilities(active) ?: return null
        val vpnActive = activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        fun physicalTransport(capabilities: NetworkCapabilities): NetworkTransport? = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> null
        }

        val selected = if (!vpnActive) {
            val transport = physicalTransport(activeCapabilities) ?: return null
            if (!activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) return null
            active to transport
        } else {
            val physicalNetworks = manager.allNetworks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) return@mapNotNull null
                val transport = physicalTransport(capabilities) ?: return@mapNotNull null
                network to transport
            }.distinctBy { it.first.networkHandle }
            if (physicalNetworks.size != 1) return null
            physicalNetworks.single()
        }

        val (physicalNetwork, transport) = selected
        return ComparisonNetworkSnapshot(
            transport = transport,
            fingerprint = "${transport.name}:${physicalNetwork.networkHandle}",
            vpnActive = vpnActive
        )
    }

    private fun refreshRouteComparisonStatus() {
        if (!::comparisonStatusView.isInitialized) return
        val direct = routeComparisonRepository.batches(RouteComparisonPath.DIRECT).size
        val candidate = routeComparisonRepository.batches(RouteComparisonPath.CANDIDATE).size
        comparisonStatusView.text =
            "EXPÉRIENCE ${routeComparisonRepository.experimentId().take(8)}\nDIRECT : $direct/3   •   CANDIDATE : $candidate/3"
        comparisonDirectButton.isEnabled = direct < MeasurementPlan.ROUTE_COMPARISON_REQUIRED_PAIRS
        comparisonCandidateButton.isEnabled = candidate < MeasurementPlan.ROUTE_COMPARISON_REQUIRED_PAIRS
    }

    private fun compareRouteComparison() {
        val result = RouteComparisonEngine.compare(
            routeComparisonRepository.batches(RouteComparisonPath.DIRECT),
            routeComparisonRepository.batches(RouteComparisonPath.CANDIDATE)
        )
        val details = buildString {
            append(result.summary)
            append("\n\nPaires valides : ${result.validPairs}/${MeasurementPlan.ROUTE_COMPARISON_REQUIRED_PAIRS}")
            append("\nVerdict : ${result.verdict}")
            result.limitations.forEach { append("\n• ").append(it) }
        }
        showMeasurementDialog("Comparaison DIRECT / CANDIDATE", details)
    }

    private fun confirmResetRouteComparison() {
        AlertDialog.Builder(this)
            .setTitle("Réinitialiser l'expérience ?")
            .setMessage("Les lots DIRECT et CANDIDATE de cette expérience seront effacés de l'historique local.")
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("RÉINITIALISER") { _, _ ->
                routeComparisonRepository.reset()
                refreshRouteComparisonStatus()
                Toast.makeText(this, "Expérience réinitialisée.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun currentNetworkPreflight(): NetworkReadiness {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        return NetworkReadiness.from(
            hasNetwork = network != null && caps != null,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
    }

    private fun scanWifiNetworks() {
        val manager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            if (!manager.isWifiEnabled) {
                Toast.makeText(this, "Active d'abord le Wi-Fi dans Android.", Toast.LENGTH_LONG).show()
                openWifiSettings()
                return
            }
            if (!wifiScanReceiverRegistered) {
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    @SuppressLint("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(wifiScanReceiver, filter)
                }
                wifiScanReceiverRegistered = true
            }
            val scanStarted = manager.startScan()
            if (!scanStarted) {
                if (wifiScanReceiverRegistered) {
                    runCatching { unregisterReceiver(wifiScanReceiver) }
                    wifiScanReceiverRegistered = false
                }
                displayWifiScanResults()
                Toast.makeText(this, "Android limite les scans. Les derniers résultats disponibles sont affichés.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Recherche des réseaux Wi-Fi…", Toast.LENGTH_SHORT).show()
            }
        } catch (error: SecurityException) {
            if (wifiScanReceiverRegistered) {
                runCatching { unregisterReceiver(wifiScanReceiver) }
                wifiScanReceiverRegistered = false
            }
            Toast.makeText(
                this,
                "Autorisation Wi-Fi ou localisation manquante. Vérifie aussi que la localisation Android est activée.",
                Toast.LENGTH_LONG
            ).show()
        } catch (error: Exception) {
            if (wifiScanReceiverRegistered) {
                runCatching { unregisterReceiver(wifiScanReceiver) }
                wifiScanReceiverRegistered = false
            }
            Toast.makeText(this, "Scan Wi-Fi indisponible : ${error.message ?: "erreur Android"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayWifiScanResults() {
        val manager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            val visible = manager.scanResults
                .filter { it.SSID.isNotBlank() && it.SSID != "<unknown ssid>" }
                .distinctBy { it.SSID }
                .sortedByDescending { it.level }
            if (visible.isEmpty()) {
                Toast.makeText(this, "Aucun réseau détecté. Vérifie la localisation Android puis réessaie.", Toast.LENGTH_LONG).show()
                return
            }
            val labels = visible.map { "${it.SSID}  ·  ${it.level} dBm" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Choisir le réseau prioritaire")
                .setItems(labels) { _, index ->
                    val selected = visible[index]
                    wifiLockPreferences.edit()
                        .putString("preferred_ssid", selected.SSID)
                        .putString("preferred_bssid", selected.BSSID)
                        .putBoolean("enabled", true)
                        .putString("mode", "manual")
                        .apply()
                    refreshWifiLockUi()
                    val connected = currentConnectedWifiSsid()
                    if (connected != selected.SSID) {
                        Toast.makeText(
                            this,
                            "Préférence enregistrée. Choisis ${selected.SSID} dans les paramètres Android pour établir la connexion.",
                            Toast.LENGTH_LONG
                        ).show()
                        openWifiSettings()
                    } else {
                        Toast.makeText(this, "${selected.SSID} est déjà connecté. Préférence vérifiée.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("ANNULER", null)
                .show()
        } catch (error: SecurityException) {
            Toast.makeText(this, "Autorisation Wi-Fi ou localisation manquante.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Impossible d'ouvrir les paramètres Wi-Fi : ${error.message ?: "erreur Android"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Reads the SSID of the Wi-Fi network Android currently reports as connected.
     * This is an observation only; it does not select or switch networks.
     */
    @Suppress("DEPRECATION")
    private fun currentConnectedWifiSsid(): String? {
        fun normalize(value: String?): String? {
            if (value.isNullOrBlank() || value == "<unknown ssid>") return null
            return value.removeSurrounding("\"").takeIf {
                it.isNotBlank() && it != "<unknown ssid>"
            }
        }

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = buildList {
            connectivity.activeNetwork?.let { add(it) }
            addAll(connectivity.allNetworks)
        }.distinct()
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wifiInfo = capabilities.transportInfo as? WifiInfo
                normalize(wifiInfo?.ssid)?.let { return it }
            }
        }
        return runCatching {
            val manager = getSystemService(Context.WIFI_SERVICE) as WifiManager
            normalize(manager.connectionInfo?.ssid)
        }.getOrNull()
    }

    private fun refreshWifiLockUi() {
        if (!::wifiLockStatus.isInitialized || !::wifiLockToggleButton.isInitialized) return
        val ssid = wifiLockPreferences.getString("preferred_ssid", null)
        val enabled = wifiLockPreferences.getBoolean("enabled", false)
        val mode = wifiLockPreferences.getString("mode", "manual") ?: "manual"
        val connectedSsid = currentConnectedWifiSsid()
        val matches = !ssid.isNullOrBlank() && ssid == connectedSsid
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeCapabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val activeTransport = transportName(activeCapabilities)
        val internetState = when {
            activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> "validé"
            activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true -> "non validé"
            else -> "indisponible"
        }
        val activeNetworkIsWifi =
            activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val preferredWifiIsUsable = enabled && matches && activeNetworkIsWifi &&
            activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val networkObservation = "\nRéseau utilisé par Android : $activeTransport · Internet : $internetState"
        wifiLockStatus.text = when {
            ssid.isNullOrBlank() ->
                "Aucun réseau préféré enregistré.\nConnecté : ${connectedSsid ?: "Wi-Fi non identifié"}"
            mode == "smart" ->
                "Réseau préféré : $ssid\nConnecté : ${connectedSsid ?: "Wi-Fi non identifié"}\nLe mode intelligent n'est pas encore implémenté."
            preferredWifiIsUsable ->
                "WI-FI PRÉFÉRÉ UTILISÉ — INTERNET VALIDÉ\nRéseau : $ssid\nAndroid confirme cette connexion comme réseau actif."
            enabled && matches && !activeNetworkIsWifi ->
                "WI-FI PRÉFÉRÉ CONNECTÉ, MAIS PAS UTILISÉ PAR ANDROID\nWi-Fi : $ssid\nRéseau actif : $activeTransport. Android garde le contrôle du routage."
            enabled && matches ->
                "WI-FI PRÉFÉRÉ CONNECTÉ, INTERNET NON VALIDÉ\nRéseau : $ssid\nAndroid n'a pas confirmé l'accès Internet sur le Wi-Fi."
            enabled ->
                "PRÉFÉRENCE ACTIVÉE — MAIS NON CONNECTÉE\nPréféré : $ssid\nConnecté : ${connectedSsid ?: "Wi-Fi non identifié"}\nOuvre les paramètres Android et connecte-toi au réseau préféré."
            else ->
                "Réseau préféré : $ssid\nConnecté : ${connectedSsid ?: "Wi-Fi non identifié"}\nPréférence enregistrée, mais désactivée."
        } + networkObservation
        wifiLockStatus.setTextColor(
            if (preferredWifiIsUsable) Color.rgb(100, 210, 150)
            else if (enabled) Color.rgb(255, 185, 90)
            else accentColor
        )
        wifiLockToggleButton.text = if (enabled) {
            "DÉSACTIVER LA PRÉFÉRENCE"
        } else {
            "ACTIVER LA PRÉFÉRENCE"
        }
    }

    private fun refreshNetworkInfo(): String {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "Aucune connexion"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Autre réseau"
        }
        val hasNetwork = network != null && caps != null
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val metered = hasNetwork && manager.isActiveNetworkMetered
        networkInfo.text = "DÉTAILS TECHNIQUES\n" + currentNetworkContext().summary() +
            "\nLa bande passante est une estimation fournie par Android, pas une mesure de débit NOVA." +
            "\nDiagnostic : ${currentNetworkPreflight().userMessage}"
        if (::status.isInitialized) {
            val stateColor = when {
                !hasNetwork -> Color.rgb(255, 115, 105)
                validated -> Color.rgb(100, 210, 150)
                transport == "Wi-Fi" -> accentColor
                else -> Color.rgb(255, 185, 90)
            }
            status.text = when {
                !hasNetwork -> "Aucun réseau détecté"
                validated -> "Internet confirmé par Android"
                hasInternet -> "Réseau connecté — Internet à vérifier"
                else -> "Connexion à vérifier"
            }
            status.setTextColor(stateColor)
            if (::networkIndicator.isInitialized) networkIndicator.setState(stateColor)
            if (::networkTransportValue.isInitialized) {
                networkTransportValue.text = transport
                networkTransportValue.setTextColor(Color.WHITE)
            }
            if (::networkInternetValue.isInitialized) {
                networkInternetValue.text = when {
                    !hasNetwork -> "HORS LIGNE"
                    validated -> "VALIDÉ"
                    hasInternet -> "À VÉRIFIER"
                    else -> "INDISPONIBLE"
                }
                networkInternetValue.setTextColor(stateColor)
            }
            if (::networkMeteredValue.isInitialized) {
                networkMeteredValue.text = if (!hasNetwork) "—" else if (metered) "OUI" else "NON"
                networkMeteredValue.setTextColor(
                    if (!hasNetwork) mutedColor
                    else if (metered) Color.rgb(255, 185, 90)
                    else Color.rgb(100, 210, 150)
                )
            }
        }
        return transport
    }

    private fun runNetworkTest() {
        testButton.isEnabled = false
        val selectedGame = gameSpinner.selectedItem?.toString() ?: "Autre jeu"
        refreshNetworkInfo()
        val sessionStartNetwork = currentNetworkObservation()
        val networkType = sessionStartNetwork.displayName()
        val preflight = NetworkReadiness.from(
            hasNetwork = !sessionStartNetwork.networkId.isNullOrBlank(),
            hasInternet = sessionStartNetwork.internetAvailable,
            validated = sessionStartNetwork.validated
        )
        if (preflight != NetworkReadiness.READY) {
            testButton.isEnabled = true
            status.text = "Connexion non prête"
            resultsContainer.removeAllViews()
            resultsContainer.addView(label(
                preflight.userMessage,
                13f, Color.rgb(255, 170, 120), false, Gravity.START
            ))
            resultsContainer.addView(label(
                "Aucune mesure n’a été lancée et aucune session n’a été enregistrée. Vérifie la connexion, puis relance le diagnostic.",
                12f, mutedColor, false, Gravity.START
            ).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        status.text = "Comparaison en cours…"
        status.setTextColor(accentColor)
        resultsContainer.removeAllViews()
        resultsContainer.addView(label("Préparation du diagnostic…", 13f, mutedColor, false, Gravity.START))

        val sessionStartedAtEpochMs = System.currentTimeMillis()
        executor.execute {
            var failure: String? = null
            var networkIssue: NetworkSessionIssue? = null
            val samplesByTarget = targets.associateWith { mutableListOf<Long>() }
            val orderedAttemptsByTarget = targets.associateWith { mutableListOf<Long?>() }
            val roundMediansByTarget = targets.associateWith { mutableListOf<Long>() }
            val cycleOutcomesByTarget = targets.associateWith { mutableListOf<List<ProbeOutcome>>() }
            val attemptTracesByTarget = targets.associateWith { mutableListOf<List<ProbeAttemptTrace>>() }
            val failuresByTarget = targets.associateWith { mutableMapOf<ProbeFailure, Int>() }
            val attemptsByTarget = targets.associateWith { 0 }.toMutableMap()
            var completedCycles = 0
            try {
                cycleLoop@ for (roundIndex in 0 until MeasurementPlan.HTTPS_CYCLES) {
                    val offset = roundIndex % targets.size
                    val roundOrder = targets.drop(offset) + targets.take(offset)
                    for (target in roundOrder) {
                        runOnUiThread {
                            status.text = "Cycle ${roundIndex + 1}/${MeasurementPlan.HTTPS_CYCLES}"
                            resultsContainer.removeAllViews()
                            resultsContainer.addView(label(
                                "Cycle ${roundIndex + 1}/${MeasurementPlan.HTTPS_CYCLES} — ${target.name}",
                                13f, mutedColor, false, Gravity.START
                            ))
                        }

                        if (roundIndex == 0 && networkIssue == null) {
                            val beforeWarmup = currentNetworkObservation()
                            networkIssue = sessionStartNetwork.issueComparedTo(beforeWarmup)
                            if (networkIssue != null) break@cycleLoop
                            HttpsProbe.measureOnce(target.endpoint)
                            val afterWarmup = currentNetworkObservation()
                            networkIssue = sessionStartNetwork.issueComparedTo(afterWarmup)
                            if (networkIssue != null) break@cycleLoop
                        }

                        val roundSamples = mutableListOf<Long>()
                        val roundOutcomes = mutableListOf<ProbeOutcome>()
                        val roundTraces = mutableListOf<ProbeAttemptTrace>()
                        for (attempt in 0 until MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE) {
                            runOnUiThread {
                                resultsContainer.removeAllViews()
                                resultsContainer.addView(label(
                                    "Cycle ${roundIndex + 1}/${MeasurementPlan.HTTPS_CYCLES} — ${target.name} — mesure ${attempt + 1}/${MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE}",
                                    13f, mutedColor, false, Gravity.START
                                ))
                            }

                            val networkBefore = currentNetworkObservation()
                            networkIssue = sessionStartNetwork.issueComparedTo(networkBefore)
                            if (networkIssue != null) break

                            val attemptStartedAt = System.currentTimeMillis()
                            val probe = HttpsProbe.measureOnce(target.endpoint)
                            val attemptCompletedAt = System.currentTimeMillis()
                            val networkAfter = currentNetworkObservation()
                            val trace = ProbeAttemptTrace(
                                startedAtEpochMs = attemptStartedAt,
                                completedAtEpochMs = attemptCompletedAt,
                                networkBefore = networkBefore,
                                networkAfter = networkAfter
                            )
                            roundTraces.add(trace)
                            attemptsByTarget[target] = attemptsByTarget.getValue(target) + 1
                            roundOutcomes.add(probe)
                            val failures = failuresByTarget.getValue(target)
                            when {
                                !trace.networkStable -> {
                                    orderedAttemptsByTarget.getValue(target).add(null)
                                    failures[ProbeFailure.NETWORK_CHANGED] =
                                        (failures[ProbeFailure.NETWORK_CHANGED] ?: 0) + 1
                                }
                                probe.elapsedMs != null -> {
                                    orderedAttemptsByTarget.getValue(target).add(probe.elapsedMs)
                                    roundSamples.add(probe.elapsedMs)
                                    probe.failure?.let { category ->
                                        failures[category] = (failures[category] ?: 0) + 1
                                    }
                                }
                                else -> {
                                    orderedAttemptsByTarget.getValue(target).add(null)
                                    val category = probe.failure ?: ProbeFailure.UNKNOWN
                                    failures[category] = (failures[category] ?: 0) + 1
                                }
                            }

                            networkIssue = sessionStartNetwork.issueComparedTo(networkAfter)
                            if (networkIssue != null) break
                            if (attempt < MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE - 1) Thread.sleep(150)
                        }

                        if (roundOutcomes.isNotEmpty()) {
                            samplesByTarget.getValue(target).addAll(roundSamples)
                            cycleOutcomesByTarget.getValue(target).add(roundOutcomes.toList())
                            attemptTracesByTarget.getValue(target).add(roundTraces.toList())
                            if (roundSamples.isNotEmpty()) {
                                roundMediansByTarget.getValue(target).add(
                                    PingMetrics.from(roundSamples, roundOutcomes.size).medianMs
                                )
                            }
                        }
                        if (networkIssue != null) break@cycleLoop
                    }
                    completedCycles += 1
                }
            } catch (error: Exception) {
                failure = error.message ?: "Erreur inconnue pendant le diagnostic"
            }

            val sessionEndedAtEpochMs = System.currentTimeMillis()
            val sessionEndNetwork = currentNetworkObservation()
            if (networkIssue == null) {
                networkIssue = sessionStartNetwork.issueComparedTo(sessionEndNetwork)
            }

            val outcomes = targets.map { target ->
                val samples = samplesByTarget.getValue(target)
                TargetResult(
                    target = target,
                    metrics = if (samples.isNotEmpty()) PingMetrics.fromAttempts(orderedAttemptsByTarget.getValue(target), attemptsByTarget.getValue(target)) else null,
                    attemptedRequests = attemptsByTarget.getValue(target),
                    roundMedians = roundMediansByTarget.getValue(target),
                    failures = failuresByTarget.getValue(target).toMap(),
                    cycleOutcomes = cycleOutcomesByTarget.getValue(target).toList(),
                    attemptTraces = attemptTracesByTarget.getValue(target).toList()
                )
            }
            val ranked = if (failure == null && networkIssue == null) {
                outcomes.filter { outcome ->
                    outcome.metrics?.let { MeasurementPlan.isHttpsRankable(it.totalRequests - it.lostRequests, it.totalRequests) } == true
                }.sortedBy { it.metrics!!.medianMs }
            } else {
                emptyList()
            }

            runOnUiThread {
                testButton.isEnabled = true
                resultsContainer.removeAllViews()
                if (failure != null || networkIssue != null) {
                    status.text = "Diagnostic interrompu"
                    status.setTextColor(Color.rgb(255, 115, 105))
                    if (failure != null) {
                        resultsContainer.addView(label(
                            "Erreur : " + failure,
                            13f, Color.rgb(255, 150, 130), false, Gravity.START
                        ))
                    }
                    if (networkIssue != null) {
                        resultsContainer.addView(label(
                            networkIssue.userMessage + " Les mesures partielles sont conservées, mais aucun point de contrôle ne sera classé.",
                            13f, Color.rgb(255, 185, 90), false, Gravity.START
                        ))
                    }
                } else {
                    status.text = when {
                        ranked.isNotEmpty() -> "Comparaison terminée"
                        outcomes.any { it.metrics != null } -> "Données insuffisantes"
                        else -> "Destinations inaccessibles"
                    }
                    status.setTextColor(when {
                        ranked.isNotEmpty() -> Color.rgb(100, 210, 150)
                        outcomes.any { it.metrics != null } -> Color.rgb(255, 185, 90)
                        else -> Color.rgb(255, 115, 105)
                    })
                }

                resultsContainer.addView(label("Profil : " + selectedGame, 13f, mutedColor, false, Gravity.START))
                resultsContainer.addView(label("Cycles terminés : $completedCycles/${MeasurementPlan.HTTPS_CYCLES}${if (failure != null || networkIssue != null) " — interrompu" else ""}", 12f, mutedColor, false, Gravity.START))

                if (ranked.isNotEmpty()) {
                    val best = ranked.first()
                    resultsContainer.addView(label(
                        "Médiane globale la plus basse : ${best.target.name}\nMédiane : ${best.metrics!!.medianMs} ms",
                        15f, accentColor, true, Gravity.START
                    ).apply { setPadding(0, dp(10), 0, dp(12)) })
                }

                outcomes.forEach { outcome ->
                    val metrics = outcome.metrics
                    val targetCard = card()
                    targetCard.addView(label(outcome.target.name, 14f, Color.WHITE, true, Gravity.START))
                    val assessment = DiagnosticEngine.assess(metrics, outcome.roundMedians)
                    targetCard.addView(label(
                        "Régularité observée : ${assessment.label}",
                        13f,
                        if (assessment.label == "Régulier") Color.rgb(100, 210, 150) else accentColor,
                        true,
                        Gravity.START
                    ).apply { setPadding(0, dp(7), 0, 0) })
                    targetCard.addView(label(
                        assessment.explanation,
                        12f, mutedColor, false, Gravity.START
                    ).apply { setPadding(0, dp(4), 0, dp(4)) })
                    val failureLine = if (outcome.failures.isEmpty()) {
                        "Échecs HTTPS : ${outcome.failures.values.sum()}/${outcome.attemptedRequests}"
                    } else {
                        "Échecs HTTPS : ${outcome.failures.values.sum()}/${outcome.attemptedRequests} — " + outcome.failures.entries
                            .sortedByDescending { it.value }
                            .joinToString(" · ") { "${it.key.label} : ${it.value}" }
                    }
                    val detail = if (metrics == null) {
                        "Aucune réponse exploitable\n$failureLine"
                    } else {
                        val cycleLine = if (outcome.roundMedians.isEmpty()) {
                            "Aucun cycle exploitable"
                        } else {
                            "Médianes par cycle : " + outcome.roundMedians.joinToString(" / ") { "${it} ms" }
                        }
                        val spreadLine = if (outcome.roundMedians.size >= 2) {
                            "Écart entre cycles : ${outcome.roundMedians.maxOrNull()!! - outcome.roundMedians.minOrNull()!!} ms"
                        } else {
                            "Cycles exploitables : ${outcome.roundMedians.size}/${MeasurementPlan.HTTPS_CYCLES}"
                        }
                        "Médiane globale : ${metrics.medianMs} ms\nP95 observé : ${metrics.p95Ms} ms\nVariation moyenne entre réponses réussies : ${metrics.jitterMs} ms\n$failureLine\n$cycleLine\n$spreadLine"
                    }
                    targetCard.addView(label(
                        detail, 13f,
                        if (metrics == null) Color.rgb(255, 150, 130) else mutedColor,
                        false, Gravity.START
                    ).apply {
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setLineSpacing(dp(3).toFloat(), 1f)
                        background = resultDetailBackground()
                    })
                    resultsContainer.addView(targetCard)
                }

                if (failure == null) {
                    resultsContainer.addView(label(
                        "Comparaison de points de contrôle HTTPS uniquement : la médiane et la régularité observées ne désignent pas le meilleur serveur ni la meilleure route de jeu.",
                        11f, mutedColor, false, Gravity.START
                    ).apply { setPadding(0, dp(10), 0, 0) })
                    saveHistory(
                        selectedGame,
                        networkType,
                        outcomes,
                        sessionStartedAtEpochMs,
                        sessionEndedAtEpochMs,
                        sessionStartNetwork,
                        sessionEndNetwork,
                        networkIssue
                    )
                    renderHistory()
                }
                refreshNetworkInfo()
            }
        }
    }

    private fun saveHistory(
        game: String,
        network: String,
        outcomes: List<TargetResult>,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long,
        startNetwork: NetworkObservation,
        endNetwork: NetworkObservation,
        networkIssue: NetworkSessionIssue?
    ) {
        val history = try {
            JSONArray(preferences.getString("sessions", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val stamp = stampFormat.format(Date(startedAtEpochMs))
        val completedStamp = stampFormat.format(Date(completedAtEpochMs))
        val session = JSONObject().apply {
            put("timestamp", stamp)
            put("completedAt", completedStamp)
            put("startedAtEpochMs", startedAtEpochMs)
            put("completedAtEpochMs", completedAtEpochMs)
            put("game", game)
            put("network", network)
            put("networkStart", startNetwork.displayName())
            put("networkEnd", endNetwork.displayName())
            put("networkChanged", networkIssue != null)
            put("networkIssue", networkIssue?.userMessage ?: JSONObject.NULL)
            put("targets", JSONArray().apply {
                outcomes.forEach { outcome ->
                    val metrics = outcome.metrics
                    put(JSONObject().apply {
                        put("name", outcome.target.name)
                        put("medianMs", metrics?.medianMs ?: JSONObject.NULL)
                        put("p95Ms", metrics?.p95Ms ?: JSONObject.NULL)
                        put("variationMs", metrics?.jitterMs ?: JSONObject.NULL)
                        put("lostRequests", metrics?.lostRequests ?: outcome.attemptedRequests)
                        put("totalRequests", metrics?.totalRequests ?: outcome.attemptedRequests)
                        put("cycleMedians", JSONArray(outcome.roundMedians))
                        put("attempts", JSONArray().apply {
                            outcome.cycleOutcomes.forEachIndexed { cycleIndex, cycle ->
                                cycle.forEachIndexed { attemptIndex, probe ->
                                    put(JSONObject().apply {
                                        put("cycle", cycleIndex + 1)
                                        put("attempt", attemptIndex + 1)
                                        val trace = outcome.attemptTraces.getOrNull(cycleIndex)?.getOrNull(attemptIndex)
                                        val networkChangedDuringAttempt = trace?.networkStable == false
                                        put("elapsedMs", if (networkChangedDuringAttempt) JSONObject.NULL else probe.elapsedMs ?: JSONObject.NULL)
                                        put("rawElapsedMs", probe.elapsedMs ?: JSONObject.NULL)
                                        put("httpCode", probe.httpCode ?: JSONObject.NULL)
                                        put("failure", if (networkChangedDuringAttempt) ProbeFailure.NETWORK_CHANGED.name else probe.failure?.name ?: JSONObject.NULL)
                                        put("probeFailure", probe.failure?.name ?: JSONObject.NULL)
                                        put("startedAtEpochMs", trace?.startedAtEpochMs ?: JSONObject.NULL)
                                        put("completedAtEpochMs", trace?.completedAtEpochMs ?: JSONObject.NULL)
                                        put("networkBefore", trace?.networkBefore?.displayName() ?: JSONObject.NULL)
                                        put("networkAfter", trace?.networkAfter?.displayName() ?: JSONObject.NULL)
                                        put("networkBeforeId", trace?.networkBefore?.networkId ?: JSONObject.NULL)
                                        put("networkAfterId", trace?.networkAfter?.networkId ?: JSONObject.NULL)
                                        put("internetValidatedBefore", trace?.networkBefore?.validated ?: JSONObject.NULL)
                                        put("internetValidatedAfter", trace?.networkAfter?.validated ?: JSONObject.NULL)
                                        put("networkStable", trace?.networkStable ?: JSONObject.NULL)
                                    })
                                }
                            }
                        })
                        put("failureCategories", JSONObject().apply {
                            outcome.failures.forEach { (failure, count) -> put(failure.name, count) }
                        })
                    })
                }
            })
        }

        val updated = JSONArray().put(session)
        for (i in 0 until minOf(history.length(), 9)) {
            val oldEntry = history.opt(i)
            if (oldEntry != null) updated.put(oldEntry)
        }
        preferences.edit().putString("sessions", updated.toString()).apply()
    }

    private fun renderHistory() {
        val history = try {
            JSONArray(preferences.getString("sessions", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        if (history.length() == 0) {
            historyText.text = "Aucun test enregistré pour le moment."
            return
        }

        val grouped = linkedMapOf<String, MutableList<String>>()
        val sessionLines = mutableListOf<String>()

        for (i in 0 until history.length()) {
            val item = history.opt(i)
            if (item is JSONObject) {
                val network = item.optString("network", "Réseau inconnu")
                val game = item.optString("game", "Jeu inconnu")
                val timestamp = item.optString("timestamp", "Date inconnue")
                val completedAt = item.optString("completedAt", "")
                val networkChanged = item.optBoolean("networkChanged", false)
                val networkStart = item.optString("networkStart", network)
                val networkEnd = item.optString("networkEnd", network)
                val targetsArray = item.optJSONArray("targets") ?: JSONArray()
                val targetLines = mutableListOf<String>()
                var bestMedian: Long? = null

                for (j in 0 until targetsArray.length()) {
                    val target = targetsArray.optJSONObject(j) ?: continue
                    val name = target.optString("name", "Destination")
                    val median = if (target.isNull("medianMs")) null else target.optLong("medianMs")
                    val lost = target.optInt("lostRequests", 9)
                    val total = target.optInt("totalRequests", 9)
                    if (median != null) {
                        targetLines.add("$name : $median ms — échecs HTTPS $lost/$total")
                        if (!networkChanged &&
                            (if (total >= MeasurementPlan.HTTPS_TOTAL_REQUESTS) MeasurementPlan.MIN_SUCCESSFUL_HTTPS_FOR_RANKING else 6) <= total - lost &&
                            (bestMedian == null || median < bestMedian)
                        ) bestMedian = median
                    } else {
                        targetLines.add("$name : aucune réponse — échecs HTTPS $lost/$total")
                    }
                }

                val sessionNetworkLabel = if (networkChanged) {
                    "⚠ réseau modifié : $networkStart → $networkEnd"
                } else {
                    network
                }
                val durationLabel = if (completedAt.isNotBlank()) " → $completedAt" else ""
                val summary = "$timestamp$durationLabel | $sessionNetworkLabel | $game"
                val issueLine = if (networkChanged) {
                    "\nClassement ignoré : ${item.optString("networkIssue", "conditions réseau modifiées")}"
                } else ""
                sessionLines.add(summary + issueLine + if (targetLines.isEmpty()) "" else "\n" + targetLines.joinToString("\n"))
                if (!networkChanged && bestMedian != null) {
                    grouped.getOrPut(network) { mutableListOf() }
                        .add("$timestamp : meilleur point HTTPS $bestMedian ms")
                }
            } else if (item is String) {
                // Preserve and display sessions saved by versions before 0.7.0.
                sessionLines.add(item)
            }
        }

        val comparison = if (grouped.isEmpty()) {
            "Comparaison par type de connexion : disponible après plusieurs tests enregistrés."
        } else {
            grouped.entries.joinToString("\n\n", "SESSIONS CLASSÉES PAR CONNEXION\n") { entry ->
                entry.key + "\n" + entry.value.joinToString("\n")
            }
        }

        historyText.text = comparison + "\n\n10 DERNIÈRES SESSIONS\n" +
            sessionLines.joinToString("\n\n")
    }

    private fun successfulAttemptSlots(target: JSONObject): Set<String>? {
        val attempts = target.optJSONArray("attempts") ?: return null
        if (attempts.length() != MeasurementPlan.HTTPS_TOTAL_REQUESTS) return null
        val seen = mutableSetOf<String>()
        val successful = mutableSetOf<String>()
        for (index in 0 until attempts.length()) {
            val attempt = attempts.optJSONObject(index) ?: return null
            val cycle = attempt.optInt("cycle", -1)
            val number = attempt.optInt("attempt", -1)
            if (cycle !in 1..MeasurementPlan.HTTPS_CYCLES ||
                number !in 1..MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE
            ) return null
            val slot = "$cycle:$number"
            if (!seen.add(slot)) return null
            if (!attempt.isNull("elapsedMs") &&
                attempt.optString("failure") != ProbeFailure.NETWORK_CHANGED.name
            ) successful.add(slot)
        }
        return successful
    }

    private fun compareRecentSessions() {
        val history = try {
            JSONArray(preferences.getString("sessions", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val eligible = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            .filter { session ->
                !session.optBoolean("networkChanged", false) &&
                    session.optString("networkStart", session.optString("network")) ==
                        session.optString("networkEnd", session.optString("network")) &&
                    session.optString("network").isNotBlank() &&
                    session.optString("game").isNotBlank()
            }

        if (eligible.size < 2) {
            showMeasurementDialog(
                "Comparaison indisponible",
                "Il faut au moins deux sessions terminées sans changement de réseau pendant les tests. Les sessions doivent utiliser le même type de connexion et le même profil de jeu."
            )
            return
        }

        val latest = eligible[0]
        val previous = eligible.drop(1).firstOrNull {
            it.optString("network") == latest.optString("network") &&
                it.optString("game") == latest.optString("game")
        }
        if (previous == null) {
            showMeasurementDialog(
                "Aucune paire comparable",
                "Les deux sessions les plus récentes ne partagent pas le même type de connexion et le même profil de jeu. Relance deux diagnostics dans des conditions comparables."
            )
            return
        }

        val previousTargets = previous.optJSONArray("targets") ?: JSONArray()
        val latestTargets = latest.optJSONArray("targets") ?: JSONArray()
        val previousByName = (0 until previousTargets.length()).mapNotNull {
            previousTargets.optJSONObject(it)
        }.associateBy { it.optString("name") }

        val output = StringBuilder()
        output.append("PROFIL : ").append(latest.optString("game"))
            .append("\nRÉSEAU : ").append(latest.optString("network"))
            .append("\nSESSION PRÉCÉDENTE : ").append(previous.optString("timestamp"))
            .append("\nSESSION RÉCENTE : ").append(latest.optString("timestamp"))
            .append("\n\nÉCARTS DES MÉDIANES HTTPS\n")
        var compared = 0

        for (index in 0 until latestTargets.length()) {
            val current = latestTargets.optJSONObject(index) ?: continue
            val old = previousByName[current.optString("name")] ?: continue
            val oldMedian = if (old.isNull("medianMs")) null else old.optLong("medianMs")
            val newMedian = if (current.isNull("medianMs")) null else current.optLong("medianMs")
            val oldTotal = old.optInt("totalRequests")
            val newTotal = current.optInt("totalRequests")
            val oldSuccess = oldTotal - old.optInt("lostRequests")
            val newSuccess = newTotal - current.optInt("lostRequests")
            val oldSuccessfulSlots = successfulAttemptSlots(old)
            val newSuccessfulSlots = successfulAttemptSlots(current)
            val sameAttemptPattern = oldSuccessfulSlots != null &&
                newSuccessfulSlots != null && oldSuccessfulSlots == newSuccessfulSlots

            if (oldMedian == null || newMedian == null ||
                oldTotal != MeasurementPlan.HTTPS_TOTAL_REQUESTS ||
                newTotal != MeasurementPlan.HTTPS_TOTAL_REQUESTS ||
                oldSuccess < MeasurementPlan.MIN_SUCCESSFUL_HTTPS_FOR_ASSESSMENT ||
                newSuccess < MeasurementPlan.MIN_SUCCESSFUL_HTTPS_FOR_ASSESSMENT ||
                oldSuccessfulSlots == null || newSuccessfulSlots == null ||
                oldSuccessfulSlots.size != oldSuccess || newSuccessfulSlots.size != newSuccess ||
                !sameAttemptPattern
            ) {
                val reason = if (oldSuccessfulSlots != null && newSuccessfulSlots != null) {
                    "positions des réponses réussies différentes"
                } else {
                    "données brutes incomplètes ou échantillons non comparables"
                }
                output.append(current.optString("name"))
                    .append(" : ").append(reason).append("\n")
                continue
            }

            val delta = newMedian - oldMedian
            val direction = when {
                delta < 0 -> "plus rapide"
                delta > 0 -> "plus lent"
                else -> "inchangé"
            }
            output.append(current.optString("name"))
                .append("\n  Avant : ").append(oldMedian).append(" ms")
                .append("\n  Maintenant : ").append(newMedian).append(" ms")
                .append("\n  Écart : ").append(if (delta > 0) "+" else "").append(delta)
                .append(" ms (").append(direction).append(")")
                .append("\n  Réponses réussies : ").append(oldSuccess).append("/")
                .append(oldTotal).append(" → ").append(newSuccess).append("/")
                .append(newTotal).append("\n  Échantillon : 25 tentatives planifiées dans chaque session")
                .append("\n\n")
            compared++
        }

        if (compared == 0) {
            output.append("\nAucune destination ne possède assez de mesures valides dans les deux sessions.")
        }
        output.append("\nINTERPRÉTATION : cette comparaison décrit uniquement les points de contrôle HTTPS. Elle ne prouve pas une amélioration du ping ni de la route d'un serveur de jeu. L'heure, la charge du réseau et du serveur distant peuvent influencer les résultats.")
        showMeasurementDialog("Comparaison de sessions", output.toString())
    }

    private fun saveRouteDetails(
        samplesByTarget: Map<ProbeTarget, List<RouteProbeOutcome>>,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long,
        startNetwork: NetworkObservation,
        endNetwork: NetworkObservation,
        networkIssue: NetworkSessionIssue?
    ) {
        val details = JSONObject().apply {
            put("timestamp", startedAtEpochMs)
            put("completedAtEpochMs", completedAtEpochMs)
            put("networkStart", startNetwork.displayName())
            put("networkEnd", endNetwork.displayName())
            put("networkChanged", networkIssue != null)
            put("networkIssue", networkIssue?.userMessage ?: JSONObject.NULL)
            put("targets", JSONArray().apply {
                targets.forEach { target ->
                    val samples = samplesByTarget[target].orEmpty()
                    put(JSONObject().apply {
                        put("name", target.name)
                        put("outcomes", JSONArray().apply {
                            samples.forEachIndexed { index, outcome ->
                                put(JSONObject().apply {
                                    put("sample", index + 1)
                                    put("host", outcome.host)
                                    put("dnsMs", outcome.dnsMs ?: JSONObject.NULL)
                                    put("tcpMs", outcome.tcpConnectMs ?: JSONObject.NULL)
                                    put("tcpAttemptTotalMs", outcome.tcpAttemptTotalMs ?: JSONObject.NULL)
                                    put("tlsMs", outcome.tlsHandshakeMs ?: JSONObject.NULL)
                                    put("httpStatusLineMs", outcome.httpStatusLineMs ?: JSONObject.NULL)
                                    put("httpStatus", outcome.httpStatus ?: JSONObject.NULL)
                                    put("failure", outcome.failure?.name ?: JSONObject.NULL)
                                    put("totalMs", outcome.totalMs)
                                    put("resolvedAddresses", JSONArray(outcome.resolvedAddresses))
                                    put("connectedAddress", outcome.connectedAddress ?: JSONObject.NULL)
                                    put("tcpAttempts", JSONArray().apply {
                                        outcome.tcpAttempts.forEach { attempt ->
                                            put(JSONObject().apply {
                                                put("address", attempt.address)
                                                put("addressFamily", attempt.addressFamily)
                                                put("elapsedMs", attempt.elapsedMs)
                                                put("errorType", attempt.errorType ?: JSONObject.NULL)
                                                put("errorMessage", attempt.errorMessage ?: JSONObject.NULL)
                                            })
                                        }
                                    })
                                    put("errorType", outcome.errorType ?: JSONObject.NULL)
                                    put("errorMessage", outcome.errorMessage ?: JSONObject.NULL)
                                    put("tlsFailureCategory", outcome.tlsFailureCategory?.name ?: JSONObject.NULL)
                                })
                            }
                        })
                    })
                }
            })
        }
        preferences.edit().putString("last_route_details", details.toString()).apply()
    }

    private fun showMeasurementDialog(title: String, content: String) {
        val text = TextView(this).apply {
            this.text = content
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(text)
            setBackgroundColor(backgroundColor)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("FERMER", null)
            .show()
    }

    private fun showLatestMeasurementDetails() {
        val history = try {
            JSONArray(preferences.getString("sessions", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val session = history.optJSONObject(0)
        if (session == null) {
            Toast.makeText(this, "Aucune session enregistrée.", Toast.LENGTH_SHORT).show()
            return
        }

        val output = StringBuilder()
        output.append("Session : ").append(session.optString("timestamp", "Date inconnue"))
            .append("\nFin : ").append(session.optString("completedAt", "Non disponible"))
            .append("\nRéseau : ").append(session.optString("network", "Inconnu"))
            .append("\nProfil : ").append(session.optString("game", "Inconnu"))
        if (session.optBoolean("networkChanged", false)) {
            output.append("\nATTENTION : ").append(session.optString("networkIssue", "Réseau modifié pendant la session"))
        }
        output.append("\n\n")
        val targetsArray = session.optJSONArray("targets") ?: JSONArray()
        for (targetIndex in 0 until targetsArray.length()) {
            val target = targetsArray.optJSONObject(targetIndex) ?: continue
            output.append("━━━━━━━━━━━━━━━━━━━━\n")
                .append(target.optString("name", "Destination")).append("\n")
            val attempts = target.optJSONArray("attempts") ?: JSONArray()
            if (attempts.length() == 0) {
                output.append("Aucune mesure individuelle conservée pour cette ancienne session. Relance un diagnostic avec cette version.\n\n")
                continue
            }
            for (cycle in 1..MeasurementPlan.HTTPS_CYCLES) {
                output.append("\nCYCLE ").append(cycle).append("\n")
                var found = false
                for (index in 0 until attempts.length()) {
                    val attempt = attempts.optJSONObject(index) ?: continue
                    if (attempt.optInt("cycle") != cycle) continue
                    found = true
                    val number = attempt.optInt("attempt")
                    output.append("  Essai ").append(number)
                    val attemptStartedAt = attempt.optLong("startedAtEpochMs", 0L)
                    if (attemptStartedAt > 0L) {
                        output.append(" — ").append(
                            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(attemptStartedAt))
                        )
                    }
                    if (!attempt.isNull("networkBefore")) {
                        output.append(" · réseau : ").append(attempt.optString("networkBefore"))
                    }
                    output.append(" : ")
                    if (attempt.optString("failure", "") == ProbeFailure.NETWORK_CHANGED.name &&
                        !attempt.isNull("rawElapsedMs")
                    ) {
                        output.append("RÉPONSE NON CLASSÉE — ")
                            .append(attempt.optLong("rawElapsedMs")).append(" ms")
                    } else if (attempt.isNull("elapsedMs")) {
                        output.append("ÉCHEC — ").append(attempt.optString("failure", "Cause inconnue"))
                    } else {
                        output.append(attempt.optLong("elapsedMs")).append(" ms")
                        if (!attempt.isNull("httpCode")) {
                            output.append(" · HTTP ").append(attempt.optInt("httpCode"))
                        }
                    }
                    if (attempt.has("networkStable") && !attempt.isNull("networkStable") &&
                        !attempt.optBoolean("networkStable", true)
                    ) {
                        output.append(" · réseau modifié pendant la requête")
                    }
                    output.append("\n")
                }
                if (!found) output.append("  Aucune tentative enregistrée\n")
            }
            output.append("\n")
        }
        showMeasurementDialog("MESURES HTTPS INDIVIDUELLES", output.toString())
    }

    private fun showLatestRouteDetails() {
        val details = try {
            JSONObject(preferences.getString("last_route_details", "{}"))
        } catch (_: Exception) {
            JSONObject()
        }
        val timestamp = details.optLong("timestamp", 0L)
        val targetsArray = details.optJSONArray("targets")
        if (timestamp <= 0L || targetsArray == null || targetsArray.length() == 0) {
            Toast.makeText(this, "Aucune analyse de phases enregistrée. Lance d'abord « Analyser les phases ».", Toast.LENGTH_LONG).show()
            return
        }
        val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val stamp = stampFormat.format(Date(timestamp))
        val completedAt = details.optLong("completedAtEpochMs", 0L)
        val output = StringBuilder("Analyse : ").append(stamp)
        if (completedAt > 0L) output.append(" → ").append(stampFormat.format(Date(completedAt)))
        output.append("\nRéseau : ").append(details.optString("networkStart", "inconnu"))
        if (details.optBoolean("networkChanged", false)) {
            output.append(" → ").append(details.optString("networkEnd", "inconnu"))
                .append("\nATTENTION : ").append(details.optString("networkIssue", "Réseau modifié pendant l'analyse"))
        }
        output.append("\n")
        val phases = listOf(
            "DNS" to "dnsMs",
            "TCP — adresse connectée" to "tcpMs",
            "TCP — total des tentatives d’adresses" to "tcpAttemptTotalMs",
            "TLS" to "tlsMs",
            "HTTP (ligne de statut)" to "httpStatusLineMs"
        )
        for (targetIndex in 0 until targetsArray.length()) {
            val target = targetsArray.optJSONObject(targetIndex) ?: continue
            output.append("\n━━━━━━━━━━━━━━━━━━━━\n")
                .append(target.optString("name", "Destination")).append("\n")
            val outcomes = target.optJSONArray("outcomes") ?: JSONArray()
            for (index in 0 until outcomes.length()) {
                val sample = outcomes.optJSONObject(index) ?: continue
                output.append("\nEssai ").append(sample.optInt("sample", index + 1)).append("\n")
                phases.forEach { (label, key) ->
                    output.append("  ").append(label).append(" : ")
                    if (sample.isNull(key)) output.append("indisponible")
                    else output.append(sample.optLong(key)).append(" ms")
                    output.append("\n")
                }
                if (!sample.isNull("httpStatus")) {
                    output.append("  Code HTTP : ").append(sample.optInt("httpStatus")).append("\n")
                }
                if (!sample.isNull("failure")) {
                    output.append("  Échec : ").append(sample.optString("failure")).append("\n")
                }
                val resolved = sample.optJSONArray("resolvedAddresses")
                if (resolved != null && resolved.length() > 0) {
                    output.append("  IP résolues : ")
                    output.append((0 until resolved.length()).joinToString(" · ") { resolved.optString(it) })
                    output.append("\n")
                }
                if (!sample.isNull("connectedAddress")) {
                    output.append("  IP connectée : ").append(sample.optString("connectedAddress")).append("\n")
                }
                val tcpAttempts = sample.optJSONArray("tcpAttempts")
                if (tcpAttempts != null && tcpAttempts.length() > 0) {
                    for (attemptIndex in 0 until tcpAttempts.length()) {
                        val attempt = tcpAttempts.optJSONObject(attemptIndex) ?: continue
                        output.append("  TCP ").append(attempt.optString("addressFamily"))
                            .append(" ").append(attempt.optString("address"))
                            .append(" : ").append(attempt.optLong("elapsedMs")).append(" ms")
                        if (!attempt.isNull("errorType")) output.append(" — ").append(attempt.optString("errorType"))
                        else output.append(" — connecté")
                        if (!attempt.isNull("errorMessage")) output.append(" (").append(attempt.optString("errorMessage")).append(")")
                        output.append("\n")
                    }
                }
                if (!sample.isNull("tlsFailureCategory")) {
                    val category = runCatching { TlsFailureCategory.valueOf(sample.optString("tlsFailureCategory")) }.getOrNull()
                    if (category != null) output.append("  Diagnostic TLS : ").append(category.label)
                        .append(" (").append(category.name).append(")\n")
                }
                if (!sample.isNull("errorType")) {
                    output.append("  Exception : ").append(sample.optString("errorType"))
                    if (!sample.isNull("errorMessage")) output.append(" — ").append(sample.optString("errorMessage"))
                    output.append("\n")
                }
                output.append("  Total : ").append(sample.optLong("totalMs")).append(" ms\n")
            }
        }
        showMeasurementDialog("PHASES INDIVIDUELLES", output.toString())
    }

    private fun exportLatestMeasurements() {
        val rows = mutableListOf<MeasurementCsvRow>()
        val history = try {
            JSONArray(preferences.getString("sessions", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val session = history.optJSONObject(0)
        if (session != null) {
            val stamp = session.optString("timestamp", "")
            val targetArray = session.optJSONArray("targets") ?: JSONArray()
            for (targetIndex in 0 until targetArray.length()) {
                val target = targetArray.optJSONObject(targetIndex) ?: continue
                val attempts = target.optJSONArray("attempts") ?: JSONArray()
                for (index in 0 until attempts.length()) {
                    val item = attempts.optJSONObject(index) ?: continue
                    val attemptStartedAt = item.optLong("startedAtEpochMs", 0L)
                    val attemptStamp = if (attemptStartedAt > 0L) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(attemptStartedAt))
                    } else {
                        stamp
                    }
                    val attemptFailure = if (!item.isNull("networkStable") &&
                        !item.optBoolean("networkStable", true)
                    ) {
                        ProbeFailure.NETWORK_CHANGED.name
                    } else if (item.isNull("failure")) null else item.optString("failure")
                    rows.add(MeasurementCsvRow(
                        dataset = "HTTPS",
                        timestamp = attemptStamp,
                        target = target.optString("name", ""),
                        cycle = item.optInt("cycle").toString(),
                        attempt = item.optInt("attempt").toString(),
                        phase = "HTTPS",
                        elapsedMs = if (item.isNull("elapsedMs")) null else item.optLong("elapsedMs").toString(),
                        httpStatus = if (item.isNull("httpCode")) null else item.optInt("httpCode").toString(),
                        failure = attemptFailure
                    ))
                }
            }
        }

        val route = try {
            JSONObject(preferences.getString("last_route_details", "{}"))
        } catch (_: Exception) {
            JSONObject()
        }
        val routeTimestamp = route.optLong("timestamp", 0L)
        val routeStamp = if (routeTimestamp > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(routeTimestamp))
        } else ""
        val routeTargets = route.optJSONArray("targets") ?: JSONArray()
        val routePhases = listOf(
            "DNS" to "dnsMs",
            "TCP_CONNECTED_ADDRESS" to "tcpMs",
            "TCP_ADDRESS_ATTEMPTS_TOTAL" to "tcpAttemptTotalMs",
            "TLS" to "tlsMs",
            "HTTP_STATUS_LINE" to "httpStatusLineMs"
        )
        for (targetIndex in 0 until routeTargets.length()) {
            val target = routeTargets.optJSONObject(targetIndex) ?: continue
            val outcomes = target.optJSONArray("outcomes") ?: JSONArray()
            for (index in 0 until outcomes.length()) {
                val item = outcomes.optJSONObject(index) ?: continue
                routePhases.forEach { (phase, key) ->
                    rows.add(MeasurementCsvRow(
                        dataset = "ROUTE_PHASE",
                        timestamp = routeStamp,
                        target = target.optString("name", ""),
                        cycle = item.optInt("sample", index + 1).toString(),
                        attempt = "1",
                        phase = phase,
                        elapsedMs = if (item.isNull(key)) null else item.optLong(key).toString(),
                        httpStatus = if (phase == "HTTP_STATUS_LINE" && !item.isNull("httpStatus")) item.optInt("httpStatus").toString() else null,
                        failure = if (item.isNull("failure")) null else item.optString("failure")
                    ))
                }
            }
        }

        if (rows.isEmpty()) {
            Toast.makeText(this, "Aucune donnée brute à exporter. Effectue d'abord un diagnostic.", Toast.LENGTH_LONG).show()
            return
        }
        pendingCsvExport = MeasurementCsv.toCsv(rows)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "NOVA-PING-mesures.csv")
        }
        try {
            startActivityForResult(intent, exportRequestCode)
        } catch (_: Exception) {
            pendingCsvExport = null
            Toast.makeText(this, "Impossible d'ouvrir le sélecteur de fichiers.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API Activity, retained for compatibility with the current target SDK.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnConsentRequestCode) {
            if (resultCode == RESULT_OK) {
                val selectedPackage = selectedVpnGamePackage
                if (selectedPackage.isNullOrBlank()) {
                    vpnConsentStatus.text = "Autorisation accordée, mais aucun jeu valide n'est sélectionné."
                    vpnConsentStatus.setTextColor(Color.rgb(255, 190, 110))
                } else {
                    try {
                        NovaVpnService.saveSelectedGamePackage(this, selectedPackage)
                        val selectedLabel = installedGameApps
                            .firstOrNull { it.packageName == selectedPackage }?.label ?: selectedPackage
                        vpnConsentStatus.text = "Autorisation Android accordée. Démarrage de $selectedLabel…"
                        vpnConsentStatus.setTextColor(Color.rgb(100, 210, 150))
                        startGameTunnel()
                    } catch (error: Exception) {
                        vpnConsentStatus.text =
                            "Consentement accordé, mais sélection non enregistrée : ${error.message ?: "erreur inconnue"}"
                        vpnConsentStatus.setTextColor(Color.rgb(255, 190, 110))
                    }
                }
            } else {
                vpnConsentStatus.text = "Autorisation refusée ou annulée. Aucun VPN actif."
                vpnConsentStatus.setTextColor(Color.rgb(255, 190, 110))
            }
            return
        }
        if (requestCode == tunnelReportExportRequestCode) {
            val report = pendingTunnelReportExport
            pendingTunnelReportExport = null
            if (resultCode != RESULT_OK || data?.data == null || report == null) return
            try {
                contentResolver.openOutputStream(data.data!!)?.use { stream ->
                    stream.write(report.toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException("Fichier de destination inaccessible")
                Toast.makeText(this, "Rapport du tunnel exporté.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this, "Échec de l'export : ${error.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != exportRequestCode) return
        val csv = pendingCsvExport
        pendingCsvExport = null
        if (resultCode != RESULT_OK || data?.data == null || csv == null) return
        try {
            val uri = data.data!!
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(csv.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("Fichier de destination inaccessible")
            Toast.makeText(this, "Export CSV enregistré.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Échec de l'export : ${error.message ?: "erreur inconnue"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadInstalledLaunchableApps(): List<InstalledLaunchableApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val appInfo = activityInfo.applicationInfo
                if (activityInfo.packageName == packageName) return@mapNotNull null
                if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return@mapNotNull null
                }
                if (appInfo.category != android.content.pm.ApplicationInfo.CATEGORY_GAME) {
                    return@mapNotNull null
                }
                InstalledLaunchableApp(
                    label = resolveInfo.loadLabel(packageManager).toString(),
                    packageName = activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun requestVpnConsent() {
        val selectedPackage = selectedVpnGamePackage
        if (selectedPackage.isNullOrBlank()) {
            vpnConsentStatus.text = "Sélectionne d'abord une application installée."
            return
        }
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) {
            try {
                NovaVpnService.saveSelectedGamePackage(this, selectedPackage)
                vpnConsentStatus.text = "Autorisation Android déjà accordée. Démarrage du tunnel…"
                vpnConsentStatus.setTextColor(Color.rgb(100, 210, 150))
                startGameTunnel()
            } catch (error: Exception) {
                vpnConsentStatus.text =
                    "Impossible d'enregistrer ce jeu : ${error.message ?: "erreur inconnue"}"
                vpnConsentStatus.setTextColor(Color.rgb(255, 190, 110))
            }
            return
        }
        try {
            startActivityForResult(consentIntent, vpnConsentRequestCode)
            vpnConsentStatus.text = "En attente de la décision dans la fenêtre système Android…"
        } catch (error: Exception) {
            vpnConsentStatus.text =
                "Impossible d'ouvrir la demande Android : ${error.message ?: "erreur inconnue"}"
        }
    }

    private fun requestEndpointObservations() {
        if (!::endpointObservationOptInCheckBox.isInitialized ||
            !endpointObservationOptInCheckBox.isChecked || !vpnTunnelStartRequested
        ) return
        endpointObservationRefreshButton.isEnabled = false
        endpointObservationResults.text = "Récupération des observations en mémoire…"
        runCatching {
            startService(
                Intent(this, NovaVpnService::class.java)
                    .setAction(NovaVpnService.ACTION_REQUEST_ENDPOINTS)
            )
        }.onFailure { error ->
            endpointObservationResults.text =
                "Impossible de récupérer les observations : ${error.message ?: "erreur Android"}"
            endpointObservationRefreshButton.isEnabled = true
        }
    }

    private fun renderEndpointObservations(intent: Intent) {
        val enabled = intent.getBooleanExtra(NovaVpnService.EXTRA_ENDPOINTS_ENABLED, false)
        if (!enabled) {
            endpointObservationResults.text =
                "Observation désactivée pour cette session. Elle doit être autorisée avant le démarrage du tunnel."
            endpointObservationRefreshButton.isEnabled = false
            return
        }
        val raw = intent.getStringExtra(NovaVpnService.EXTRA_ENDPOINTS_JSON).orEmpty()
        val candidates = runCatching { JSONArray(raw) }.getOrNull()
        if (candidates == null || candidates.length() == 0) {
            endpointObservationResults.text =
                "Aucun endpoint public admissible observé pour le moment."
            endpointObservationRefreshButton.isEnabled = true
            return
        }
        val displayLimit = 20
        val lines = buildList {
            add("CANDIDATS OBSERVÉS — RÔLE SERVEUR NON CONFIRMÉ")
            add("")
            for (index in 0 until minOf(candidates.length(), displayLimit)) {
                val item = candidates.optJSONObject(index) ?: continue
                val address = item.optString("address")
                val port = item.optInt("port")
                val transport = item.optString("transport")
                val observations = item.optInt("observations")
                val sessions = item.optInt("sessions")
                val endpoint = if (address.contains(':')) "[$address]:$port" else "$address:$port"
                add("$endpoint • $transport • $observations observation(s) • $sessions session(s)")
            }
            if (candidates.length() > displayLimit) {
                add("")
                add("${candidates.length() - displayLimit} autre(s) candidat(s) non affiché(s).")
            }
            add("")
            add("Ces observations ne prouvent pas qu'une adresse héberge un serveur de match CODM.")
        }
        endpointObservationResults.text = lines.joinToString("\n")
        endpointObservationRefreshButton.isEnabled = true
    }

    private fun startGameTunnel() {
        try {
            startForegroundService(
                Intent(this, NovaVpnService::class.java)
                    .setAction(NovaVpnService.ACTION_START)
                    .putExtra(
                        NovaVpnService.EXTRA_ENDPOINT_OBSERVATION_OPT_IN,
                        endpointObservationOptInCheckBox.isChecked
                    )
            )
            vpnTunnelStartRequested = true
            vpnConsentButton.text = "ARRÊTER LE TUNNEL"
            vpnConsentStatus.text = "Demande de démarrage envoyée au service VPN…"
            vpnConsentStatus.setTextColor(Color.rgb(100, 210, 150))
        } catch (error: Exception) {
            vpnTunnelStartRequested = false
            vpnConsentButton.text = "RÉESSAYER LE TUNNEL VPN"
            vpnConsentStatus.text = "Impossible de démarrer le service : ${error.message ?: "erreur Android"}"
            vpnConsentStatus.setTextColor(Color.rgb(255, 150, 130))
        }
    }

    private fun stopGameTunnel() {
        try {
            startService(Intent(this, NovaVpnService::class.java).setAction(NovaVpnService.ACTION_STOP))
            vpnConsentStatus.text = "Arrêt du tunnel demandé…"
        } catch (error: Exception) {
            vpnConsentStatus.text = "Impossible d'arrêter le tunnel : ${error.message ?: "erreur Android"}"
        }
    }

    private fun dashboardMetric(title: String, initialValue: String): Pair<LinearLayout, TextView> {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(10), dp(9), dp(8), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 22, 38))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(39, 58, 82))
            }
        }
        tile.addView(label(title, 9f, mutedColor, true, Gravity.START))
        val value = label(initialValue, 12f, Color.WHITE, true, Gravity.START).apply {
            setPadding(0, dp(4), 0, 0)
            maxLines = 2
        }
        tile.addView(value)
        return tile to value
    }

    private fun resultDetailBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.rgb(12, 20, 34))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(31, 46, 67))
        }

    private fun actionButtonBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(accentColor)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(94, 157, 255))
        }

    private fun sectionTitle(value: String): TextView =
        label(value, 11f, accentColor, true, Gravity.START).apply {
            letterSpacing = 0.10f
            setPadding(dp(2), dp(22), 0, dp(10))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(37, 53, 76))
        }
        elevation = dp(2).toFloat()
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean, gravityValue: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (wifiScanReceiverRegistered) {
            runCatching { unregisterReceiver(wifiScanReceiver) }
            wifiScanReceiverRegistered = false
        }

        executor.shutdownNow()
        super.onDestroy()
    }
}
