package com.tminus.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ReminderStore {
    private const val PREFS = "tminus_local_data"
    private const val KEY_REMINDERS = "reminders"

    @Synchronized
    fun getAll(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMINDERS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        Reminder(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            targetDate = item.optString("targetDate"),
                            recipientEmail = item.optString("recipientEmail"),
                            reminderTime = item.optString("reminderTime", "09:00"),
                            notificationEnabled = item.optBoolean("notificationEnabled", true),
                            emailEnabled = item.optBoolean("emailEnabled", false),
                            lastNotificationDate = item.optString("lastNotificationDate", "")
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun upsert(context: Context, reminder: Reminder) {
        val updated = getAll(context).filterNot { it.id == reminder.id } + reminder
        write(context, updated)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        write(context, getAll(context).filterNot { it.id == id })
    }

    @Synchronized
    fun markNotificationSent(context: Context, id: String, date: String) {
        val updated = getAll(context).map {
            if (it.id == id) it.copy(lastNotificationDate = date) else it
        }
        write(context, updated)
    }

    private fun write(context: Context, reminders: List<Reminder>) {
        val array = JSONArray()
        reminders.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("targetDate", item.targetDate)
                    .put("recipientEmail", item.recipientEmail)
                    .put("reminderTime", item.reminderTime)
                    .put("notificationEnabled", item.notificationEnabled)
                    .put("emailEnabled", item.emailEnabled)
                    .put("lastNotificationDate", item.lastNotificationDate)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDERS, array.toString())
            .apply()
    }
}
