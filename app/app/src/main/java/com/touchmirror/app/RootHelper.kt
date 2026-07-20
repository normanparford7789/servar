package com.touchmirror.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Executes shell commands with root privileges using the `su` binary.
 * Used on Target devices to inject touch events.
 *
 * All operations are performed on a background thread — do NOT call from UI thread.
 */
object RootHelper {

    private const val TAG = "RootHelper"

    // Long-lived root shell for batching commands
    private var rootProcess: Process? = null
    private var rootWriter: OutputStreamWriter? = null
    private var rootReader: BufferedReader? = null

    @Volatile
    var isRootAvailable = false
        private set

    /**
     * Check if root is available. Call once on service start.
     */
    fun checkRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val result = p.inputStream.bufferedReader().readLine() ?: ""
            p.waitFor()
            val hasRoot = result.contains("uid=0")
            isRootAvailable = hasRoot
            Log.d(TAG, "Root check: $hasRoot ($result)")
            hasRoot
        } catch (e: Exception) {
            Log.w(TAG, "Root not available: ${e.message}")
            isRootAvailable = false
            false
        }
    }

    /**
     * Open a persistent root shell to avoid the overhead of spawning a new process per command.
     */
    fun openShell(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            rootProcess = p
            rootWriter = OutputStreamWriter(p.outputStream)
            rootReader = BufferedReader(InputStreamReader(p.inputStream))
            isRootAvailable = true
            Log.d(TAG, "Root shell opened")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open root shell", e)
            false
        }
    }

    fun closeShell() {
        try {
            rootWriter?.write("exit\n")
            rootWriter?.flush()
        } catch (_: Exception) {}
        rootProcess?.destroy()
        rootProcess = null
        rootWriter = null
        rootReader = null
    }

    /**
     * Execute a single command via the persistent root shell (fast path).
     */
    fun exec(cmd: String) {
        try {
            val w = rootWriter ?: return
            w.write("$cmd\n")
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: $e — trying fresh shell")
            execOnce(cmd)
        }
    }

    /**
     * Execute a command via a fresh root shell (slow path, use as fallback).
     */
    fun execOnce(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            Log.e(TAG, "execOnce failed: $e")
            ""
        }
    }

    // ── Touch injection helpers ────────────────────────────────────────────────

    /**
     * Inject a tap at absolute screen coordinates.
     */
    fun tap(x: Int, y: Int) {
        exec("input tap $x $y")
    }

    /**
     * Inject a swipe gesture.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 100) {
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Inject a touchscreen down event at absolute coordinates.
     */
    fun touchDown(x: Int, y: Int) {
        exec("input touchscreen swipe $x $y $x $y 1")
    }

    /**
     * Convert normalized coordinates (0..1) to absolute pixel coordinates.
     */
    fun denormalize(normX: Float, normY: Float, screenW: Int, screenH: Int): Pair<Int, Int> {
        return Pair(
            (normX * screenW).toInt().coerceIn(0, screenW - 1),
            (normY * screenH).toInt().coerceIn(0, screenH - 1)
        )
    }
}
