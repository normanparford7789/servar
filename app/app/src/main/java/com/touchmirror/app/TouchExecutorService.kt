package com.touchmirror.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Foreground service on Target devices.
 *
 * Receives BROADCAST_TOUCH intents from SocketService and executes them
 * as root shell commands using RootHelper.
 */
class TouchExecutorService : Service() {

    companion object {
        private const val TAG = "TouchExecutorService"
        const val ACTION_START = "com.touchmirror.EXECUTOR_START"
        const val ACTION_STOP = "com.touchmirror.EXECUTOR_STOP"
        private const val NOTIF_ID = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var screenWidth = 1080
    private var screenHeight = 1920

    private val touchReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(SocketService.EXTRA_TOUCH_JSON) ?: return
            scope.launch { executeTouchJson(json) }
        }
    }

    private val mirrorStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // If mirror paused, we simply ignore incoming touches (SocketService stops sending)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getScreenSize()
        registerReceiver(touchReceiver, IntentFilter(SocketService.BROADCAST_TOUCH))
        registerReceiver(mirrorStateReceiver, IntentFilter(SocketService.BROADCAST_MIRROR_STATE))

        // Open persistent root shell
        scope.launch {
            val hasRoot = RootHelper.openShell()
            if (!hasRoot) {
                Log.w(TAG, "Root shell unavailable — falling back to execOnce")
                RootHelper.checkRoot()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "TouchExecutorService started")
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(touchReceiver)
        unregisterReceiver(mirrorStateReceiver)
        RootHelper.closeShell()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Touch Execution ────────────────────────────────────────────────────────

    private fun executeTouchJson(json: String) {
        try {
            val event = TouchEvent.fromJson(JSONObject(json))
            executeEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute touch: ${e.message}")
        }
    }

    private fun executeEvent(event: TouchEvent) {
        if (event.pointers.isEmpty()) return
        // For simplicity we handle the primary pointer (id=0)
        // Multi-touch support via separate input tool calls
        val pointer = event.pointers.firstOrNull { it.id == 0 } ?: event.pointers.first()
        val (absX, absY) = RootHelper.denormalize(pointer.x, pointer.y, screenWidth, screenHeight)

        when (event.action) {
            "down", "up" -> RootHelper.tap(absX, absY)
            "move" -> {
                // For move events, we do minimal tap (stateless touch injection via `input` command)
                // A full swipe is handled by grouping down+move+up sequences
                // This is a simplified approach that works well for most tap-based mirroring
            }
            "swipe" -> {
                // Extended: if pointers has 2 entries it's a swipe start/end
                if (event.pointers.size >= 2) {
                    val p2 = event.pointers[1]
                    val (x2, y2) = RootHelper.denormalize(p2.x, p2.y, screenWidth, screenHeight)
                    RootHelper.swipe(absX, absY, x2, y2, 100)
                }
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
            Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get screen size: ${e.message}")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                SocketService.CHANNEL_ID, "TouchMirror",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }

        return NotificationCompat.Builder(this, SocketService.CHANNEL_ID)
            .setContentTitle("TouchMirror Target")
            .setContentText("Receiving touch events…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
