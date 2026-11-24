package com.copastracker.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, FloatingTrackerService::class.java).apply {
                action = FloatingTrackerService.ACTION_START_PROJECTION
                putExtra(FloatingTrackerService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingTrackerService.EXTRA_DATA_INTENT, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Toast.makeText(this, "Permissão de captura cancelada", Toast.LENGTH_SHORT).show()
        }
    }

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
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
            }
            layoutParams = lp
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startService(Intent(this@MainActivity, FloatingTrackerService::class.java))
                }
            }
        }

        val botaoCaptura = Button(this).apply {
            text = "Ativar Captura de Tela"
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
            layoutParams = lp
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ative a permissão de sobreposição primeiro",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                startService(Intent(this@MainActivity, FloatingTrackerService::class.java))

                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        layout.addView(titulo)
        layout.addView(botaoIniciar)
        layout.addView(botaoCaptura)
        setContentView(layout)
    }
}
