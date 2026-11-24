package com.copastracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
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
import java.io.File
import java.io.FileOutputStream
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
    private var isCapturingFrame = false
    private var lastCaptureStatus = "OFF"

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
            updateCounterText()
            stopProjectionResources()
            mediaProjection = null
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
            updateCounterText()
            showToast("Erro foreground: ${e.javaClass.simpleName}")
            return
        }

        try {
            stopProjectionResources()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = projectionManager.getMediaProjection(resultCode, dataIntent)
            if (mp == null) {
                lastCaptureStatus = "MP_NULL"
                updateCounterText()
                showToast("MediaProjection veio nulo")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mp.registerCallback(projectionCallback, uiHandler)
            }

            mediaProjection = mp
            lastCaptureStatus = "ON"
            updateCounterText()
            showToast("Captura pronta! Toque em FOTO")
        } catch (e: Exception) {
            lastCaptureStatus = "ERR_MP"
            updateCounterText()
            showToast("Erro projeção: ${e.javaClass.simpleName}")
        }
    }

    private fun testSingleFrameCapture() {
        val mp = mediaProjection
        if (mp == null) {
            lastCaptureStatus = "OFF"
            updateCounterText()
            showToast("Sem captura ativa! Abra o app e toque em Ativar Captura")
            return
        }
        if (isCapturingFrame) {
            lastCaptureStatus = "LENDO..."
            updateCounterText()
            return
        }

        isCapturingFrame = true
        lastCaptureStatus = "LENDO..."
        updateCounterText()

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(72)

        stopProjectionResources()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mp.registerCallback(projectionCallback, uiHandler)
            }

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val display = mp.createVirtualDisplay(
                "CopasTestCapture",
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
                isCapturingFrame = false
                lastCaptureStatus = "VD_NULL"
                updateCounterText()
                showToast("VirtualDisplay retornou nulo")
                return
            }

            imageReader = reader
            virtualDisplay = display

            uiHandler.postDelayed({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image, width, height)
                        image.close()

                        val isBlack = isBitmapMostlyBlack(bitmap)
                        if (isBlack) {
                            bitmap.recycle()
                            lastCaptureStatus = "PRETA"
                            showToast("Tela preta (bloqueado pelo jogo)")
                        } else {
                            val stamp = System.currentTimeMillis()
                            val fullSaved = saveBitmapToGallery(bitmap, "mesa_$stamp.png")
                            val centerBitmap = cropCenterRegion(bitmap)
                            bitmap.recycle()

                            val centerSaved = if (centerBitmap != null) {
                                processCenterBitmapForCards(centerBitmap)
                                val name = saveBitmapToGallery(centerBitmap, "centro_$stamp.png")
                                centerBitmap.recycle()
                                name
                            } else {
                                null
                            }

                            if (fullSaved != null && centerSaved != null) {
                                lastCaptureStatus = "LIDO"
                            } else if (fullSaved != null) {
                                lastCaptureStatus = "SALVO"
                            } else {
                                lastCaptureStatus = "ERR_SAVE"
                                showToast("Captura OK, mas falhou ao salvar imagem")
                            }
                        }
                    } else {
                        lastCaptureStatus = "SEM_FRAME"
                        showToast("Sem frame ainda, toque em FOTO de novo")
                    }
                } catch (e: Exception) {
                    lastCaptureStatus = "ERR_FRAME"
                    showToast("Erro ao ler frame: ${e.javaClass.simpleName}")
                } finally {
                    stopProjectionResources()
                    isCapturingFrame = false
                    updateCounterText()
                }
            }, 350L)
        } catch (e: Exception) {
            stopProjectionResources()
            isCapturingFrame = false
            lastCaptureStatus = "ERR_VD"
            updateCounterText()
            showToast("Erro VD: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    private fun cropCenterRegion(source: Bitmap): Bitmap? {
        if (source.width <= 1 || source.height <= 1) return null
        val left = (source.width * 0.30f).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * 0.20f).toInt().coerceIn(0, source.height - 1)
        val right = (source.width * 0.70f).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * 0.78f).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private enum class CardSlot { TOP, LEFT, RIGHT, BOTTOM }

    private data class QuadF(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun processCenterBitmapForCards(centerBitmap: Bitmap) {
        val currentTrickCards = mutableSetOf<String>()

        CardSlot.entries.forEach { slot ->
            val rawCrop = cropCardSlot(centerBitmap, slot) ?: return@forEach
            val orientedCrop = normalizeSlotOrientation(rawCrop, slot)
            if (orientedCrop != rawCrop) {
                rawCrop.recycle()
            }

            if (hasWhiteCardSurface(orientedCrop)) {
                val cardId = recognizeCardId(orientedCrop)
                if (cardId != null) {
                    currentTrickCards.add(cardId)
                }
            }
            orientedCrop.recycle()
        }

        if (currentTrickCards.isEmpty()) {
            lastSeenTrickCards = emptySet()
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
            showToast("Marcado: ${newCards.joinToString(", ")}")
        } else {
            showToast("Mesa: ${currentTrickCards.joinToString(", ")}")
        }
    }

    private fun cropCardSlot(center: Bitmap, slot: CardSlot): Bitmap? {
        val w = center.width
        val h = center.height
        if (w <= 4 || h <= 4) return null

        val (l, t, r, b) = when (slot) {
            CardSlot.TOP -> QuadF(0.35f, 0.03f, 0.65f, 0.44f)
            CardSlot.LEFT -> QuadF(0.05f, 0.34f, 0.42f, 0.68f)
            CardSlot.RIGHT -> QuadF(0.58f, 0.34f, 0.95f, 0.68f)
            CardSlot.BOTTOM -> QuadF(0.36f, 0.56f, 0.64f, 0.98f)
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
                if (Color.red(c) > 210 && Color.green(c) > 210 && Color.blue(c) > 210) {
                    whitePixels++
                }
            }
        }
        return sampled > 0 && (whitePixels.toFloat() / sampled.toFloat()) > 0.20f
    }

    private fun recognizeCardId(cardBitmap: Bitmap): String? {
        val w = cardBitmap.width
        val h = cardBitmap.height
        if (w < 8 || h < 8) return null

        val topZone = Bitmap.createBitmap(
            cardBitmap,
            (w * 0.25f).toInt().coerceIn(0, w - 1),
            (h * 0.08f).toInt().coerceIn(0, h - 1),
            (w * 0.50f).toInt().coerceAtLeast(1).coerceAtMost(w - (w * 0.25f).toInt()),
            (h * 0.34f).toInt().coerceAtLeast(1).coerceAtMost(h - (h * 0.08f).toInt())
        )
        val midZone = Bitmap.createBitmap(
            cardBitmap,
            (w * 0.25f).toInt().coerceIn(0, w - 1),
            (h * 0.42f).toInt().coerceIn(0, h - 1),
            (w * 0.50f).toInt().coerceAtLeast(1).coerceAtMost(w - (w * 0.25f).toInt()),
            (h * 0.38f).toInt().coerceAtLeast(1).coerceAtMost(h - (h * 0.42f).toInt())
        )

        val suit = detectSuit(midZone, topZone)
        val rank = detectRank(topZone, suit)

        topZone.recycle()
        midZone.recycle()

        return if (rank != null && suit != null) "$rank$suit" else null
    }

    private fun detectSuit(midZone: Bitmap, topZone: Bitmap): String? {
        val redMid = countMatchingPixels(midZone) { r, g, b -> r > 165 && g < 85 && b < 85 }
        val darkMid = countMatchingPixels(midZone) { r, g, b -> r < 65 && g < 65 && b < 65 }

        val redTop = countMatchingPixels(topZone) { r, g, b -> r > 165 && g < 85 && b < 85 }
        val darkTop = countMatchingPixels(topZone) { r, g, b -> r < 65 && g < 65 && b < 65 }

        val totalRed = redMid + redTop
        val totalDark = darkMid + darkTop

        return if (totalRed >= totalDark && totalRed > 6) {
            val midWidthRatio = horizontalSpreadRatio(midZone) { r, g, b -> r > 165 && g < 85 && b < 85 }
            if (midWidthRatio > 0.48f) "♦" else "♥"
        } else if (totalDark > totalRed && totalDark > 6) {
            val midTopBottomBalance = verticalBalanceRatio(midZone) { r, g, b -> r < 65 && g < 65 && b < 65 }
            if (midTopBottomBalance < 0.46f) "♠" else "♣"
        } else {
            null
        }
    }

    private fun detectRank(topZone: Bitmap, suit: String?): String? {
        val isRed = suit == "♥" || suit == "♦"
        val matchFn: (Int, Int, Int) -> Boolean = if (isRed) {
            { r, g, b -> r > 165 && g < 85 && b < 85 }
        } else {
            { r, g, b -> r < 65 && g < 65 && b < 65 }
        }

        val activeCount = countMatchingPixels(topZone, matchFn)
        if (activeCount < 4) return null

        val spreadX = horizontalSpreadRatio(topZone, matchFn)
        val spreadY = verticalSpreadRatio(topZone, matchFn)
        val vBalance = verticalBalanceRatio(topZone, matchFn)

        return when {
            spreadX > 0.56f && spreadY > 0.50f -> "10"
            spreadX > 0.46f && vBalance in 0.42f..0.58f -> "Q"
            spreadX > 0.44f && vBalance > 0.52f -> "K"
            spreadX < 0.34f && vBalance > 0.52f -> "J"
            spreadX < 0.38f && vBalance < 0.46f -> "A"
            vBalance < 0.44f -> "7"
            vBalance > 0.56f -> "9"
            spreadX > 0.40f && spreadY > 0.48f -> "8"
            activeCount > 45 -> "6"
            activeCount > 32 -> "5"
            activeCount > 22 -> "4"
            activeCount > 14 -> "3"
            else -> "2"
        }
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

    private fun verticalBalanceRatio(bitmap: Bitmap, predicate: (Int, Int, Int) -> Boolean): Float {
        var topWeight = 0
        var bottomWeight = 0
        val midY = bitmap.height / 2
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val c = bitmap.getPixel(x, y)
                if (predicate(Color.red(c), Color.green(c), Color.blue(c))) {
                    if (y < midY) topWeight++ else bottomWeight++
                }
            }
        }
        val total = topWeight + bottomWeight
        if (total == 0) return 0.5f
        return (bottomWeight.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
    private fun saveBitmapToGallery(bitmap: Bitmap, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/CopasTracker"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        resolver.delete(uri, null, null)
                        return null
                    }
                    out.flush()
                } ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                fileName
            } else {
                @Suppress("DEPRECATION")
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val outDir = File(baseDir, "CopasTracker")
                if (!outDir.exists() && !outDir.mkdirs()) {
                    return null
                }
                val file = File(outDir, fileName)
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        return null
                    }
                    out.flush()
                }
                fileName
            }
        } catch (_: Exception) {
            null
        }
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
        counterTextView?.text = "Copas • $remaining/52 • $lastCaptureStatus"
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
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
        floatingView?.let {
            windowManager.removeView(it)
            floatingView = null
        }
    }
}
