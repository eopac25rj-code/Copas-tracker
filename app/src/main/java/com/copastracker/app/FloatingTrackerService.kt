package com.copastracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingTrackerService : Service() {

    companion object {
        const val ACTION_START_PROJECTION = "com.copastracker.app.ACTION_START_PROJECTION"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "EXTRA_DATA_INTENT"
        private const val NOTIFICATION_CHANNEL_ID = "copas_tracker_projection_channel"
        private const val NOTIFICATION_ID = 101
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cardStorage: CardStorage
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val playedCards = mutableSetOf<String>()
    private val undoStack = ArrayDeque<Set<String>>()
    private val cardButtons = mutableMapOf<String, Button>()
    private val suitCounterViews = mutableMapOf<String, TextView>()
    private var counterTextView: TextView? = null
    private var bodyContainer: View? = null
    private var resetButton: Button? = null
    private var undoButton: Button? = null
    private var isMinimized = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var isProjectionCallbackRegistered = false
    private var isCapturingFrame = false
    private var lastCaptureStatus = "OFF"
    private var lastDiagnosticText = ""

    private val displayRanks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")
    private val displaySuits = listOf("♠", "♥", "♦", "♣")
    private var lastSeenTrickCards = setOf<String>()

    private var pendingClearConfirmation = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clearTimeoutRunnable = Runnable {
        pendingClearConfirmation = false
        updateResetButtonAppearance()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            lastCaptureStatus = "PAROU"
            lastDiagnosticText = "Parou"
            isCapturingFrame = false
            updateCounterText()
            stopProjectionResources()
            mediaProjection = null
            isProjectionCallbackRegistered = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cardStorage = CardStorage(this)
        playedCards.addAll(cardStorage.loadPlayedCards())
        createNotificationChannelIfNeeded()
        createFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -999)
            val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA_INTENT)
            }

            if (resultCode != -999 && dataIntent != null) {
                startProjection(resultCode, dataIntent)
            } else {
                lastCaptureStatus = "SEM_PERM"
                lastDiagnosticText = "Code:$resultCode"
                updateCounterText()
                showToast("Permissão não devolveu dados (code=$resultCode)")
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Captura do CopasTracker",
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildProjectionNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CopasTracker ativo")
            .setContentText("Captura de tela pronta")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startProjection(resultCode: Int, dataIntent: Intent) {
        try {
            val notification = buildProjectionNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            lastCaptureStatus = "ERR_FG"
            lastDiagnosticText = e.javaClass.simpleName
            updateCounterText()
            showToast("Erro foreground: ${e.javaClass.simpleName}")
            return
        }

        try {
            stopProjectionResources()
            if (isProjectionCallbackRegistered) {
                mediaProjection?.unregisterCallback(projectionCallback)
                isProjectionCallbackRegistered = false
            }
            mediaProjection?.stop()
            mediaProjection = null

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = projectionManager.getMediaProjection(resultCode, dataIntent)
            if (mp == null) {
                lastCaptureStatus = "MP_NULL"
                lastDiagnosticText = "MP nulo"
                updateCounterText()
                showToast("MediaProjection veio nulo")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mp.registerCallback(projectionCallback, uiHandler)
                isProjectionCallbackRegistered = true
            }

            mediaProjection = mp
            ensureVirtualDisplay(mp)
            lastCaptureStatus = "ON"
            lastDiagnosticText = ""
            updateCounterText()
            showToast("Captura pronta! Toque em FOTO")
        } catch (e: Exception) {
            lastCaptureStatus = "ERR_MP"
            lastDiagnosticText = e.javaClass.simpleName
            updateCounterText()
            showToast("Erro projeção: ${e.javaClass.simpleName}")
        }
    }

    private fun ensureVirtualDisplay(mp: MediaProjection): Boolean {
        if (virtualDisplay != null && imageReader != null && captureWidth > 0 && captureHeight > 0) {
            return true
        }
        stopProjectionResources()

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(72)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val display = mp.createVirtualDisplay(
            "CopasTrackerDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            uiHandler
        )

        if (display == null) {
            reader.close()
            return false
        }

        captureWidth = width
        captureHeight = height
        imageReader = reader
        virtualDisplay = display
        return true
    }

    private fun testSingleFrameCapture() {
        val mp = mediaProjection
        if (mp == null) {
            lastCaptureStatus = "OFF"
            lastDiagnosticText = "Sem projecao"
            updateCounterText()
            showToast("Sem captura ativa! Abra o app e toque em Ativar Captura")
            return
        }
        if (isCapturingFrame) {
            return
        }

        if (!ensureVirtualDisplay(mp)) {
            lastCaptureStatus = "VD_NULL"
            lastDiagnosticText = "VD nulo"
            updateCounterText()
            showToast("VirtualDisplay retornou nulo")
            return
        }

        isCapturingFrame = true
        lastCaptureStatus = "LENDO..."
        updateCounterText()

        uiHandler.postDelayed({
            try {
                val reader = imageReader
                if (reader == null) {
                    lastCaptureStatus = "SEM_READER"
                    lastDiagnosticText = "Reader nulo"
                    return@postDelayed
                }

                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image, captureWidth, captureHeight)
                    image.close()

                    if (isBitmapMostlyBlack(bitmap)) {
                        bitmap.recycle()
                        lastCaptureStatus = "PRETA"
                        lastDiagnosticText = "Tela preta"
                    } else {
                        val centerBitmap = cropCenterRegion(bitmap)
                        bitmap.recycle()

                        if (centerBitmap != null) {
                            processCenterBitmapForCards(centerBitmap)
                            centerBitmap.recycle()
                            lastCaptureStatus = "OK"
                        } else {
                            lastCaptureStatus = "ERR_CROP"
                            lastDiagnosticText = "Crop nulo"
                        }
                    }
                } else {
                    lastCaptureStatus = "SEM_FRAME"
                    lastDiagnosticText = "Sem frame"
                }
            } catch (e: Exception) {
                lastCaptureStatus = "ERR_FRAME"
                lastDiagnosticText = "Err:${e.javaClass.simpleName}"
            } finally {
                isCapturingFrame = false
                updateCounterText()
            }
        }, 120L)
    }
    private fun cropCenterRegion(source: Bitmap): Bitmap? {
        if (source.width <= 1 || source.height <= 1) return null
        val left = (source.width * 0.25f).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * 0.16f).toInt().coerceIn(0, source.height - 1)
        val right = (source.width * 0.75f).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * 0.82f).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private enum class CardSlot { TOP, LEFT, RIGHT, BOTTOM }

    private data class QuadF(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun processCenterBitmapForCards(centerBitmap: Bitmap) {
        val currentTrickCards = mutableSetOf<String>()
        val slotReports = mutableListOf<String>()
        var whiteSlots = 0

        CardSlot.entries.forEach { slot ->
            val rawCrop = cropCardSlot(centerBitmap, slot) ?: return@forEach
            val orientedCrop = normalizeSlotOrientation(rawCrop, slot)
            if (orientedCrop != rawCrop) {
                rawCrop.recycle()
            }

            if (hasWhiteCardSurface(orientedCrop)) {
                whiteSlots++
                val readResult = recognizeCardDetails(orientedCrop)
                if (readResult.cardId != null) {
                    currentTrickCards.add(readResult.cardId)
                    slotReports.add(readResult.cardId)
                } else {
                    val rTxt = readResult.rank ?: "?"
                    val sTxt = readResult.suit ?: "?"
                    slotReports.add("$rTxt$sTxt")
                }
            }
            orientedCrop.recycle()
        }

        if (whiteSlots == 0) {
            lastSeenTrickCards = emptySet()
            lastDiagnosticText = "sem carta na mesa"
            return
        }

        if (currentTrickCards.isEmpty()) {
            lastSeenTrickCards = emptySet()
            lastDiagnosticText = "b=$whiteSlots [${slotReports.joinToString(",")}]"
            return
        }

        val newCards = currentTrickCards.filter { it !in playedCards && it !in lastSeenTrickCards }
        lastSeenTrickCards = currentTrickCards.toSet()

        if (newCards.isNotEmpty()) {
            pushUndoSnapshot()
            playedCards.addAll(newCards)
            cardStorage.savePlayedCards(playedCards)
            for (id in newCards) {
                updateCardButtonStyle(id)
            }
            updateCounterText()
            lastDiagnosticText = "+${newCards.joinToString(",")}"
        } else {
            lastDiagnosticText = "ja:${currentTrickCards.joinToString(",")}"
        }
    }

    private data class CardReadResult(
        val rank: String?,
        val suit: String?,
        val cardId: String?
    )

    private fun cropCardSlot(center: Bitmap, slot: CardSlot): Bitmap? {
        val w = center.width
        val h = center.height
        if (w <= 4 || h <= 4) return null

        val (l, t, r, b) = when (slot) {
            CardSlot.TOP -> QuadF(0.34f, 0.03f, 0.66f, 0.46f)
            CardSlot.LEFT -> QuadF(0.04f, 0.33f, 0.43f, 0.69f)
            CardSlot.RIGHT -> QuadF(0.57f, 0.33f, 0.96f, 0.69f)
            CardSlot.BOTTOM -> QuadF(0.35f, 0.54f, 0.65f, 0.98f)
        }

        val left = (w * l).toInt().coerceIn(0, w - 1)
        val top = (h * t).toInt().coerceIn(0, h - 1)
        val right = (w * r).toInt().coerceIn(left + 1, w)
        val bottom = (h * b).toInt().coerceIn(top + 1, h)
        return Bitmap.createBitmap(center, left, top, right - left, bottom - top)
    }

    private fun normalizeSlotOrientation(bitmap: Bitmap, slot: CardSlot): Bitmap {
        val angle = when (slot) {
            CardSlot.LEFT -> 90f
            CardSlot.RIGHT -> 270f
            else -> 0f
        }
        if (angle == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun hasWhiteCardSurface(bitmap: Bitmap): Boolean {
        var whitePixels = 0
        var sampled = 0
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)

        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val c = bitmap.getPixel(x, y)
                sampled++
                if (Color.red(c) > 190 && Color.green(c) > 190 && Color.blue(c) > 190) {
                    whitePixels++
                }
            }
        }
        return sampled > 0 && (whitePixels.toFloat() / sampled.toFloat()) > 0.14f
    }

    private fun isRedInk(r: Int, g: Int, b: Int): Boolean = r > 150 && g < 105 && b < 105

    private fun isBlackInk(r: Int, g: Int, b: Int): Boolean = r < 85 && g < 85 && b < 85

    private fun recognizeCardDetails(cardBitmap: Bitmap): CardReadResult {
        val w = cardBitmap.width
        val h = cardBitmap.height
        if (w < 8 || h < 8) return CardReadResult(null, null, null)

        val topX = (w * 0.18f).toInt().coerceIn(0, w - 1)
        val topY = (h * 0.05f).toInt().coerceIn(0, h - 1)
        val topW = (w * 0.64f).toInt().coerceAtLeast(1).coerceAtMost(w - topX)
        val topH = (h * 0.36f).toInt().coerceAtLeast(1).coerceAtMost(h - topY)

        val midX = (w * 0.20f).toInt().coerceIn(0, w - 1)
        val midY = (h * 0.42f).toInt().coerceIn(0, h - 1)
        val midW = (w * 0.60f).toInt().coerceAtLeast(1).coerceAtMost(w - midX)
        val midH = (h * 0.42f).toInt().coerceAtLeast(1).coerceAtMost(h - midY)

        val topZone = Bitmap.createBitmap(cardBitmap, topX, topY, topW, topH)
        val midZone = Bitmap.createBitmap(cardBitmap, midX, midY, midW, midH)

        val suit = detectSuitByGrid(midZone, topZone)
        val rank = detectRankByGrid(topZone, suit)

        topZone.recycle()
        midZone.recycle()

        val cardId = if (rank != null && suit != null) "$rank$suit" else null
        return CardReadResult(rank, suit, cardId)
    }

    // Converte uma região em grade 12x12 (0.0 a 1.0 de densidade de tinta por célula)
    private fun toInkGrid12x12(bitmap: Bitmap, pred: (Int, Int, Int) -> Boolean): FloatArray {
        val cols = 12
        val rows = 12
        val out = FloatArray(cols * rows)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return out

        for (gy in 0 until rows) {
            val y0 = (h * gy) / rows
            val y1 = ((h * (gy + 1)) / rows).coerceAtLeast(y0 + 1)
            for (gx in 0 until cols) {
                val x0 = (w * gx) / cols
                val x1 = ((w * (gx + 1)) / cols).coerceAtLeast(x0 + 1)

                var hit = 0
                var total = 0
                val stepX = ((x1 - x0) / 4).coerceAtLeast(1)
                val stepY = ((y1 - y0) / 4).coerceAtLeast(1)

                for (y in y0 until y1 step stepY) {
                    for (x in x0 until x1 step stepX) {
                        val c = bitmap.getPixel(x, y)
                        total++
                        if (pred(Color.red(c), Color.green(c), Color.blue(c))) {
                            hit++
                        }
                    }
                }
                out[gy * cols + gx] = if (total > 0) hit.toFloat() / total.toFloat() else 0f
            }
        }
        return out
    }

    private fun gridDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            sum += abs(a[i] - b[i])
        }
        return if (n > 0) sum / n else 1f
    }

    // Padrões de referência (grade 12x12 resumida em células marcantes) para os 4 naipes
    private fun suitReferenceGrid(suit: String): FloatArray {
        val g = FloatArray(144)
        fun setCell(r: Int, c: Int, v: Float = 0.8f) {
            if (r in 0..11 && c in 0..11) g[r * 12 + c] = v
        }
        when (suit) {
            "♦" -> {
                for (i in 1..10) {
                    val half = if (i <= 5) i else 11 - i
                    for (c in (6 - half)..(5 + half)) setCell(i, c, 0.85f)
                }
            }
            "♥" -> {
                for (r in 2..4) {
                    setCell(r, 3, 0.75f); setCell(r, 4, 0.85f)
                    setCell(r, 7, 0.85f); setCell(r, 8, 0.75f)
                }
                for (r in 5..9) {
                    val span = ((10 - r) * 0.75f).toInt().coerceAtLeast(1)
                    for (c in (6 - span)..(5 + span)) setCell(r, c, 0.80f)
                }
                setCell(10, 5, 0.6f); setCell(10, 6, 0.6f)
            }
            "♠" -> {
                setCell(1, 5, 0.75f); setCell(1, 6, 0.75f)
                for (r in 2..7) {
                    val span = ((r - 1) * 0.8f).toInt().coerceAtLeast(1).coerceAtMost(5)
                    for (c in (6 - span)..(5 + span)) setCell(r, c, 0.82f)
                }
                setCell(8, 3, 0.7f); setCell(8, 8, 0.7f)
                setCell(9, 5, 0.8f); setCell(9, 6, 0.8f)
                setCell(10, 5, 0.8f); setCell(10, 6, 0.8f)
            }
            "♣" -> {
                for (r in 2..4) {
                    setCell(r, 5, 0.85f); setCell(r, 6, 0.85f)
                }
                for (r in 5..7) {
                    for (c in 2..4) setCell(r, c, 0.82f)
                    for (c in 5..6) setCell(r, c, 0.75f)
                    for (c in 7..9) setCell(r, c, 0.82f)
                }
                setCell(8, 5, 0.8f); setCell(8, 6, 0.8f)
                setCell(9, 5, 0.8f); setCell(9, 6, 0.8f)
                setCell(10, 5, 0.8f); setCell(10, 6, 0.8f)
            }
        }
        return g
    }

    private fun detectSuitByGrid(midZone: Bitmap, topZone: Bitmap): String? {
        val redMid = countMatchingPixels(midZone, ::isRedInk)
        val darkMid = countMatchingPixels(midZone, ::isBlackInk)
        val redTop = countMatchingPixels(topZone, ::isRedInk)
        val darkTop = countMatchingPixels(topZone, ::isBlackInk)

        val totalRed = redMid + redTop
        val totalDark = darkMid + darkTop

        if (totalRed >= totalDark && redMid >= 2) {
            val grid = toInkGrid12x12(midZone, ::isRedInk)
            val dDiamond = gridDistance(grid, suitReferenceGrid("♦"))
            val dHeart = gridDistance(grid, suitReferenceGrid("♥"))
            return if (dDiamond <= dHeart) "♦" else "♥"
        }

        if (totalDark > totalRed && darkMid >= 2) {
            val grid = toInkGrid12x12(midZone, ::isBlackInk)
            val dSpade = gridDistance(grid, suitReferenceGrid("♠"))
            val dClub = gridDistance(grid, suitReferenceGrid("♣"))
            return if (dClub < dSpade) "♣" else "♠"
        }

        return null
    }

    // Assinatura estrutural (densidade por quadrante + largura/altura) para cada valor 2..A
    private data class RankProfile(
        val rank: String,
        val topLeft: Float,
        val topRight: Float,
        val mid: Float,
        val botLeft: Float,
        val botRight: Float,
        val widthRatio: Float,
        val heightRatio: Float
    )

    private val rankProfiles = listOf(
        RankProfile("2", 0.35f, 0.65f, 0.45f, 0.70f, 0.55f, 0.36f, 0.62f),
        RankProfile("3", 0.40f, 0.65f, 0.55f, 0.35f, 0.65f, 0.35f, 0.62f),
        RankProfile("4", 0.35f, 0.55f, 0.65f, 0.25f, 0.75f, 0.40f, 0.62f),
        RankProfile("5", 0.70f, 0.35f, 0.55f, 0.30f, 0.65f, 0.35f, 0.62f),
        RankProfile("6", 0.65f, 0.35f, 0.60f, 0.65f, 0.60f, 0.36f, 0.64f),
        RankProfile("7", 0.65f, 0.75f, 0.35f, 0.25f, 0.45f, 0.36f, 0.62f),
        RankProfile("8", 0.60f, 0.60f, 0.65f, 0.60f, 0.60f, 0.37f, 0.64f),
        RankProfile("9", 0.60f, 0.65f, 0.60f, 0.30f, 0.65f, 0.36f, 0.64f),
        RankProfile("10", 0.55f, 0.70f, 0.55f, 0.55f, 0.70f, 0.62f, 0.62f),
        RankProfile("J", 0.25f, 0.65f, 0.30f, 0.65f, 0.45f, 0.30f, 0.64f),
        RankProfile("Q", 0.60f, 0.60f, 0.45f, 0.60f, 0.70f, 0.44f, 0.64f),
        RankProfile("K", 0.70f, 0.55f, 0.55f, 0.70f, 0.65f, 0.42f, 0.64f),
        RankProfile("A", 0.45f, 0.45f, 0.65f, 0.65f, 0.65f, 0.41f, 0.64f)
    )

    private fun regionDensity(
        bitmap: Bitmap,
        x0Frac: Float,
        x1Frac: Float,
        y0Frac: Float,
        y1Frac: Float,
        pred: (Int, Int, Int) -> Boolean
    ): Float {
        val x0 = (bitmap.width * x0Frac).toInt().coerceIn(0, bitmap.width - 1)
        val x1 = (bitmap.width * x1Frac).toInt().coerceIn(x0 + 1, bitmap.width)
        val y0 = (bitmap.height * y0Frac).toInt().coerceIn(0, bitmap.height - 1)
        val y1 = (bitmap.height * y1Frac).toInt().coerceIn(y0 + 1, bitmap.height)

        var hit = 0
        var total = 0
        val stepX = ((x1 - x0) / 6).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 6).coerceAtLeast(1)

        for (y in y0 until y1 step stepY) {
            for (x in x0 until x1 step stepX) {
                val c = bitmap.getPixel(x, y)
                total++
                if (pred(Color.red(c), Color.green(c), Color.blue(c))) {
                    hit++
                }
            }
        }
        if (total == 0) return 0f
        return (hit.toFloat() / total.toFloat() * 2.2f).coerceIn(0f, 1f)
    }

    private fun detectRankByGrid(topZone: Bitmap, suit: String?): String? {
        val isRed = suit == "♥" || suit == "♦"
        val pred: (Int, Int, Int) -> Boolean = if (isRed) ::isRedInk else ::isBlackInk
        val active = countMatchingPixels(topZone, pred)
        if (active < 2) return null

        val tl = regionDensity(topZone, 0.0f, 0.5f, 0.0f, 0.4f, pred)
        val tr = regionDensity(topZone, 0.5f, 1.0f, 0.0f, 0.4f, pred)
        val mid = regionDensity(topZone, 0.15f, 0.85f, 0.35f, 0.65f, pred)
        val bl = regionDensity(topZone, 0.0f, 0.5f, 0.6f, 1.0f, pred)
        val br = regionDensity(topZone, 0.5f, 1.0f, 0.6f, 1.0f, pred)
        val wRatio = horizontalSpreadRatio(topZone, pred)
        val hRatio = verticalSpreadRatio(topZone, pred)

        var bestRank: String? = null
        var bestScore = Float.MAX_VALUE

        for (p in rankProfiles) {
            val score = abs(tl - p.topLeft) +
                abs(tr - p.topRight) +
                abs(mid - p.mid) +
                abs(bl - p.botLeft) +
                abs(br - p.botRight) +
                abs(wRatio - p.widthRatio) * 1.4f +
                abs(hRatio - p.heightRatio) * 1.0f

            if (score < bestScore) {
                bestScore = score
                bestRank = p.rank
            }
        }

        return bestRank
    }

    private fun countMatchingPixels(bitmap: Bitmap, predicate: (Int, Int, Int) -> Boolean): Int {
        var count = 0
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val c = bitmap.getPixel(x, y)
                if (predicate(Color.red(c), Color.green(c), Color.blue(c))) {
                    count++
                }
            }
        }
        return count
    }

    private fun horizontalSpreadRatio(bitmap: Bitmap, predicate: (Int, Int, Int) -> Boolean): Float {
        var minX = bitmap.width
        var maxX = -1
        val stepX = (bitmap.width / 28).coerceAtLeast(1)
        val stepY = (bitmap.height / 28).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val c = bitmap.getPixel(x, y)
                if (predicate(Color.red(c), Color.green(c), Color.blue(c))) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        if (maxX <= minX || bitmap.width <= 0) return 0f
        return ((maxX - minX).toFloat() / bitmap.width.toFloat()).coerceIn(0f, 1f)
    }

    private fun verticalSpreadRatio(bitmap: Bitmap, predicate: (Int, Int, Int) -> Boolean): Float {
        var minY = bitmap.height
        var maxY = -1
        val stepX = (bitmap.width / 28).coerceAtLeast(1)
        val stepY = (bitmap.height / 28).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val c = bitmap.getPixel(x, y)
                if (predicate(Color.red(c), Color.green(c), Color.blue(c))) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxY <= minY || bitmap.height <= 0) return 0f
        return ((maxY - minY).toFloat() / bitmap.height.toFloat()).coerceIn(0f, 1f)
    }
    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        if (cropped != paddedBitmap) {
            paddedBitmap.recycle()
        }
        return cropped
    }

    private fun isBitmapMostlyBlack(bitmap: Bitmap): Boolean {
        var darkSamples = 0
        var totalSamples = 0
        val stepX = (bitmap.width / 20).coerceAtLeast(1)
        val stepY = (bitmap.height / 20).coerceAtLeast(1)

        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r < 12 && g < 12 && b < 12) {
                    darkSamples++
                }
                totalSamples++
            }
        }
        return totalSamples > 0 && (darkSamples.toFloat() / totalSamples) > 0.92f
    }

    private fun stopProjectionResources() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        captureWidth = 0
        captureHeight = 0
    }

    private fun showToast(message: String) {
        uiHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun createFloatingWindow() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val panelWidthDp = 188

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 14
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E612161F"))
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3B82F6"))
            }
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(5))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(panelWidthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(2))
        }

        val titleAndCount = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        counterTextView = titleAndCount

        val snapBtn = Button(this).apply {
            text = "FOTO"
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = makePillDrawable(
                fillColor = "#0F766E",
                strokeColor = "#5EEAD4",
                radiusDp = 5
            )
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            val lp = LinearLayout.LayoutParams(dpToPx(32), dpToPx(18))
            lp.marginEnd = dpToPx(6)
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                testSingleFrameCapture()
            }
        }

        val minimizeBtn = Button(this).apply {
            text = "—"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            background = makePillDrawable(
                fillColor = "#2563EB",
                strokeColor = "#93C5FD",
                radiusDp = 5
            )
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            val lp = LinearLayout.LayoutParams(dpToPx(24), dpToPx(18))
            lp.marginEnd = dpToPx(8)
            layoutParams = lp
            setPadding(0, 0, 0, 0)
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            background = makePillDrawable(
                fillColor = "#991B1B",
                strokeColor = "#FCA5A5",
                radiusDp = 5
            )
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(18))
            setPadding(0, 0, 0, 0)
        }

        header.addView(titleAndCount)
        header.addView(snapBtn)
        header.addView(minimizeBtn)
        header.addView(closeBtn)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(panelWidthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val colCount = displaySuits.size
        val horizontalGapDp = 3
        val verticalGapDp = 1
        val totalHorizontalGapDp = horizontalGapDp * 2 * colCount
        val btnWidthDp = ((panelWidthDp - totalHorizontalGapDp) / colCount).coerceAtLeast(36)
        val btnHeightDp = 17

        val suitHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(2)
            }
        }

        for (suit in displaySuits) {
            val tv = TextView(this).apply {
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E2E8F0"))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(btnWidthDp),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(horizontalGapDp), 0, dpToPx(horizontalGapDp), 0)
                }
            }
            suitCounterViews[suit] = tv
            suitHeaderRow.addView(tv)
        }
        body.addView(suitHeaderRow)

        val grid = GridLayout(this).apply {
            columnCount = colCount
            rowCount = displayRanks.size
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (rank in displayRanks) {
            for (suit in displaySuits) {
                val cardId = "$rank$suit"
                val btn = Button(this).apply {
                    text = cardId
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    minimumWidth = 0
                    minWidth = 0
                    minimumHeight = 0
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dpToPx(btnWidthDp)
                        height = dpToPx(btnHeightDp)
                        setMargins(
                            dpToPx(horizontalGapDp),
                            dpToPx(verticalGapDp),
                            dpToPx(horizontalGapDp),
                            dpToPx(verticalGapDp)
                        )
                    }
                    setOnClickListener {
                        toggleCard(cardId)
                    }
                }
                cardButtons[cardId] = btn
                grid.addView(btn)
                updateCardButtonStyle(cardId)
            }
        }

        body.addView(grid)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(3)
            }
        }

        val undoBtn = Button(this).apply {
            text = "DESFAZER"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(22),
                1f
            ).apply {
                marginEnd = dpToPx(3)
            }
            setPadding(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
            setOnClickListener {
                undoLastAction()
            }
        }
        undoButton = undoBtn

        val resetBtn = Button(this).apply {
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(22),
                1.15f
            ).apply {
                marginStart = dpToPx(3)
            }
            setPadding(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
            setOnClickListener {
                handleResetClick()
            }
        }
        resetButton = resetBtn

        actionsRow.addView(undoBtn)
        actionsRow.addView(resetBtn)
        body.addView(actionsRow)

        bodyContainer = body
        root.addView(header)
        root.addView(body)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        minimizeBtn.setOnClickListener {
            isMinimized = !isMinimized
            bodyContainer?.visibility = if (isMinimized) View.GONE else View.VISIBLE
            minimizeBtn.text = if (isMinimized) "+" else "—"
        }

        closeBtn.setOnClickListener {
            stopSelf()
        }

        updateResetButtonAppearance()
        updateUndoButtonAppearance()
        updateCounterText()
        floatingView = root
        windowManager.addView(floatingView, layoutParams)
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(playedCards.toSet())
        if (undoStack.size > 30) {
            undoStack.removeFirst()
        }
        updateUndoButtonAppearance()
    }

    private fun undoLastAction() {
        if (undoStack.isEmpty()) return
        val previousState = undoStack.removeLast()
        playedCards.clear()
        playedCards.addAll(previousState)
        cardStorage.savePlayedCards(playedCards)

        for (id in cardButtons.keys) {
            updateCardButtonStyle(id)
        }
        updateCounterText()
        updateUndoButtonAppearance()
    }

    private fun updateUndoButtonAppearance() {
        val btn = undoButton ?: return
        val hasHistory = undoStack.isNotEmpty()
        btn.isEnabled = hasHistory
        if (hasHistory) {
            btn.alpha = 1.0f
            btn.background = makePillDrawable(
                fillColor = "#1E3A8A",
                strokeColor = "#93C5FD",
                radiusDp = 5
            )
        } else {
            btn.alpha = 0.45f
            btn.background = makePillDrawable(
                fillColor = "#1F2937",
                strokeColor = "#4B5563",
                radiusDp = 5
            )
        }
    }

    private fun handleResetClick() {
        if (!pendingClearConfirmation) {
            pendingClearConfirmation = true
            updateResetButtonAppearance()
            uiHandler.removeCallbacks(clearTimeoutRunnable)
            uiHandler.postDelayed(clearTimeoutRunnable, 4000L)
        } else {
            uiHandler.removeCallbacks(clearTimeoutRunnable)
            pendingClearConfirmation = false
            if (playedCards.isNotEmpty()) {
                pushUndoSnapshot()
                playedCards.clear()
                cardStorage.clearPlayedCards()
                for (id in cardButtons.keys) {
                    updateCardButtonStyle(id)
                }
                updateCounterText()
            }
            updateResetButtonAppearance()
        }
    }

    private fun updateResetButtonAppearance() {
        val btn = resetButton ?: return
        if (pendingClearConfirmation) {
            btn.text = "CONFIRMAR?"
            btn.background = makePillDrawable(
                fillColor = "#D97706",
                strokeColor = "#FDE68A",
                radiusDp = 5
            )
        } else {
            val remaining = 52 - playedCards.size
            btn.text = "LIMPAR ($remaining)"
            btn.background = makePillDrawable(
                fillColor = "#16A34A",
                strokeColor = "#86EFAC",
                radiusDp = 5
            )
        }
    }

    private fun toggleCard(cardId: String) {
        pushUndoSnapshot()
        if (playedCards.contains(cardId)) {
            playedCards.remove(cardId)
        } else {
            playedCards.add(cardId)
        }
        cardStorage.savePlayedCards(playedCards)
        updateCardButtonStyle(cardId)
        updateCounterText()
    }

    private fun updateCardButtonStyle(cardId: String) {
        val btn = cardButtons[cardId] ?: return
        val isPlayed = playedCards.contains(cardId)

        if (isPlayed) {
            btn.background = makePillDrawable(
                fillColor = "#DC2626",
                strokeColor = "#FECACA",
                radiusDp = 5
            )
            btn.setTextColor(Color.WHITE)
            btn.alpha = 1.0f
        } else {
            btn.background = makePillDrawable(
                fillColor = "#15803D",
                strokeColor = "#86EFAC",
                radiusDp = 5
            )
            btn.setTextColor(Color.WHITE)
            btn.alpha = 1.0f
        }
    }

    private fun makePillDrawable(
        fillColor: String,
        strokeColor: String,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            cornerRadius = dpToPx(radiusDp).toFloat()
            setStroke(dpToPx(1), Color.parseColor(strokeColor))
        }
    }

    private fun remainingForSuit(suit: String): Int {
        val playedInSuit = playedCards.count { it.endsWith(suit) }
        return (displayRanks.size - playedInSuit).coerceAtLeast(0)
    }

    private fun updateCounterText() {
        val remaining = 52 - playedCards.size
        val diag = if (lastDiagnosticText.isNotEmpty()) " • $lastDiagnosticText" else ""
        counterTextView?.text = "Copas • $remaining/52 • $lastCaptureStatus$diag"
        for (suit in displaySuits) {
            suitCounterViews[suit]?.text = "$suit ${remainingForSuit(suit)}"
        }
        if (!pendingClearConfirmation) {
            updateResetButtonAppearance()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(clearTimeoutRunnable)
        stopProjectionResources()
        if (isProjectionCallbackRegistered) {
            mediaProjection?.unregisterCallback(projectionCallback)
            isProjectionCallbackRegistered = false
        }
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
        floatingView?.let {
            windowManager.removeView(it)
            floatingView = null
        }
    }
}
