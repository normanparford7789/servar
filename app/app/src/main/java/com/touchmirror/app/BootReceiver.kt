package com.touchmirror.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-starts the mirroring services on device boot if autostart is enabled in settings.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!AppPrefs.isAutostart(context)) return
        if (!AppPrefs.isConfigured(context)) return

        Log.d(TAG, "Boot completed — autostarting TouchMirror")

        val serviceIntent = Intent(context, SocketService::class.java).apply {
            this.action = SocketService.ACTION_CONNECT
        }
        context.startForegroundService(serviceIntent)

        val mode = AppPrefs.getDeviceMode(context)
        if (mode == AppPrefs.MODE_CONTROLLER) {
            val floatIntent = Intent(context, FloatingMenuService::class.java).apply {
                this.action = FloatingMenuService.ACTION_START
            }
            context.startForegroundService(floatIntent)
        } else {
            val execIntent = Intent(context, TouchExecutorService::class.java).apply {
                this.action = TouchExecutorService.ACTION_START
            }
            context.startForegroundService(execIntent)
        }
    }
}
