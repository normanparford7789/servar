package com.touchmirror.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Floating overlay service for the Controller device.
 *
 * Responsibilities:
 * 1. Show a draggable floating button (pause/resume mirroring).
 * 2. Capture ALL touch events via a full-screen transparent overlay.
 * 3. Send captured events to SocketService.
 * 4. Re-inject events locally via TouchCaptureService (Accessibility) so the
 *    underlying app still receives them.
 */
class FloatingMenuService : Service() {

    companion object {
        private const val TAG = "FloatingMenuService"
        const val ACTION_START = "com.touchmirror.FLOAT_START"
        const val ACTION_STOP = "com.touchmirror.FLOAT_STOP"
        const val ACTION_TOGGLE_MIRROR = "com.touchmirror.TOGGLE_MIRROR"
        private const val NOTIF_ID = 1003
    }

    private lateinit var windowManager: WindowManager
    private var fabView: View? = null
    private var overlayView: View? = null
    private var fabIcon: ImageView? = null

    private var isMirroring = true
    private var screenWidth = 1080
    private var screenHeight = 1920

    // Batch touch events for efficiency
    private val touchBatch = mutableListOf<TouchEvent>()
    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batchJob: Job? = null

    // Track active gesture for swipe detection
    private val pointerPaths = mutableMapOf<Int, MutableList<TouchPointer>>()
    private val gestureStart = mutableMapOf<Int, Long>()

    private val socketStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(SocketService.EXTRA_STATUS) ?: return
            updateFabColor(status)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        getScreenSize()
        registerReceiver(socketStatusReceiver, IntentFilter(SocketService.BROADCAST_STATUS))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_START -> {
                showFab()
                showCaptureOverlay()
            }
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_MIRROR -> toggleMirror()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeFab()
        removeCaptureOverlay()
        unregisterReceiver(socketStatusReceiver)
        batchScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Floating FAB ──────────────────────────────────────────────────────────

    private fun showFab() {
        if (fabView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.floating_menu, null)
        fabIcon = view.findViewById<CardView>(R.id.cvFloating).let {
            view.findViewById(R.id.ivFloatingIcon)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }

        // Drag + click
        var startRawX = 0f; var startRawY = 0f
        var startParamsX = 0; var startParamsY = 0
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startParamsX = params.x
                    startParamsY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        params.x = (startParamsX - dx).toInt()
                        params.y = (startParamsY + dy).toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleMirror()
                    true
                }
                else -> false
            }
        }

        fabView = view
        windowManager.addView(view, params)
    }

    private fun removeFab() {
        fabView?.let { windowManager.removeView(it) }
        fabView = null
    }

    private fun toggleMirror() {
        isMirroring = !isMirroring
        updateFabIcon()
        // Tell SocketService
        val intent = Intent(this, SocketService::class.java).apply {
            action = if (isMirroring) "MIRROR_ON" else "MIRROR_OFF"
        }
        // Use broadcast instead
        val b = Intent(SocketService.BROADCAST_MIRROR_STATE).apply {
            putExtra(SocketService.EXTRA_MIRRORING, isMirroring)
        }
        sendBroadcast(b)
        Log.d(TAG, "Mirroring toggled: $isMirroring")
    }

    private fun updateFabIcon() {
        fabIcon?.setImageResource(
            if (isMirroring) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun updateFabColor(status: String) {
        // Visual feedback via tint — runs on main thread via post
        fabView?.post {
            val color = when (status) {
                "connected" -> 0xFF4CAF50.toInt()
                "connecting" -> 0xFFFFA726.toInt()
                else -> 0xFFEF5350.toInt()
            }
            fabView?.findViewById<CardView>(R.id.cvFloating)?.setCardBackgroundColor(color)
        }
    }

    // ── Full-screen Touch Capture Overlay ──────────────────────────────────────

    private fun showCaptureOverlay() {
        if (overlayView != null) return

        val overlay = View(this)
        overlay.setBackgroundColor(0x00000000) // fully transparent

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_TOUCH_MODAL allows touches outside the overlay to pass through
            // We capture every touch but also re-inject it
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            alpha = 0.01f // nearly invisible but still receives touches
        }

        overlay.setOnTouchListener { _, event ->
            if (isMirroring) onOverlayTouch(event)
            // Always re-inject so the underlying app gets the touch
            reInjectTouch(event)
            false // don't consume — let the event continue naturally
        }

        overlayView = overlay
        windowManager.addView(overlay, params)
    }

    private fun removeCaptureOverlay() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }

    // ── Touch Capture & Send ──────────────────────────────────────────────────

    private fun onOverlayTouch(event: MotionEvent) {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"
            else -> return
        }

        val pointers = mutableListOf<TouchPointer>()
        for (i in 0 until event.pointerCount) {
            pointers.add(
                TouchPointer(
                    id = event.getPointerId(i),
                    x = (event.getX(i) / screenWidth).coerceIn(0f, 1f),
                    y = (event.getY(i) / screenHeight).coerceIn(0f, 1f)
                )
            )
        }

        val touchEvent = TouchEvent(action, pointers)
        enqueueTouchEvent(touchEvent)
    }

    private fun enqueueTouchEvent(event: TouchEvent) {
        synchronized(touchBatch) {
            touchBatch.add(event)
        }
        // Debounce: flush batch every 16ms (~60fps)
        batchJob?.cancel()
        batchJob = batchScope.launch {
            delay(16)
            flushBatch()
        }
        // Immediate flush for down/up events
        if (event.action == "down" || event.action == "up") {
            batchJob?.cancel()
            batchScope.launch { flushBatch() }
        }
    }

    private fun flushBatch() {
        val batch: List<TouchEvent>
        synchronized(touchBatch) {
            if (touchBatch.isEmpty()) return
            batch = touchBatch.toList()
            touchBatch.clear()
        }
        // Send via local broadcast — SocketService picks it up
        val intent = Intent("com.touchmirror.SEND_TOUCH_BATCH").apply {
            putExtra("batch_json", org.json.JSONArray(batch.map { it.toJson() }).toString())
        }
        sendBroadcast(intent)
    }

    // ── Re-injection (so underlying app receives the touch) ───────────────────

    private fun reInjectTouch(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val svc = TouchCaptureService.instance ?: return
        val action = event.actionMasked
        // Only re-inject tap/click gestures; move events are frequent and not needed
        if (action == MotionEvent.ACTION_UP) {
            val pointer = TouchPointer(0, event.x, event.y) // absolute coords for re-injection
            try {
                svc.injectTap(event.x, event.y, 50L)
            } catch (e: Exception) {
                Log.w(TAG, "Re-inject failed: ${e.message}")
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun getScreenSize() {
        try {
            val wm = getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screen size error: $e")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                SocketService.CHANNEL_ID, "TouchMirror",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingMenuService::class.java).apply { action = ACTION_TOGGLE_MIRROR },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SocketService.CHANNEL_ID)
            .setContentTitle("TouchMirror Controller")
            .setContentText("Capturing touch events…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Pause/Resume", togglePi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
