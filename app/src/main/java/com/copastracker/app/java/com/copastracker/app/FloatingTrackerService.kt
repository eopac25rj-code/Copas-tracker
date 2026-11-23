package com.copastracker.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.TextView

class FloatingTrackerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var cardStorage: CardStorage
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val playedCards = mutableSetOf<String>()
    private val cardButtons = mutableMapOf<String, Button>()
    private var counterTextView: TextView? = null
    private var bodyContainer: View? = null
    private var resetButton: Button? = null
    private var isMinimized = false

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
        createFloatingWindow()
    }

    private fun createFloatingWindow() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 80
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E612161F"))
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3B82F6"))
            }
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(196),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(4))
        }

        val titleAndCount = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        counterTextView = titleAndCount

        val minimizeBtn = Button(this).apply {
            text = "—"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = makePillDrawable(
                fillColor = "#2563EB",
                strokeColor = "#93C5FD",
                radiusDp = 6
            )
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            val lp = LinearLayout.LayoutParams(dpToPx(28), dpToPx(24))
            lp.marginEnd = dpToPx(14)
            layoutParams = lp
            setPadding(0, 0, 0, 0)
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = makePillDrawable(
                fillColor = "#991B1B",
                strokeColor = "#FCA5A5",
                radiusDp = 6
            )
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(24))
            setPadding(0, 0, 0, 0)
        }

        header.addView(titleAndCount)
        header.addView(minimizeBtn)
        header.addView(closeBtn)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(196),
                dpToPx(148)
            )
        }

        val grid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = true
        }

        for (rank in CardStorage.RANKS) {
            for (suit in CardStorage.SUITS) {
                val cardId = "$rank$suit"
                val btn = Button(this).apply {
                    text = cardId
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    minimumWidth = 0
                    minWidth = 0
                    minimumHeight = 0
                    minHeight = 0
                    setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dpToPx(42)
                        height = dpToPx(28)
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

        scrollView.addView(grid)
        body.addView(scrollView)

        val resetBtn = Button(this).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(30)
            )
            lp.topMargin = dpToPx(4)
            layoutParams = lp
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
            setOnClickListener {
                handleResetClick()
            }
        }
        resetButton = resetBtn
        updateResetButtonAppearance()
        body.addView(resetBtn)

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

        updateCounterText()
        floatingView = root
        windowManager.addView(floatingView, layoutParams)
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
            playedCards.clear()
            cardStorage.clearPlayedCards()
            for (id in cardButtons.keys) {
                updateCardButtonStyle(id)
            }
            updateCounterText()
            updateResetButtonAppearance()
        }
    }

    private fun updateResetButtonAppearance() {
        val btn = resetButton ?: return
        if (pendingClearConfirmation) {
            btn.text = "Confirmar limpar?"
            btn.background = makePillDrawable(
                fillColor = "#D97706",
                strokeColor = "#FDE68A",
                radiusDp = 6
            )
        } else {
            btn.text = "Limpar (52)"
            btn.background = makePillDrawable(
                fillColor = "#16A34A",
                strokeColor = "#86EFAC",
                radiusDp = 6
            )
        }
    }

    private fun toggleCard(cardId: String) {
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
                radiusDp = 6
            )
            btn.setTextColor(Color.WHITE)
            btn.alpha = 1.0f
        } else {
            btn.background = makePillDrawable(
                fillColor = "#15803D",
                strokeColor = "#86EFAC",
                radiusDp = 6
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

    private fun updateCounterText() {
        val remaining = 52 - playedCards.size
        counterTextView?.text = "Copas • $remaining/52"
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(clearTimeoutRunnable)
        super.onDestroy()
        floatingView?.let {
            windowManager.removeView(it)
            floatingView = null
        }
    }
}
