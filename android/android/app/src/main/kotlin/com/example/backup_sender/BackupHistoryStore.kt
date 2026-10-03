package com.example.backup_sender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object BackupHistoryStore {
    @Synchronized
    fun load(context: Context): String = context.getSharedPreferences("backup_history", Context.MODE_PRIVATE).getString("records", "[]") ?: "[]"

    @Synchronized
    fun add(context: Context, record: JSONObject) {
        val old = JSONArray(load(context))
        val records = JSONArray().put(record)
        for (i in 0 until minOf(old.length(), 199)) records.put(old.getJSONObject(i))
        if (!context.getSharedPreferences("backup_history", Context.MODE_PRIVATE).edit().putString("records", records.toString()).commit()) {
            throw IllegalStateException("无法保存备份记录")
        }
    }
}
