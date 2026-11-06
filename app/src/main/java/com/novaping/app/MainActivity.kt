package com.novaping.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
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
        val status = TextView(this).apply {
            text = "Moteur réseau : en préparation"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        val testButton = Button(this).apply {
            text = "Diagnostic bientôt disponible"
            isEnabled = false
        }
        val note = TextView(this).apply {
            text = "Version 0.1.0 — interface de démarrage.\nAucune mesure réseau n'est encore effectuée."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(testButton)
        root.addView(note)
        setContentView(root)
    }
}
