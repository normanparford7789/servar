package com.touchmirror.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvSessionId: TextView
    private lateinit var tvPeers: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvMirrorState: TextView
    private lateinit var tvLog: TextView
    private lateinit var statusDot: View
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnShowLog: MaterialButton
    private lateinit var switchMirror: SwitchMaterial
    private lateinit var cardMirrorState: View
    private lateinit var cardAccessibility: View
    private lateinit var cardOverlay: View
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var scrollLog: View
    private lateinit var cardLog: View

    private var socketService: SocketService? = null
    private var isConnected = false
    private var logVisible = false
    private val logBuilder = StringBuilder()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            socketService = (service as SocketService.LocalBinder).getService()
            isConnected = socketService?.isConnected ?: false
            updateUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            socketService = null
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(SocketService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(SocketService.EXTRA_MESSAGE) ?: ""
            isConnected = status == "connected"
            updateStatusUI(status, message)
        }
    }

    private val peerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val targets = intent?.getIntExtra(SocketService.EXTRA_TARGETS, 0) ?: 0
            val hasCtrl = intent?.getBooleanExtra(SocketService.EXTRA_HAS_CONTROLLER, false) ?: false
            val mode = AppPrefs.getDeviceMode(this@MainActivity)
            if (mode == AppPrefs.MODE_CONTROLLER) {
                tvPeers.text = "Connected targets: $targets"
            } else {
                tvPeers.text = "Controller connected: $hasCtrl"
            }
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(SocketService.EXTRA_MESSAGE) ?: return
            appendLog(msg)
        }
    }

    private val mirrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val mirroring = intent?.getBooleanExtra(SocketService.EXTRA_MIRRORING, true) ?: true
            switchMirror.isChecked = mirroring
            tvMirrorState.text = if (mirroring) "Mirroring: ON" else "Mirroring: PAUSED"
            tvMirrorState.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (mirroring) R.color.colorConnected else R.color.colorDisconnected
                )
            )
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        checkPermissions()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        registerReceivers()
        bindSocketService()
        checkPermissions()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceivers()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        socketService = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvMode = findViewById(R.id.tvMode)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvSessionId = findViewById(R.id.tvSessionId)
        tvPeers = findViewById(R.id.tvPeers)
        tvLatency = findViewById(R.id.tvLatency)
        tvMirrorState = findViewById(R.id.tvMirrorState)
        tvLog = findViewById(R.id.tvLog)
        statusDot = findViewById(R.id.statusDot)
        btnConnect = findViewById(R.id.btnConnect)
        btnSettings = findViewById(R.id.btnSettings)
        btnShowLog = findViewById(R.id.btnShowLog)
        switchMirror = findViewById(R.id.switchMirror)
        cardMirrorState = findViewById(R.id.cardMirrorState)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardOverlay = findViewById(R.id.cardOverlay)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        scrollLog = findViewById(R.id.scrollLog)
        cardLog = findViewById(R.id.cardLog)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (isConnected) disconnectAll() else connectAll()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnShowLog.setOnClickListener {
            logVisible = !logVisible
            cardLog.visibility = if (logVisible) View.VISIBLE else View.GONE
            btnShowLog.text = if (logVisible) "Hide Log" else "Log"
        }

        switchMirror.setOnCheckedChangeListener { _, checked ->
            socketService?.setMirroring(checked)
        }

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun registerReceivers() {
        registerReceiver(statusReceiver, IntentFilter(SocketService.BROADCAST_STATUS))
        registerReceiver(peerReceiver, IntentFilter(SocketService.BROADCAST_PEER_UPDATE))
        registerReceiver(logReceiver, IntentFilter(SocketService.BROADCAST_LOG))
        registerReceiver(mirrorReceiver, IntentFilter(SocketService.BROADCAST_MIRROR_STATE))
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(peerReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mirrorReceiver) } catch (_: Exception) {}
    }

    private fun bindSocketService() {
        val intent = Intent(this, SocketService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    // ── Connect / Disconnect ───────────────────────────────────────────────────

    private fun connectAll() {
        if (!AppPrefs.isConfigured(this)) {
            Toast.makeText(this, "Please configure settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val mode = AppPrefs.getDeviceMode(this)

        // Start socket
        val socketIntent = Intent(this, SocketService::class.java).apply {
            action = SocketService.ACTION_CONNECT
        }
        startForegroundService(socketIntent)

        if (mode == AppPrefs.MODE_CONTROLLER) {
            // Check permissions
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Enable TouchMirror in Accessibility Settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            // Start floating overlay
            val floatIntent = Intent(this, FloatingMenuService::class.java).apply {
                action = FloatingMenuService.ACTION_START
            }
            startForegroundService(floatIntent)
        } else {
            // Start touch executor
            val execIntent = Intent(this, TouchExecutorService::class.java).apply {
                action = TouchExecutorService.ACTION_START
            }
            startForegroundService(execIntent)
        }

        appendLog("Connecting…")
    }

    private fun disconnectAll() {
        val socketIntent = Intent(this, SocketService::class.java).apply {
            action = SocketService.ACTION_DISCONNECT
        }
        startService(socketIntent)

        stopService(Intent(this, FloatingMenuService::class.java))
        stopService(Intent(this, TouchExecutorService::class.java))

        isConnected = false
        updateStatusUI("disconnected", "Disconnected")
        appendLog("Disconnected")
    }

    // ── UI Updates ─────────────────────────────────────────────────────────────

    private fun updateUI() {
        val mode = AppPrefs.getDeviceMode(this)
        tvMode.text = if (mode == AppPrefs.MODE_CONTROLLER) "CONTROLLER" else "TARGET"
        tvServerUrl.text = "Server: ${AppPrefs.getServerUrl(this).ifBlank { "not configured" }}"
        tvSessionId.text = "Session: ${AppPrefs.getSessionId(this)}"
        cardMirrorState.visibility =
            if (mode == AppPrefs.MODE_CONTROLLER && isConnected) View.VISIBLE else View.GONE

        val status = if (isConnected) "connected" else "disconnected"
        updateStatusUI(status, if (isConnected) "Connected" else "Disconnected")
        checkPermissions()
    }

    private fun updateStatusUI(status: String, message: String) {
        runOnUiThread {
            tvStatus.text = message
            val dotColor = when (status) {
                "connected" -> R.color.colorConnected
                "connecting" -> R.color.colorConnecting
                else -> R.color.colorDisconnected
            }
            statusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, dotColor)

            btnConnect.text = if (status == "connected") "Disconnect" else "Connect"
            isConnected = status == "connected"

            val mode = AppPrefs.getDeviceMode(this)
            cardMirrorState.visibility =
                if (mode == AppPrefs.MODE_CONTROLLER && isConnected) View.VISIBLE else View.GONE
        }
    }

    private fun checkPermissions() {
        val mode = AppPrefs.getDeviceMode(this)
        if (mode == AppPrefs.MODE_CONTROLLER) {
            val needsA11y = !isAccessibilityEnabled()
            val needsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)
            cardAccessibility.visibility = if (needsA11y) View.VISIBLE else View.GONE
            cardOverlay.visibility = if (needsOverlay) View.VISIBLE else View.GONE
        } else {
            cardAccessibility.visibility = View.GONE
            cardOverlay.visibility = View.GONE
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logBuilder.append("[$time] $message\n")
            // Keep last 100 lines
            val lines = logBuilder.lines()
            if (lines.size > 100) {
                logBuilder.clear()
                logBuilder.append(lines.takeLast(100).joinToString("\n"))
            }
            tvLog.text = logBuilder.toString()
            // Auto-scroll
            scrollLog.post { (scrollLog as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
