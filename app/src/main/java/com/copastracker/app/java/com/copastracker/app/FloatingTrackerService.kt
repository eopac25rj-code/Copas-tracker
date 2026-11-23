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
import android.widget.TextView

class FloatingTrackerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var cardStorage: CardStorage
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val playedCards = mutableSetOf<String>()
    private val undoStack = ArrayDeque<Set<String>>()
    private val cardButtons = mutableMapOf<String, Button>()
    private var counterTextView: TextView? = null
    private var bodyContainer: View? = null
    private var resetButton: Button? = null
    private var undoButton: Button? = null
    private var isMinimized = false

    // Ordem com o Ás (A) por último
    private val displayRanks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")
    // Menos naipes aparecendo para deixar mais compacto e com botões maiores
    private val displaySuits = listOf("♠", "♥", "♦")

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

        val panelWidthDp = 168

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 16
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E612161F"))
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3B82F6"))
            }
            setPadding(dpToPx(6), dpToPx(5), dpToPx(6), dpToPx(6))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(panelWidthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(3))
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
            val lp = LinearLayout.LayoutParams(dpToPx(24), dpToPx(20))
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
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(20))
            setPadding(0, 0, 0, 0)
        }

        header.addView(titleAndCount)
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
        val grid = GridLayout(this).apply {
            columnCount = colCount
            rowCount = displayRanks.size
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Vão maior entre os naipes (esquerda/direita) e altura compacta
        val horizontalGapDp = 5
        val verticalGapDp = 2
        val totalHorizontalGapDp = horizontalGapDp * 2 * colCount
        val btnWidthDp = ((panelWidthDp - totalHorizontalGapDp) / colCount).coerceAtLeast(32)
        val btnHeightDp = 18

        for (rank in displayRanks) {
            for (suit in displaySuits) {
                val cardId = "$rank$suit"
                val btn = Button(this).apply {
                    text = cardId
                    textSize = 9f
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
                topMargin = dpToPx(4)
            }
        }

        val undoBtn = Button(this).apply {
            text = "Desfazer"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(24),
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
                dpToPx(24),
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
            btn.text = "Confirmar?"
            btn.background = makePillDrawable(
                fillColor = "#D97706",
                strokeColor = "#FDE68A",
                radiusDp = 5
            )
        } else {
            btn.text = "Limpar (52)"
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
