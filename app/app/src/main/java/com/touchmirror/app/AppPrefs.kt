package com.touchmirror.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object AppPrefs {

    private const val PREFS_NAME = "touchmirror_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Keys ──────────────────────────────────────────────────────────────────
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_SECRET = "session_secret"
    private const val KEY_DEVICE_MODE = "device_mode" // "controller" | "target"
    private const val KEY_AUTOSTART = "autostart"

    // ── Accessors ─────────────────────────────────────────────────────────────
    fun getServerUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_SERVER_URL, "") ?: ""

    fun setServerUrl(ctx: Context, url: String) =
        prefs(ctx).edit { putString(KEY_SERVER_URL, url.trimEnd('/')) }

    fun getSessionId(ctx: Context): String =
        prefs(ctx).getString(KEY_SESSION_ID, "default-session") ?: "default-session"

    fun setSessionId(ctx: Context, id: String) =
        prefs(ctx).edit { putString(KEY_SESSION_ID, id) }

    fun getSessionSecret(ctx: Context): String =
        prefs(ctx).getString(KEY_SESSION_SECRET, "") ?: ""

    fun setSessionSecret(ctx: Context, secret: String) =
        prefs(ctx).edit { putString(KEY_SESSION_SECRET, secret) }

    fun getDeviceMode(ctx: Context): String =
        prefs(ctx).getString(KEY_DEVICE_MODE, MODE_CONTROLLER) ?: MODE_CONTROLLER

    fun setDeviceMode(ctx: Context, mode: String) =
        prefs(ctx).edit { putString(KEY_DEVICE_MODE, mode) }

    fun isAutostart(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOSTART, false)

    fun setAutostart(ctx: Context, value: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_AUTOSTART, value) }

    fun isConfigured(ctx: Context): Boolean {
        val url = getServerUrl(ctx)
        val secret = getSessionSecret(ctx)
        return url.isNotBlank() && secret.isNotBlank()
    }

    const val MODE_CONTROLLER = "controller"
    const val MODE_TARGET = "target"
}
