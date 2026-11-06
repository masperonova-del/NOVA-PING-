package com.novaping.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 28)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "NOVA PING"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Comprendre. Mesurer. Optimiser."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 32)
        }
        status = TextView(this).apply {
            text = "Moteur réseau : prêt"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 20)
        }
        testButton = Button(this).apply {
            text = "Lancer le test réseau"
            setOnClickListener { runNetworkTest() }
        }
        result = TextView(this).apply {
            text = "Aucune mesure effectuée."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        val note = TextView(this).apply {
            text = "Mesure HTTPS vers le point de contrôle Google.\nCe résultat n'est pas le ping d'un serveur de jeu."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(testButton)
        root.addView(result)
        root.addView(note)
        setContentView(root)
    }

    private fun runNetworkTest() {
        testButton.isEnabled = false
        status.text = "Test en cours…"
        result.text = "8 requêtes HTTPS en préparation."
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
                status.text = if (metrics != null) "Test terminé" else "Aucune réponse valide"
                result.text = if (metrics != null) {
                    "Latence médiane : ${metrics.medianMs} ms\n" +
                        "P95 : ${metrics.p95Ms} ms\n" +
                        "Variation moyenne : ${metrics.jitterMs} ms\n" +
                        "Requêtes sans réponse : ${metrics.lostRequests}/8"
                } else {
                    "Impossible de joindre le point de contrôle. Vérifie ta connexion puis réessaie."
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
