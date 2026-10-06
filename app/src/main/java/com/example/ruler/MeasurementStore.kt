package com.example.ruler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 测量记录存储（基于 SharedPreferences + JSON）。
 * 支持保存单条记录、读取全部、清空。
 */
object MeasurementStore {

    private const val PREFS = "measurement_history"
    private const val KEY_LIST = "records"
    private const val MAX = 100

    data class Record(
        val id: Long,
        val type: String,       // "ruler" | "camera" | "ar"
        val value: Float,       // 测量值（cm）
        val unit: String,       // "cm" | "mm"
        val timestamp: Long,
        val photoPath: String? = null  // 可选：拍照记录路径
    ) {
        fun display(): String =
            if (value >= 10f) String.format("%.2f cm", value)
            else String.format("%.1f mm", value * 10f)
    }

    fun save(context: Context, type: String, valueCm: Float, photoPath: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_LIST, "[]"))
        val obj = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("type", type)
            put("value", valueCm)
            put("unit", "cm")
            put("timestamp", System.currentTimeMillis())
            put("photoPath", photoPath ?: "")
        }
        arr.put(obj)
        // 限制最大数量
        while (arr.length() > MAX) arr.remove(0)
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun getAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_LIST, "[]"))
        val list = mutableListOf<Record>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Record(
                    id = o.getLong("id"),
                    type = o.getString("type"),
                    value = o.getDouble("value").toFloat(),
                    unit = o.optString("unit", "cm"),
                    timestamp = o.getLong("timestamp"),
                    photoPath = o.optString("photoPath", "").ifEmpty { null }
                )
            )
        }
        return list.reversed()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LIST).apply()
    }

    fun delete(context: Context, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_LIST, "[]"))
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("id") != id) newArr.put(o)
        }
        prefs.edit().putString(KEY_LIST, newArr.toString()).apply()
    }
}
