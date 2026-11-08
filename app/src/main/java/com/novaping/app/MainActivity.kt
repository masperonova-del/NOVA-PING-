package com.novaping.app

import android.Manifest
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
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
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
import java.util.concurrent.Executors

private const val MAX_STABILITY_EVENTS = 15
private const val ACTION_STABILITY_HISTORY_UPDATED = "com.novaping.app.action.STABILITY_HISTORY_UPDATED"

private data class ProbeTarget(val name: String, val endpoint: String)
private data class TargetResult(
    val target: ProbeTarget,
    val metrics: PingMetrics?,
    val attemptedRequests: Int,
    val roundMedians: List<Long>,
    val failures: Map<ProbeFailure, Int>,
    val cycleOutcomes: List<List<ProbeOutcome>>
)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var stabilityReceiverRegistered = false
    private val stabilityHistoryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STABILITY_HISTORY_UPDATED) {
                refreshStabilityMonitorUi()
            }
        }
    }
    private lateinit var status: TextView
    private lateinit var networkInfo: TextView
    private lateinit var testButton: Button
    private lateinit var gameSpinner: Spinner
    private lateinit var resultsContainer: LinearLayout
    private lateinit var historyText: TextView
    private lateinit var routeButton: Button
    private lateinit var routeResults: LinearLayout
    private lateinit var stabilityStatus: TextView
    private lateinit var stabilityToggleButton: Button
    private lateinit var stabilityResults: LinearLayout
    private var stabilityHasEvents = false
    private var pendingCsvExport: String? = null
    private val exportRequestCode = 9104

    private val backgroundColor = Color.rgb(10, 15, 27)
    private val panelColor = Color.rgb(21, 29, 47)
    private val accentColor = Color.rgb(64, 160, 255)
    private val mutedColor = Color.rgb(160, 174, 195)
    private val preferences by lazy { getSharedPreferences("nova_ping_history", Context.MODE_PRIVATE) }

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

        root.addView(label("NOVA PING", 30f, Color.WHITE, true, Gravity.START))
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "inconnue"
        root.addView(label("VERSION $installedVersion", 10f, mutedColor, true, Gravity.START))
        root.addView(label("COMPRENDRE. MESURER. OPTIMISER.", 11f, accentColor, true, Gravity.START).apply {
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(sectionTitle("TABLEAU DE BORD"))
        val networkCard = card()
        networkCard.addView(label("ÉTAT DU RÉSEAU", 12f, mutedColor, true, Gravity.START))
        status = label("Analyse de la connexion…", 18f, Color.WHITE, true, Gravity.START)
        status.setPadding(0, dp(8), 0, dp(6))
        networkCard.addView(status)
        networkInfo = label("", 13f, mutedColor, false, Gravity.START)
        networkCard.addView(networkInfo)
        root.addView(networkCard)
        refreshNetworkInfo()

        root.addView(sectionTitle("SURVEILLANCE RÉSEAU"))
        val stabilityCard = card()
        stabilityCard.addView(label("Surveillance des coupures", 16f, Color.WHITE, true, Gravity.START))
        stabilityCard.addView(label(
            "Surveille le Wi-Fi et le réseau actif même lorsque tu quittes l'application. Un service visible conserve les relevés locaux à intervalle d'environ 60 secondes. Tu peux l'arrêter à tout moment.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(8)) })
        stabilityStatus = label("Surveillance arrêtée", 13f, accentColor, true, Gravity.START)
        stabilityCard.addView(stabilityStatus)
        stabilityToggleButton = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener {
                if (preferences.getBoolean("stability_monitor_running", false)) {
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

        root.addView(sectionTitle("PROFIL DE JEU"))
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
        gameCard.addView(label(
            "Le profil organise les diagnostics. Les destinations de match ne sont pas considérées comme vérifiées tant que leur origine et leur accessibilité ne sont pas confirmées.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(8), 0, 0) })
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
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener { runNetworkTest() }
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
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener { runRouteAnalysis() }
        }
        routeCard.addView(routeButton)
        routeResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        routeResults.addView(label("Aucune analyse de phase effectuée.", 13f, mutedColor, false, Gravity.START))
        routeCard.addView(routeResults)
        root.addView(routeCard)

        root.addView(sectionTitle("MOTEUR DE TUNNEL"))
        val tunnelCard = card()
        tunnelCard.addView(label(
            "Moteur WireGuard intégré",
            15f, Color.WHITE, true, Gravity.START
        ))
        val tunnelEngineStatus = label(
            "Vérification du moteur…",
            12f, accentColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, 0) }
        tunnelCard.addView(tunnelEngineStatus)
        tunnelCard.addView(label(
            "Cette vérification charge le moteur local. Aucun VPN n'est démarré et aucun trafic n'est redirigé : le relais NOVA n'est pas encore configuré.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(tunnelCard)
        executor.execute {
            val engineResult = runCatching {
                WireGuardEngine.detectVersion(this@MainActivity)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    tunnelEngineStatus.text = engineResult.fold(
                        onSuccess = { "Moteur chargé — WireGuard $it" },
                        onFailure = { "Moteur non chargé : ${it.message ?: "erreur inconnue"}" }
                    )
                    tunnelEngineStatus.setTextColor(
                        if (engineResult.isSuccess) Color.rgb(100, 210, 150)
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
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener { showLatestMeasurementDetails() }
        })
        measurementDetailCard.addView(Button(this).apply {
            text = "VOIR LES PHASES DNS / TCP / TLS / HTTP"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener { showLatestRouteDetails() }
        })
        measurementDetailCard.addView(Button(this).apply {
            text = "EXPORTER LES DONNÉES CSV"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
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
        stabilityStatus.text = if (running) {
            "Surveillance active en arrière-plan · relevé environ chaque minute"
        } else {
            preferences.getString("stability_monitor_error", null)
                ?: "Surveillance arrêtée"
        }
        stabilityToggleButton.text = if (running) {
            "ARRÊTER LA SURVEILLANCE"
        } else {
            "DÉMARRER LA SURVEILLANCE"
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

    private fun startNetworkMonitor() {
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
    }

    override fun onStart() {
        super.onStart()
        refreshStabilityMonitorUi()
    }

    override fun onResume() {
        super.onResume()
        if (!stabilityReceiverRegistered) {
            val filter = IntentFilter(ACTION_STABILITY_HISTORY_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stabilityHistoryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(stabilityHistoryReceiver, filter)
            }
            stabilityReceiverRegistered = true
        }
        refreshStabilityMonitorUi()
    }

    override fun onPause() {
        if (stabilityReceiverRegistered) {
            unregisterReceiver(stabilityHistoryReceiver)
            stabilityReceiverRegistered = false
        }
        super.onPause()
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
            metered = manager.isActiveNetworkMetered,
            roaming = if (transport == NetworkTransport.CELLULAR &&
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == true) false else null,
            downstreamKbps = caps?.linkDownstreamBandwidthKbps,
            upstreamKbps = caps?.linkUpstreamBandwidthKbps
        )
    }

    private fun runRouteAnalysis() {
        routeButton.isEnabled = false
        val context = currentNetworkContext()
        if (!context.internetValidated) {
            routeButton.isEnabled = true
            routeResults.removeAllViews()
            routeResults.addView(label(
                "Android ne confirme pas l'accès à Internet. Connecte-toi puis relance l'analyse.",
                13f, Color.rgb(255, 170, 120), false, Gravity.START
            ))
            return
        }

        routeResults.removeAllViews()
        routeResults.addView(label("5 essais indépendants par destination — analyse DNS, TCP, TLS et HTTP…", 13f, mutedColor, false, Gravity.START))
        executor.execute {
            val samplesByTarget = targets.associateWith { mutableListOf<RouteProbeOutcome>() }
            var interrupted: String? = null
            try {
                repeat(MeasurementPlan.ROUTE_SAMPLES_PER_TARGET) { cycleIndex ->
                    val offset = cycleIndex % targets.size
                    val cycleOrder = targets.drop(offset) + targets.take(offset)
                    cycleOrder.forEachIndexed { targetIndex, target ->
                        runOnUiThread {
                            routeResults.removeAllViews()
                            routeResults.addView(label(
                                "Essai ${cycleIndex + 1}/${MeasurementPlan.ROUTE_SAMPLES_PER_TARGET} — ${target.name}",
                                13f, mutedColor, false, Gravity.START
                            ))
                        }
                        samplesByTarget.getValue(target).add(RouteProbe.measure(target.endpoint))
                        if (targetIndex < cycleOrder.lastIndex) Thread.sleep(200)
                    }
                }
            } catch (error: Exception) {
                interrupted = error.message ?: "Erreur inconnue pendant l'analyse"
            }

            saveRouteDetails(samplesByTarget)
            val aggregates = targets.mapNotNull { target ->
                samplesByTarget.getValue(target).takeIf { it.isNotEmpty() }
                    ?.let { RouteStatistics.aggregate(it) }
            }
            val recommendations = DecisionEngine.recommend(context, aggregates)

            runOnUiThread {
                routeButton.isEnabled = true
                routeResults.removeAllViews()
                if (interrupted != null) {
                    routeResults.addView(label(
                        "Analyse interrompue : $interrupted. Les résultats partiels ne permettent pas une conclusion complète.",
                        12f, Color.rgb(255, 150, 130), false, Gravity.START
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
                        phaseLine("TCP", aggregate.tcp),
                        phaseLine("TLS", aggregate.tls),
                        phaseLine("Ligne de statut HTTP", aggregate.httpStatusLine),
                        "Connexions HTTPS complètes : ${aggregate.completeSamples}/${aggregate.sampleCount}",
                        "Codes HTTP : " + if (aggregate.httpStatuses.isEmpty()) "aucun" else
                            aggregate.httpStatuses.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} : ${it.value}" },
                        "Échecs par phase : " + if (aggregate.failures.isEmpty()) "aucun" else
                            aggregate.failures.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} : ${it.value}" }
                    ).joinToString("\n")
                    detail.addView(label(details, 12f, mutedColor, false, Gravity.START)
                        .apply { setPadding(0, dp(6), 0, 0) })
                    routeResults.addView(detail)
                }

                routeResults.addView(label(
                    "RECOMMANDATIONS DU DECISION ENGINE", 13f, accentColor, true, Gravity.START
                ).apply { setPadding(0, dp(12), 0, dp(6)) })
                recommendations.forEach { recommendation ->
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

    private fun refreshNetworkInfo(): String {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "Aucune connexion"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Autre réseau"
        }
        val hasNetwork = network != null && caps != null
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        networkInfo.text = currentNetworkContext().summary() + "\nDiagnostic : ${currentNetworkPreflight().userMessage}"
        if (::status.isInitialized) {
            status.text = when {
                !hasNetwork -> "Aucun réseau détecté"
                validated -> "Internet confirmé par Android"
                hasInternet -> "Réseau connecté — Internet à vérifier"
                else -> "Connexion à vérifier"
            }
            status.setTextColor(when {
                !hasNetwork -> Color.rgb(255, 115, 105)
                validated -> Color.rgb(100, 210, 150)
                transport == "Wi-Fi" -> accentColor
                else -> Color.rgb(255, 185, 90)
            })
        }
        return transport
    }

    private fun runNetworkTest() {
        testButton.isEnabled = false
        val selectedGame = gameSpinner.selectedItem?.toString() ?: "Autre jeu"
        val networkType = refreshNetworkInfo()
        val preflight = currentNetworkPreflight()
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

        executor.execute {
            var failure: String? = null
            val samplesByTarget = targets.associateWith { mutableListOf<Long>() }
            val roundMediansByTarget = targets.associateWith { mutableListOf<Long>() }
            val cycleOutcomesByTarget = targets.associateWith { mutableListOf<List<ProbeOutcome>>() }
            val failuresByTarget = targets.associateWith { mutableMapOf<ProbeFailure, Int>() }
            val attemptsByTarget = targets.associateWith { 0 }.toMutableMap()
            var completedCycles = 0
            try {
                repeat(MeasurementPlan.HTTPS_CYCLES) { roundIndex ->
                    val offset = roundIndex % targets.size
                    val roundOrder = targets.drop(offset) + targets.take(offset)
                    roundOrder.forEach { target ->
                        runOnUiThread {
                            status.text = "Cycle ${roundIndex + 1}/${MeasurementPlan.HTTPS_CYCLES}"
                            resultsContainer.removeAllViews()
                            resultsContainer.addView(label(
                                "Cycle ${roundIndex + 1}/${MeasurementPlan.HTTPS_CYCLES} — ${target.name}",
                                13f, mutedColor, false, Gravity.START
                            ))
                        }

                        if (roundIndex == 0) {
                            HttpsProbe.measureOnce(target.endpoint)
                        }

                        val roundSamples = mutableListOf<Long>()
                        val roundOutcomes = mutableListOf<ProbeOutcome>()
                        repeat(MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE) { attempt ->
                            runOnUiThread {
                                resultsContainer.removeAllViews()
                                resultsContainer.addView(label(
                                    "Cycle ${roundIndex + 1}/${MeasurementPlan.HTTPS_CYCLES} — ${target.name} — mesure ${attempt + 1}/${MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE}",
                                    13f, mutedColor, false, Gravity.START
                                ))
                            }
                            attemptsByTarget[target] = attemptsByTarget.getValue(target) + 1
                            val probe = HttpsProbe.measureOnce(target.endpoint)
                            roundOutcomes.add(probe)
                            if (probe.elapsedMs != null) {
                                roundSamples.add(probe.elapsedMs)
                            } else {
                                val failures = failuresByTarget.getValue(target)
                                failures[probe.failure ?: ProbeFailure.UNKNOWN] =
                                    (failures[probe.failure ?: ProbeFailure.UNKNOWN] ?: 0) + 1
                            }
                            if (attempt < MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE - 1) Thread.sleep(150)
                        }
                        samplesByTarget.getValue(target).addAll(roundSamples)
                        cycleOutcomesByTarget.getValue(target).add(roundOutcomes.toList())
                        if (roundSamples.isNotEmpty()) {
                            roundMediansByTarget.getValue(target).add(
                                PingMetrics.from(roundSamples, MeasurementPlan.HTTPS_REQUESTS_PER_CYCLE).medianMs
                            )
                        }
                    }
                    completedCycles += 1
                }
            } catch (error: Exception) {
                failure = error.message ?: "Erreur inconnue pendant le diagnostic"
            }

            val outcomes = targets.map { target ->
                val samples = samplesByTarget.getValue(target)
                TargetResult(
                    target = target,
                    metrics = if (samples.isNotEmpty()) PingMetrics.from(samples, attemptsByTarget.getValue(target)) else null,
                    attemptedRequests = attemptsByTarget.getValue(target),
                    roundMedians = roundMediansByTarget.getValue(target),
                    failures = failuresByTarget.getValue(target).toMap(),
                    cycleOutcomes = cycleOutcomesByTarget.getValue(target).toList()
                )
            }
            val ranked = outcomes.filter { outcome ->
                outcome.metrics?.let { MeasurementPlan.isHttpsRankable(it.totalRequests - it.lostRequests, it.totalRequests) } == true
            }.sortedBy { it.metrics!!.medianMs }

            runOnUiThread {
                testButton.isEnabled = true
                resultsContainer.removeAllViews()
                if (failure != null) {
                    status.text = "Diagnostic interrompu"
                    status.setTextColor(Color.rgb(255, 115, 105))
                    resultsContainer.addView(label(
                        "Erreur : " + failure,
                        13f, Color.rgb(255, 150, 130), false, Gravity.START
                    ))
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
                resultsContainer.addView(label("Cycles terminés : $completedCycles/${MeasurementPlan.HTTPS_CYCLES}${if (failure != null) " — interrompu" else ""}", 12f, mutedColor, false, Gravity.START))

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
                    ).apply { setPadding(0, dp(6), 0, 0) })
                    resultsContainer.addView(targetCard)
                }

                if (failure == null) {
                    resultsContainer.addView(label(
                        "Comparaison de points de contrôle HTTPS uniquement : la médiane et la régularité observées ne désignent pas le meilleur serveur ni la meilleure route de jeu.",
                        11f, mutedColor, false, Gravity.START
                    ).apply { setPadding(0, dp(10), 0, 0) })
                    saveHistory(selectedGame, networkType, outcomes)
                    renderHistory()
                }
                refreshNetworkInfo()
            }
        }
    }

    private fun saveHistory(game: String, network: String, outcomes: List<TargetResult>) {
        val history = try {
            JSONArray(preferences.getString("sessions", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val session = JSONObject().apply {
            put("timestamp", stamp)
            put("game", game)
            put("network", network)
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
                                        put("elapsedMs", probe.elapsedMs ?: JSONObject.NULL)
                                        put("httpCode", probe.httpCode ?: JSONObject.NULL)
                                        put("failure", probe.failure?.name ?: JSONObject.NULL)
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
                        if ((if (total >= MeasurementPlan.HTTPS_TOTAL_REQUESTS) MeasurementPlan.MIN_SUCCESSFUL_HTTPS_FOR_RANKING else 6) <= total - lost && (bestMedian == null || median < bestMedian)) bestMedian = median
                    } else {
                        targetLines.add("$name : aucune réponse — échecs HTTPS $lost/$total")
                    }
                }

                val summary = "$timestamp | $network | $game"
                sessionLines.add(summary + if (targetLines.isEmpty()) "" else "\n" + targetLines.joinToString("\n"))
                if (bestMedian != null) {
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

    private fun saveRouteDetails(samplesByTarget: Map<ProbeTarget, List<RouteProbeOutcome>>) {
        val details = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
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
                                    put("tlsMs", outcome.tlsHandshakeMs ?: JSONObject.NULL)
                                    put("httpStatusLineMs", outcome.httpStatusLineMs ?: JSONObject.NULL)
                                    put("httpStatus", outcome.httpStatus ?: JSONObject.NULL)
                                    put("failure", outcome.failure?.name ?: JSONObject.NULL)
                                    put("totalMs", outcome.totalMs)
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
            .append("\nRéseau : ").append(session.optString("network", "Inconnu"))
            .append("\nProfil : ").append(session.optString("game", "Inconnu"))
            .append("\n\n")
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
                    output.append("  Essai ").append(number).append(" : ")
                    if (attempt.isNull("elapsedMs")) {
                        output.append("ÉCHEC — ").append(attempt.optString("failure", "Cause inconnue"))
                    } else {
                        output.append(attempt.optLong("elapsedMs")).append(" ms")
                        if (!attempt.isNull("httpCode")) {
                            output.append(" · HTTP ").append(attempt.optInt("httpCode"))
                        }
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
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val output = StringBuilder("Analyse : ").append(stamp).append("\n")
        val phases = listOf(
            "DNS" to "dnsMs",
            "TCP" to "tcpMs",
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
                    rows.add(MeasurementCsvRow(
                        dataset = "HTTPS",
                        timestamp = stamp,
                        target = target.optString("name", ""),
                        cycle = item.optInt("cycle").toString(),
                        attempt = item.optInt("attempt").toString(),
                        phase = "HTTPS",
                        elapsedMs = if (item.isNull("elapsedMs")) null else item.optLong("elapsedMs").toString(),
                        httpStatus = if (item.isNull("httpCode")) null else item.optInt("httpCode").toString(),
                        failure = if (item.isNull("failure")) null else item.optString("failure")
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
            "TCP" to "tcpMs",
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

    private fun sectionTitle(value: String): TextView =
        label(value, 12f, accentColor, true, Gravity.START).apply {
            setPadding(dp(2), dp(20), 0, dp(9))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
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
        executor.shutdownNow()
        super.onDestroy()
    }
}
