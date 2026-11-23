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

    private val displayRanks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")
    private val displaySuits = listOf("♠", "♥", "♦", "♣")

    private var pendingClearConfirmation = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clearTimeoutRunnable = Runnable {
        pendingClearConfirmation = false
        updateResetButtonAppearance()
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
                showToast("Permissão não retornou dados (code=$resultCode)")
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
            .setContentText("Teste de captura de tela pronto")
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
            showToast("Erro no serviço foreground: ${e.javaClass.simpleName}")
            return
        }

        try {
            stopProjectionResources()
            mediaProjection?.stop()
            mediaProjection = null

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = projectionManager.getMediaProjection(resultCode, dataIntent)
            if (mp == null) {
                showToast("MediaProjection veio nulo")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopProjectionResources()
                    }
                }, uiHandler)
            }

            mediaProjection = mp
            showToast("Captura pronta! Toque em FOTO")
        } catch (e: Exception) {
            showToast("Erro ao pegar projeção: ${e.javaClass.simpleName}")
        }
    }

    private fun testSingleFrameCapture() {
        val mp = mediaProjection
        if (mp == null) {
            showToast("Captura ainda não ativa — toque em Ativar Captura")
            return
        }
        if (isCapturingFrame) {
            showToast("Capturando frame...")
            return
        }

        isCapturingFrame = true

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        stopProjectionResources()

        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val display = mp.createVirtualDisplay(
                "CopasTestCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            imageReader = reader
            virtualDisplay = display

            uiHandler.postDelayed({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image, width, height)
                        image.close()

                        val isBlack = isBitmapMostlyBlack(bitmap)
                        bitmap.recycle()

                        if (isBlack) {
                            showToast("Tela preta (bloqueado pelo jogo)")
                        } else {
                            showToast("Captura OK! Tela visível")
                        }
                    } else {
                        showToast("Sem frame ainda, tente FOTO de novo")
                    }
                } catch (e: Exception) {
                    showToast("Erro ao ler frame: ${e.javaClass.simpleName}")
                } finally {
                    stopProjectionResources()
                    isCapturingFrame = false
                }
            }, 350L)
        } catch (e: Exception) {
            stopProjectionResources()
            isCapturingFrame = false
            showToast("Erro virtualDisplay: ${e.javaClass.simpleName}")
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
            textSize = 10f
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
                LinearLayout.LayoutParams
