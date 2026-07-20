package com.touchmirror.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Foreground service that owns the Socket.IO connection.
 * Both controller and target modes share this service.
 *
 * Bind to it from MainActivity / other services to get the SocketService instance.
 */
class SocketService : Service() {

    companion object {
        private const val TAG = "SocketService"
        const val CHANNEL_ID = "touchmirror_channel"
        const val NOTIF_ID = 1001

        const val ACTION_CONNECT = "com.touchmirror.CONNECT"
        const val ACTION_DISCONNECT = "com.touchmirror.DISCONNECT"

        // Broadcast events sent to the app
        const val BROADCAST_STATUS = "com.touchmirror.STATUS"
        const val BROADCAST_TOUCH = "com.touchmirror.TOUCH"
        const val BROADCAST_PEER_UPDATE = "com.touchmirror.PEER_UPDATE"
        const val BROADCAST_MIRROR_STATE = "com.touchmirror.MIRROR_STATE"
        const val BROADCAST_LOG = "com.touchmirror.LOG"
        const val EXTRA_STATUS = "status"        // "connecting"|"connected"|"disconnected"|"error"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TOUCH_JSON = "touch_json"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_HAS_CONTROLLER = "has_controller"
        const val EXTRA_MIRRORING = "mirroring"
    }

    // ── Binder ─────────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): SocketService = this@SocketService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── State ──────────────────────────────────────────────────────────────────
    private var socket: Socket? = null
    private var isMirroring = true
    private var screenWidth = 1080
    private var screenHeight = 1920
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onTouchEvent: ((TouchEvent) -> Unit)? = null
    var onStatusChange: ((String, String) -> Unit)? = null

    var isConnected = false
        private set

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public API ─────────────────────────────────────────────────────────────
    fun connect() {
        val ctx = applicationContext
        if (!AppPrefs.isConfigured(ctx)) {
            broadcastLog("Not configured — open Settings first")
            return
        }

        val url = AppPrefs.getServerUrl(ctx)
        val sessionId = AppPrefs.getSessionId(ctx)
        val secret = AppPrefs.getSessionSecret(ctx)
        val mode = AppPrefs.getDeviceMode(ctx)

        broadcastStatus("connecting", "Connecting to $url…")

        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setReconnectionDelay(1000)
                .setReconnectionDelayMax(5000)
                .setTransports(arrayOf("websocket", "polling"))
                .setTimeout(20000)
                .build()

            socket?.disconnect()
            socket = IO.socket(URI.create(url), opts).also { s ->
                attachListeners(s, sessionId, secret, mode)
                s.connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket init failed", e)
            broadcastStatus("error", "Connection failed: ${e.message}")
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        isConnected = false
        broadcastStatus("disconnected", "Disconnected")
    }

    fun sendTouchEvent(event: TouchEvent) {
        if (!isConnected || !isMirroring) return
        socket?.emit("touch", event.toJson())
    }

    fun sendTouchBatch(events: List<TouchEvent>) {
        if (!isConnected || !isMirroring) return
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        socket?.emit("touch_batch", arr)
    }

    fun setMirroring(enabled: Boolean) {
        isMirroring = enabled
        val j = JSONObject().put("active", enabled)
        if (isConnected) socket?.emit("mirror_state", j)
        val i = Intent(BROADCAST_MIRROR_STATE).apply { putExtra(EXTRA_MIRRORING, enabled) }
        sendBroadcast(i)
    }

    fun isMirroringActive() = isMirroring

    fun updateScreenSize(w: Int, h: Int) {
        screenWidth = w
        screenHeight = h
    }

    // ── Socket Listeners ───────────────────────────────────────────────────────
    private fun attachListeners(s: Socket, sessionId: String, secret: String, mode: String) {
        s.on(Socket.EVENT_CONNECT) {
            isConnected = true
            broadcastStatus("connected", "Connected — joining session…")
            // Join room
            val payload = JSONObject()
                .put("sessionId", sessionId)
                .put("role", mode)
                .put("secret", secret)
                .put("screenWidth", screenWidth)
                .put("screenHeight", screenHeight)
            s.emit("join", payload)
        }

        s.on(Socket.EVENT_DISCONNECT) { args ->
            isConnected = false
            broadcastStatus("disconnected", "Disconnected: ${args.firstOrNull()}")
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            isConnected = false
            broadcastStatus("error", "Connection error: ${args.firstOrNull()}")
        }

        s.on("joined") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val targets = data.optInt("targetCount", 0)
            val hasCtrl = data.optBoolean("hasController", false)
            broadcastStatus("connected", "Joined session «$sessionId» as $mode")
            broadcastLog("Joined session. Targets: $targets, hasController: $hasCtrl")
            val i = Intent(BROADCAST_PEER_UPDATE).apply {
                putExtra(EXTRA_TARGETS, targets)
                putExtra(EXTRA_HAS_CONTROLLER, hasCtrl)
            }
            sendBroadcast(i)
        }

        s.on("peer_update") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val targets = data.optInt("targetCount", 0)
            val hasCtrl = data.optBoolean("hasController", false)
            val i = Intent(BROADCAST_PEER_UPDATE).apply {
                putExtra(EXTRA_TARGETS, targets)
                putExtra(EXTRA_HAS_CONTROLLER, hasCtrl)
            }
            sendBroadcast(i)
        }

        s.on("error") { args ->
            val msg = (args.firstOrNull() as? JSONObject)?.optString("message") ?: "Unknown error"
            broadcastStatus("error", "Server error: $msg")
        }

        // Receive touch events (target mode)
        s.on("touch") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            handleIncomingTouch(data)
        }

        s.on("touch_batch") { args ->
            val arr = args.firstOrNull() as? JSONArray ?: return@on
            for (i in 0 until arr.length()) {
                handleIncomingTouch(arr.getJSONObject(i))
            }
        }

        s.on("mirror_state") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val active = data.optBoolean("active", true)
            broadcastLog("Controller mirror state: $active")
        }

        s.on("controller_disconnected") {
            broadcastLog("Controller disconnected — waiting…")
        }

        s.on("kicked") { args ->
            val reason = (args.firstOrNull() as? JSONObject)?.optString("reason") ?: ""
            broadcastLog("Kicked from session: $reason")
        }
    }

    private fun handleIncomingTouch(data: JSONObject) {
        try {
            val event = TouchEvent.fromJson(data)
            onTouchEvent?.invoke(event)
            val i = Intent(BROADCAST_TOUCH).apply {
                putExtra(EXTRA_TOUCH_JSON, data.toString())
            }
            sendBroadcast(i)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse touch event", e)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun broadcastStatus(status: String, message: String) {
        Log.d(TAG, "[$status] $message")
        onStatusChange?.invoke(status, message)
        val i = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(i)
        // Update notification
        updateNotification(message)
    }

    fun broadcastLog(message: String) {
        val i = Intent(BROADCAST_LOG).apply { putExtra(EXTRA_MESSAGE, message) }
        sendBroadcast(i)
        Log.d(TAG, message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TouchMirror Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TouchMirror background service"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "Running…"): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TouchMirror")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(text))
    }
}
