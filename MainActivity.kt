package com.copastracker.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val titulo = TextView(this).apply {
            text = "Copas Tracker"
            textSize = 24f
        }

        val botaoIniciar = Button(this).apply {
            text = "Iniciar Copas Tracker"
            setOnClickListener {
                startService(Intent(this@MainActivity, FloatingTrackerService::class.java))
            }
        }

        layout.addView(titulo)
        layout.addView(botaoIniciar)
        setContentView(layout)
    }
}
