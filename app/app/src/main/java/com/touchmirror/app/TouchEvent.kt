package com.touchmirror.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single touch pointer in a multi-touch event.
 * x, y are normalized 0.0–1.0 relative to screen dimensions.
 */
data class TouchPointer(
    val id: Int,
    val x: Float,   // normalized 0..1
    val y: Float    // normalized 0..1
)

/**
 * A touch event packet sent from controller → server → targets.
 *
 * action values:
 *   "down"   — pointer pressed
 *   "move"   — pointer moved
 *   "up"     — pointer lifted
 *   "cancel" — gesture cancelled
 */
data class TouchEvent(
    val action: String,
    val pointers: List<TouchPointer>,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("timestamp", timestamp)
        val arr = JSONArray()
        for (p in pointers) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
            })
        }
        put("pointers", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): TouchEvent {
            val action = obj.getString("action")
            val timestamp = if (obj.has("timestamp")) obj.getLong("timestamp") else System.currentTimeMillis()
            val pArr = obj.getJSONArray("pointers")
            val pointers = mutableListOf<TouchPointer>()
            for (i in 0 until pArr.length()) {
                val p = pArr.getJSONObject(i)
                pointers.add(TouchPointer(p.getInt("id"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat()))
            }
            return TouchEvent(action, pointers, timestamp)
        }
    }
}
