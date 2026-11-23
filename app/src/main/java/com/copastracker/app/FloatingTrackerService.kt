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
    private val suitCounterViews = mutableMapOf<String, TextView>()
    private var counterTextView: TextView? = null
    private var bodyContainer: View? = null
    private var resetButton: Button? = null
    private var undoButton: Button? = null
    private var isMinimized = false

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
        createFloatingWindow()
    }

    private fun createFloatingWindow() {
        val overlayType = if (

