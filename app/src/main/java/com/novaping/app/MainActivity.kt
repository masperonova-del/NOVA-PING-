package com.novaping.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    val roundMedians: List<Long>
)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var networkInfo: TextView
    private lateinit var testButton: Button
    private lateinit var gameSpinner: Spinner
    private lateinit var resultsContainer: LinearLayout
    private lateinit var historyText: TextView

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

        root.addView(sectionTitle("PROFIL DE JEU"))
        val gameCard = card()
        gameCard.addView(label("Sélectionne ton jeu", 14f, Color.WHITE, true, Gravity.START))
        gameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Call of Duty: Mobile", "PUBG Mobile", "Free Fire", "Autre jeu")
            )
        }
        gameCard.addView(gameSpinner)
        gameCard.addView(label(
            "Le profil organise les diagnostics. Les serveurs de jeu seront ajoutés après identification de destinations fiables.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(gameCard)

        root.addView(sectionTitle("COMPARAISON DE DESTINATIONS"))
        val testCard = card()
        testCard.addView(label("Diagnostic multi-points", 16f, Color.WHITE, true, Gravity.START))
        testCard.addView(label(
            "3 cycles indépendants, avec 3 mesures par destination à chaque cycle (9 mesures au total). Une mesure de préparation précède le premier cycle. Les résultats restent des temps de réponse HTTPS, pas le ping du jeu.",
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
            "01  Diagnostic Wi-Fi et données mobiles",
            "02  Détection des régions et serveurs de jeux",
            "03  Analyse des routes réseau",
            "04  Recommandations fondées sur les mesures",
            "05  Optimisation expérimentale avec consentement"
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
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        networkInfo.text = "Connexion : $transport\nAccès Internet validé : ${if (validated) "oui" else "non confirmé"}"
        if (::status.isInitialized) status.text = if (validated) "Connexion détectée" else "Connexion à vérifier"
        return transport
    }

    private fun runNetworkTest() {
        testButton.isEnabled = false
        val selectedGame = gameSpinner.selectedItem?.toString() ?: "Autre jeu"
        val networkType = refreshNetworkInfo()
        status.text = "Comparaison en cours…"
        resultsContainer.removeAllViews()
        resultsContainer.addView(label("Préparation du diagnostic…", 13f, mutedColor, false, Gravity.START))

        executor.execute {
            var failure: String? = null
            val samplesByTarget = targets.associateWith { mutableListOf<Long>() }
            val roundMediansByTarget = targets.associateWith { mutableListOf<Long>() }
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
                            HttpsProbe.measureOnce(target.endpoint)?.let { roundSamples.add(it) }
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
                    roundMedians = roundMediansByTarget.getValue(target)
                )
            }
            val ranked = outcomes.filter { it.metrics != null }.sortedBy { it.metrics!!.medianMs }

            runOnUiThread {
                testButton.isEnabled = true
                resultsContainer.removeAllViews()
                if (failure != null) {
                    status.text = "Diagnostic interrompu"
                    resultsContainer.addView(label(
                        "Erreur : " + failure,
                        13f, Color.rgb(255, 150, 130), false, Gravity.START
                    ))
                } else {
                    status.text = if (ranked.isNotEmpty()) "Comparaison terminée" else "Destinations inaccessibles"
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
                    val detail = if (metrics == null) {
                        "Aucune réponse exploitable\nÉchecs : 9/9"
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
                        "Médiane globale : ${metrics.medianMs} ms\nP95 observé : ${metrics.p95Ms} ms\nVariation moyenne : ${metrics.jitterMs} ms\nSans réponse : ${metrics.lostRequests}/${metrics.totalRequests}\n$cycleLine\n$spreadLine"
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
                        targetLines.add("$name : $median ms — pertes $lost/$total")
                        if (bestMedian == null || median < bestMedian) bestMedian = median
                    } else {
                        targetLines.add("$name : aucune réponse — pertes $lost/$total")
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
        executor.shutdownNow()
        super.onDestroy()
    }
}
