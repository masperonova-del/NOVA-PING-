package com.novaping.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private data class ProbeTarget(val name: String, val endpoint: String)
private data class TargetResult(
    val target: ProbeTarget,
    val metrics: PingMetrics?,
    val roundMedians: List<Long>,
    val failures: Map<ProbeFailure, Int>
)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var networkInfo: TextView
    private lateinit var testButton: Button
    private lateinit var gameSpinner: Spinner
    private lateinit var resultsContainer: LinearLayout
    private lateinit var historyText: TextView
    private lateinit var routeButton: Button
    private lateinit var routeResults: LinearLayout
    private lateinit var stabilityStatus: TextView
    private lateinit var stabilityResults: LinearLayout
    private var stabilityHasEvents = false

    private val stabilityTracker = NetworkStabilityTracker()
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var stabilityMonitorRegistered = false

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

        root.addView(sectionTitle("WI-FI STABILITY MONITOR"))
        val stabilityCard = card()
        stabilityCard.addView(label("Surveillance des coupures", 16f, Color.WHITE, true, Gravity.START))
        stabilityCard.addView(label(
            "Observe les changements du réseau Wi-Fi et la validation Internet par Android lorsque NOVA PING est au premier plan. La surveillance s'arrête en arrière-plan.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(8)) })
        stabilityStatus = label("Surveillance en attente…", 13f, accentColor, true, Gravity.START)
        stabilityCard.addView(stabilityStatus)
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
            "Vérification préalable de l’accès Internet, puis 3 cycles indépendants avec 3 mesures par destination (9 mesures). Les échecs sont classés lorsque possible. Les résultats restent des temps de réponse HTTPS, pas le ping du jeu.",
            13f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(14)) })
        testButton = Button(this).apply {
            text = "LANCER LES 3 CYCLES"
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
            "Effectue 3 essais par destination et résume séparément DNS, TCP, TLS et le délai jusqu'à la ligne de statut HTTP. Ce test ne trace pas les routeurs intermédiaires et ne mesure pas le serveur de match.",
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
            NetworkStabilityEventType.DEFAULT_NETWORK_CHANGED -> accentColor
            NetworkStabilityEventType.INTERNET_STATUS ->
                if (details.contains("validé", ignoreCase = true)) Color.rgb(100, 210, 150)
                else Color.rgb(255, 185, 90)
            NetworkStabilityEventType.WIFI_STATUS ->
                if (details.contains("aucun réseau", ignoreCase = true) ||
                    details.contains("indisponible", ignoreCase = true)) Color.rgb(255, 115, 105)
                else accentColor
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
        val start = maxOf(0, events.length() - 20)
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

    private fun recordStabilityEvent(event: NetworkStabilityEvent?) {
        if (event == null) return
        runOnUiThread {
            if (!::stabilityResults.isInitialized) return@runOnUiThread
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
            for (i in maxOf(0, history.length() - 20) until history.length()) {
                trimmed.put(history.get(i))
            }
            preferences.edit().putString("stability_events", trimmed.toString()).apply()
            renderStabilityHistory()
        }
    }

    private fun synchronizeStabilitySnapshot(manager: ConnectivityManager) {
        val now = System.currentTimeMillis()
        val wifiCapabilities = manager.allNetworks
            .asSequence()
            .mapNotNull { network -> manager.getNetworkCapabilities(network) }
            .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        stabilityTracker.synchronizeWifiState(
            available = wifiCapabilities != null,
            internetValidated = wifiCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            timestampMillis = now
        ).forEach(::recordStabilityEvent)

        val activeCapabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        stabilityTracker.synchronizeDefaultTransport(transportName(activeCapabilities))
    }

    private fun registerStabilityMonitor() {
        if (stabilityMonitorRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        synchronizeStabilitySnapshot(manager)

        defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                recordStabilityEvent(
                    stabilityTracker.onDefaultTransportChanged(
                        transportName(networkCapabilities), System.currentTimeMillis()
                    )
                )
            }
        }

        wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                recordStabilityEvent(
                    stabilityTracker.onWifiAvailabilityChanged(true, System.currentTimeMillis())
                )
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    recordStabilityEvent(
                        stabilityTracker.onWifiInternetValidationChanged(
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                            System.currentTimeMillis()
                        )
                    )
                }
            }

            override fun onLost(network: Network) {
                recordStabilityEvent(
                    stabilityTracker.onWifiAvailabilityChanged(false, System.currentTimeMillis())
                )
            }
        }

        try {
            manager.registerDefaultNetworkCallback(defaultNetworkCallback!!)
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                wifiNetworkCallback!!
            )
            stabilityMonitorRegistered = true
            stabilityStatus.text = "Surveillance active au premier plan"
        } catch (error: RuntimeException) {
            stabilityStatus.text = "Surveillance indisponible : ${error.message ?: "erreur Android"}"
            defaultNetworkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
            wifiNetworkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
            defaultNetworkCallback = null
            wifiNetworkCallback = null
        }
    }

    private fun unregisterStabilityMonitor() {
        if (!stabilityMonitorRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        defaultNetworkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        wifiNetworkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        defaultNetworkCallback = null
        wifiNetworkCallback = null
        stabilityMonitorRegistered = false
        if (::stabilityStatus.isInitialized) {
            stabilityStatus.text = "Surveillance suspendue en arrière-plan"
        }
    }

    override fun onStart() {
        super.onStart()
        if (::stabilityStatus.isInitialized) registerStabilityMonitor()
    }

    override fun onStop() {
        unregisterStabilityMonitor()
        super.onStop()
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
        routeResults.addView(label("3 cycles de mesure DNS, TCP, TLS et HTTP en cours…", 13f, mutedColor, false, Gravity.START))
        executor.execute {
            val samplesByTarget = targets.associateWith { mutableListOf<RouteProbeOutcome>() }
            var interrupted: String? = null
            try {
                repeat(3) { cycleIndex ->
                    val offset = cycleIndex % targets.size
                    val cycleOrder = targets.drop(offset) + targets.take(offset)
                    cycleOrder.forEachIndexed { targetIndex, target ->
                        runOnUiThread {
                            routeResults.removeAllViews()
                            routeResults.addView(label(
                                "Cycle ${cycleIndex + 1}/3 — ${target.name}",
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
                    "Statistiques calculées séparément sur les réponses disponibles. Le P95 est un percentile observé sur un petit échantillon, pas une garantie. Les points de contrôle HTTPS ne représentent pas les serveurs de jeu.",
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
            val failuresByTarget = targets.associateWith { mutableMapOf<ProbeFailure, Int>() }
            try {
                repeat(3) { roundIndex ->
                    val offset = roundIndex % targets.size
                    val roundOrder = targets.drop(offset) + targets.take(offset)
                    roundOrder.forEach { target ->
                        runOnUiThread {
                            status.text = "Cycle ${roundIndex + 1}/3"
                            resultsContainer.removeAllViews()
                            resultsContainer.addView(label(
                                "Cycle ${roundIndex + 1}/3 — ${target.name}",
                                13f, mutedColor, false, Gravity.START
                            ))
                        }

                        if (roundIndex == 0) {
                            HttpsProbe.measureOnce(target.endpoint)
                        }

                        val roundSamples = mutableListOf<Long>()
                        repeat(3) { attempt ->
                            runOnUiThread {
                                resultsContainer.removeAllViews()
                                resultsContainer.addView(label(
                                    "Cycle ${roundIndex + 1}/3 — ${target.name} — mesure ${attempt + 1}/3",
                                    13f, mutedColor, false, Gravity.START
                                ))
                            }
                            val probe = HttpsProbe.measureOnce(target.endpoint)
                            if (probe.elapsedMs != null) {
                                roundSamples.add(probe.elapsedMs)
                            } else {
                                val failures = failuresByTarget.getValue(target)
                                failures[probe.failure ?: ProbeFailure.UNKNOWN] =
                                    (failures[probe.failure ?: ProbeFailure.UNKNOWN] ?: 0) + 1
                            }
                            if (attempt < 2) Thread.sleep(150)
                        }
                        samplesByTarget.getValue(target).addAll(roundSamples)
                        if (roundSamples.isNotEmpty()) {
                            roundMediansByTarget.getValue(target).add(
                                PingMetrics.from(roundSamples, 3).medianMs
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                failure = error.message ?: "Erreur inconnue pendant le diagnostic"
            }

            val outcomes = targets.map { target ->
                val samples = samplesByTarget.getValue(target)
                TargetResult(
                    target = target,
                    metrics = if (samples.isNotEmpty()) PingMetrics.from(samples, 9) else null,
                    roundMedians = roundMediansByTarget.getValue(target),
                    failures = failuresByTarget.getValue(target).toMap()
                )
            }
            val ranked = outcomes.filter { outcome ->
                outcome.metrics?.let { it.totalRequests - it.lostRequests >= 6 } == true
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
                resultsContainer.addView(label("Cycles terminés : ${if (failure == null) 3 else "interrompu"}", 12f, mutedColor, false, Gravity.START))

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
                        "Échecs HTTPS : 0/9"
                    } else {
                        "Causes des échecs HTTPS : " + outcome.failures.entries
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
                            "Cycles exploitables : ${outcome.roundMedians.size}/3"
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
                        put("lostRequests", metrics?.lostRequests ?: 9)
                        put("totalRequests", metrics?.totalRequests ?: 9)
                        put("cycleMedians", JSONArray(outcome.roundMedians))
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
                        if (total - lost >= 6 && (bestMedian == null || median < bestMedian)) bestMedian = median
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
        unregisterStabilityMonitor()
        executor.shutdownNow()
        super.onDestroy()
    }
}
