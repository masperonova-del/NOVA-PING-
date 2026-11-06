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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

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
            "Le profil sert à organiser les futurs diagnostics. Cette version ne mesure pas encore le serveur réel du jeu.",
            12f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(gameCard)

        root.addView(sectionTitle("DIAGNOSTIC DE LATENCE"))
        val testCard = card()
        testCard.addView(label("Test de stabilité HTTPS", 16f, Color.WHITE, true, Gravity.START))
        testCard.addView(label(
            "8 mesures vers un point de contrôle public pour estimer la réactivité et la régularité de la connexion.",
            13f, mutedColor, false, Gravity.START
        ).apply { setPadding(0, dp(6), 0, dp(14)) })
        testButton = Button(this).apply {
            text = "LANCER LE TEST RÉSEAU"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentColor))
            setOnClickListener { runNetworkTest() }
        }
        testCard.addView(testButton)
        result = label("Aucune mesure effectuée.", 14f, Color.WHITE, false, Gravity.START)
        result.setPadding(0, dp(16), 0, 0)
        testCard.addView(result)
        root.addView(testCard)

        root.addView(sectionTitle("PROCHAINES FONCTIONS"))
        val roadmapCard = card()
        listOf(
            "01  Diagnostic Wi-Fi et données mobiles",
            "02  Comparaison de plusieurs destinations",
            "03  Profils et régions de jeux",
            "04  Recommandations fondées sur les mesures"
        ).forEach { item ->
            roadmapCard.addView(label(item, 13f, Color.WHITE, false, Gravity.START).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        root.addView(roadmapCard)

        root.addView(label(
            "IMPORTANT : ces mesures HTTPS ne représentent pas le ping d'un serveur de jeu. NOVA PING n'applique aucune modification réseau dans cette version.",
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
        status.text = "Mesures en cours…"
        result.text = "8 requêtes HTTPS en préparation."
        val selectedGame = gameSpinner.selectedItem?.toString() ?: "Autre jeu"
        executor.execute {
            val samples = mutableListOf<Long>()
            var successes = 0
            repeat(8) {
                val latency = HttpsProbe.measureOnce()
                if (latency != null) {
                    successes++
                    samples.add(latency)
                }
                if (it < 7) Thread.sleep(250)
            }
            val metrics = if (successes > 0) PingMetrics.from(samples, 8) else null
            runOnUiThread {
                testButton.isEnabled = true
                status.text = if (metrics != null) "Diagnostic terminé" else "Point de contrôle inaccessible"
                result.text = if (metrics != null) {
                    "Profil sélectionné : $selectedGame\n\n" +
                        "Latence médiane : ${metrics.medianMs} ms\n" +
                        "P95 : ${metrics.p95Ms} ms\n" +
                        "Variation moyenne : ${metrics.jitterMs} ms\n" +
                        "Requêtes sans réponse : ${metrics.lostRequests}/8"
                } else {
                    "Impossible de joindre le point de contrôle. Vérifie ta connexion puis réessaie."
                }
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
