package com.copastracker.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class FloatingTrackerService : Service() {

    companion object {
        const val ACTION_START_PROJECTION = "com.copastracker.app.action.START_PROJECTION"
        const val EXTRA_RESULT_CODE = "com.copastracker.app.extra.RESULT_CODE"
        const val EXTRA_DATA_INTENT = "com.copastracker.app.extra.DATA_INTENT"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var counterText: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val suits = listOf("♠", "♥", "♦", "♣")
    private val ranks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")

    private val playedCards = mutableSetOf<String>()
    private val historyStack = mutableListOf<String>()
    private val cardButtons = mutableMapOf<String, TextView>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        setupFloatingUI()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && mediaProjection == null) {
            val resultCode = when {
                intent.hasExtra(EXTRA_RESULT_CODE) ->
                    intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                else ->
                    intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            }

            @Suppress("DEPRECATION")
            val data: Intent? = when {
                intent.hasExtra(EXTRA_DATA_INTENT) ->
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                else ->
                    intent.getParcelableExtra("data")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                startForegroundServiceNotification()
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                setupVirtualDisplay()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "copas_tracker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CopasTracker",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("CopasTracker ativo")
            .setContentText("Toque em FOTO para ler as cartas")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CopasTrackerDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun setupFloatingUI() {
        floatingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61A1A1A"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#44FFFFFF"))
            }
        }

        // Barra superior (arrastável + status)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 10)
        }

        statusText = TextView(this).apply {
            text = "Pronto (OCR)"
            setTextColor(Color.parseColor("#A5D6A7"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        counterText = TextView(this).apply {
            text = "Rest: 52"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 0, 0, 0)
        }

        topBar.addView(statusText)
        topBar.addView(counterText)
        floatingView.addView(topBar)

        // Linha de botões de ação
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        val btnFoto = createActionButton("FOTO", "#2E7D32") { captureAndAnalyze() }
        val btnUndo = createActionButton("DESFAZER", "#EF6C00") { undoLastCard() }
        val btnClear = createActionButton("LIMPAR", "#C62828") { clearAllCards() }

        actionRow.addView(btnFoto)
        actionRow.addView(btnUndo)
        actionRow.addView(btnClear)
        floatingView.addView(actionRow)

        // Grade dos 4 naipes na mesma tela
        for (suit in suits) {
            val suitRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            val suitColor = if (suit == "♥" || suit == "♦") Color.parseColor("#FF5252") else Color.WHITE
            val suitLabel = TextView(this).apply {
                text = suit
                setTextColor(suitColor)
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(44, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            suitRow.addView(suitLabel)

            for (rank in ranks) {
                val cardId = "$rank$suit"
                val cardView = TextView(this).apply {
                    text = rank
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(4, 10, 4, 10)
                    background = createCardBackground(false)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(3, 2, 3, 2)
                    }
                    setOnClickListener { toggleCardManual(cardId) }
                }
                cardButtons[cardId] = cardView
                suitRow.addView(cardView)
            }
            floatingView.addView(suitRow)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (screenWidth * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        // Arrastar pela barra superior
        topBar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun createActionButton(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(12, 6, 12, 6)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = 14f
            }
            layoutParams = LinearLayout.LayoutParams(0, 88, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createCardBackground(played: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            setColor(if (played) Color.parseColor("#B71C1C") else Color.parseColor("#333333"))
            cornerRadius = 8f
        }
    }

    private fun toggleCardManual(cardId: String) {
        if (playedCards.contains(cardId)) {
            playedCards.remove(cardId)
            historyStack.remove(cardId)
            cardButtons[cardId]?.background = createCardBackground(false)
        } else {
            playedCards.add(cardId)
            historyStack.add(cardId)
            cardButtons[cardId]?.background = createCardBackground(true)
        }
        updateCounter()
    }

    private fun markCardIfNew(cardId: String): Boolean {
        if (playedCards.contains(cardId)) return false
        playedCards.add(cardId)
        historyStack.add(cardId)
        cardButtons[cardId]?.background = createCardBackground(true)
        updateCounter()
        return true
    }

    private fun undoLastCard() {
        if (historyStack.isEmpty()) return
        val last = historyStack.removeAt(historyStack.size - 1)
        playedCards.remove(last)
        cardButtons[last]?.background = createCardBackground(false)
        updateCounter()
        statusText.text = "Desfeito: $last"
    }

    private fun clearAllCards() {
        playedCards.clear()
        historyStack.clear()
        cardButtons.values.forEach { it.background = createCardBackground(false) }
        updateCounter()
        statusText.text = "Limpo"
    }

    private fun updateCounter() {
        counterText.text = "Rest: ${52 - playedCards.size}"
    }

    private fun captureAndAnalyze() {
        val reader = imageReader ?: run {
            statusText.text = "Sem captura"
            return
        }

        val image = reader.acquireLatestImage() ?: run {
            statusText.text = "Tela vazia"
            return
        }

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val fullBitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        fullBitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val croppedScreen = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
        fullBitmap.recycle()

        val tableLeft = (screenWidth * 0.20).toInt()
        val tableTop = (screenHeight * 0.28).toInt()
        val tableWidth = (screenWidth * 0.60).toInt()
        val tableHeight = (screenHeight * 0.40).toInt()

        val tableBitmap = Bitmap.createBitmap(croppedScreen, tableLeft, tableTop, tableWidth, tableHeight)
        croppedScreen.recycle()

        val scaled = Bitmap.createScaledBitmap(
            tableBitmap,
            (tableBitmap.width * 1.5).toInt(),
            (tableBitmap.height * 1.5).toInt(),
            true
        )

        val isRedDominant = detectDominantRed(tableBitmap)
        tableBitmap.recycle()

        val inputImage = InputImage.fromBitmap(scaled, 0)
        statusText.text = "Lendo OCR..."

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                scaled.recycle()
                val foundRanks = extractRanks(visionText.text)
                if (foundRanks.isEmpty()) {
                    val rawPreview = visionText.text.replace("\n", " ").take(18)
                    statusText.text = if (rawPreview.isBlank()) "OCR: vazio" else "OCR: $rawPreview"
                    return@addOnSuccessListener
                }

                val suit = if (isRedDominant) "♥" else "♠"
                val added = mutableListOf<String>()
                val already = mutableListOf<String>()

                for (rank in foundRanks) {
                    val cardId = "$rank$suit"
                    if (markCardIfNew(cardId)) {
                        added.add(cardId)
                    } else {
                        already.add(cardId)
                    }
                }

                statusText.text = when {
                    added.isNotEmpty() -> "+${added.joinToString(",")}"
                    already.isNotEmpty() -> "ja:${already.joinToString(",")}"
                    else -> "OCR: ?"
                }
            }
            .addOnFailureListener {
                scaled.recycle()
                statusText.text = "Erro OCR"
            }
    }

    private fun extractRanks(rawText: String): List<String> {
        val tokens = rawText.uppercase()
            .replace("O", "Q")
            .replace("I", "J")
            .split(Regex("\\s+"))

        val result = mutableListOf<String>()
        for (token in tokens) {
            val clean = token.trim()
            if (clean in ranks && clean !in result) {
                result.add(clean)
            }
        }
        return result
    }

    private fun detectDominantRed(bitmap: Bitmap): Boolean {
        var redCount = 0
        var blackCount = 0
        val step = 6
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 150 && g < 80 && b < 80) {
                    redCount++
                } else if (r < 70 && g < 70 && b < 70) {
                    blackCount++
                }
            }
        }
        return redCount > blackCount
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        textRecognizer.close()
    }
}
