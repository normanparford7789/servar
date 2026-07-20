package com.touchmirror.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * Accessibility service running on the Controller device.
 *
 * Primary responsibilities:
 * 1. Re-inject touch gestures recorded by the FloatingMenuService overlay
 *    back onto the local screen (so the actual app under the overlay receives them).
 * 2. Expose static reference so FloatingMenuService can call dispatchGesture().
 *
 * NOTE: Raw coordinate capture is done by the transparent overlay in FloatingMenuService.
 * This service just re-injects the gesture locally after the overlay has captured it.
 */
class TouchCaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchCaptureService"

        /** Shared instance — set when the service is connected. */
        @Volatile
        var instance: TouchCaptureService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.DEFAULT
        serviceInfo = info
        Log.d(TAG, "TouchCaptureService connected")
        // Notify MainActivity
        sendBroadcast(Intent("com.touchmirror.ACCESSIBILITY_CONNECTED"))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process events here — overlay handles gesture capture
    }

    override fun onInterrupt() {
        Log.d(TAG, "TouchCaptureService interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "TouchCaptureService destroyed")
    }

    // ── Gesture Re-injection ───────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.N)
    fun injectTap(x: Float, y: Float, durationMs: Long = 50L) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun injectPath(path: Path, startTime: Long, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
