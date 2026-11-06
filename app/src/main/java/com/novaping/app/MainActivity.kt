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
import java.util.concurrent.Executors

private data class ProbeTarget(val name: String, val endpoint: String)
private data class TargetResult(val target: ProbeTarget, val metrics: PingMetrics?)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var networkInfo: TextView
    private lateinit var testButton: Button
    private lateinit var gameSpinner: Spinner

    private val backgroundColor = Color.rgb(10, 15, 27)
    private val panelColor = Color.rgb(21, 29, 47)
    private val accentColor = Color.rgb(64, 160, 255)
    private val mutedColor = Color.rgb(160, 174, 195)

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
            "Le profil organise les diagnostics. La mesure directe des serveurs de jeu sera ajoutée après identification de destinations fiables.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(gameCard)

        root.addView(sectionTitle("COMPARAISON DE DESTINATIONS"))
        val testCard = card()
        testCard.addView(label("Diagnostic multi-points", 16f, Color.WHITE, true, Gravity.START))
        testCard.addView(label(
            "4 mesures HTTPS par destination, sur trois services publics. NOVA PING compare leur réactivité et leur régularité.",
            13f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(14)) })
        testButton = Button(this).apply {
            text = "COMPARER LES DESTINATIONS"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener { runNetworkTest() }
        }
        testCard.addView(testButton)
        result = label("Aucune comparaison effectuée.", 14f, Color.WHITE, false, Gravity.START)
        result.setPadding(0, dp(16), 0, 0)
        testCard.addView(result)
        root.addView(testCard)

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
            "TRANSPARENCE : les mesures HTTPS incluent le temps de réponse du service et ne sont pas le ping d'un serveur de jeu. Cette version ne modifie pas la connexion.",
            11f, mutedColor, false, Gravity.CENTER
        ).apply { setPadding(dp(4), dp(18), dp(4), 0) })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refreshNetworkInfo() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "Aucune connexion détectée"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connexion : Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connexion : données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Connexion : Ethernet"
            else -> "Connexion : autre type de réseau"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        networkInfo.text = "$transport\nAccès Internet validé : ${if (validated) "oui" else "non confirmé"}"
        status.text = if (validated) "Connexion détectée" else "Connexion à vérifier"
    }

    private fun runNetworkTest() {
        testButton.isEnabled = false
        status.text = "Comparaison en cours…"
        result.text = "Préparation des 12 mesures HTTPS."
        val selectedGame = gameSpinner.selectedItem?.toString() ?: "Autre jeu"
        executor.execute {
            val outcomes = mutableListOf<TargetResult>()
            targets.forEachIndexed { index, target ->
                runOnUiThread {
                    result.text = "Profil : $selectedGame\n\nTest ${index + 1}/${targets.size} : ${target.name}…"
                }
                val samples = mutableListOf<Long>()
                repeat(4) { attempt ->
                    HttpsProbe.measureOnce(target.endpoint)?.let { samples.add(it) }
                    if (attempt < 3) Thread.sleep(200)
                }
                val metrics = if (samples.isNotEmpty()) PingMetrics.from(samples, 4) else null
                outcomes.add(TargetResult(target, metrics))
            }
            val ranked = outcomes.filter { it.metrics != null }.sortedBy { it.metrics!!.medianMs }
            runOnUiThread {
                testButton.isEnabled = true
                status.text = if (ranked.isNotEmpty()) "Comparaison terminée" else "Destinations inaccessibles"
                val report = StringBuilder("Profil : $selectedGame\n")
                if (ranked.isNotEmpty()) {
                    report.append("\nDestination la plus réactive : ${ranked.first().target.name}\n")
                    report.append("Médiane observée : ${ranked.first().metrics!!.medianMs} ms\n")
                }
                report.append("\nDÉTAIL DES RÉSULTATS\n")
                outcomes.forEach { outcome ->
                    val metrics = outcome.metrics
                    report.append("\n${outcome.target.name}\n")
                    if (metrics == null) {
                        report.append("Aucune réponse exploitable (4/4 échecs)\n")
                    } else {
                        report.append("Médiane : ${metrics.medianMs} ms | P95 : ${metrics.p95Ms} ms\n")
                        report.append("Variation : ${metrics.jitterMs} ms | Sans réponse : ${metrics.lostRequests}/4\n")
                    }
                }
                report.append("\nCe classement compare uniquement ces points de contrôle HTTPS, pas les serveurs de jeu.")
                result.text = report.toString()
                refreshNetworkInfo()
            }
        }
    }

    private fun sectionTitle(value: String): TextView =
        label(value, 12f, accentColor, true, Gravity.START).apply {
            setPadding(dp(2), dp(20), 0, dp(9))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(panelColor)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        gravityValue: Int
    ): TextView = TextView(this).apply {
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
