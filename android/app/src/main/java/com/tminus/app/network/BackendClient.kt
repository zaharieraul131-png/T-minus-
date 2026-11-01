package com.tminus.app.network

import android.content.Context
import android.util.Base64
import com.tminus.app.data.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.ZoneId

object BackendClient {
    private const val PREFS = "tminus_connection"
    private const val KEY_BASE_URL = "backend_url"
    private const val KEY_DEVICE_TOKEN = "device_token"

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "")?.trim()?.trimEnd('/') ?: ""

    fun saveBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    private fun deviceToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
        return token
    }

    private fun validateBaseUrl(context: Context): String {
        val base = getBaseUrl(context)
        require(base.startsWith("https://") || base.startsWith("http://10.0.2.2") || base.startsWith("http://127.0.0.1")) {
            "Enter your Cloudflare Worker URL (it must start with https://)."
        }
        return base
    }

    private suspend fun request(
        context: Context,
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val base = validateBaseUrl(context)
        val token = deviceToken(context)
        if (path != "/api/register") {
            rawRequest("$base/api/register", "POST", token, JSONObject())
        }
        rawRequest("$base$path", method, token, body)
    }

    private fun rawRequest(urlString: String, method: String, token: String, body: JSONObject?): JSONObject {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input -> BufferedReader(InputStreamReader(input)).readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error", "Server returned HTTP $status"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun upsertReminder(context: Context, reminder: Reminder) {
        val body = JSONObject()
            .put("id", reminder.id)
            .put("title", reminder.title)
            .put("targetDate", reminder.targetDate)
            .put("recipientEmail", reminder.recipientEmail)
            .put("reminderTime", reminder.reminderTime)
            .put("timezone", ZoneId.systemDefault().id)
            .put("notificationEnabled", reminder.notificationEnabled)
            .put("emailEnabled", reminder.emailEnabled)
        request(context, "/api/reminders/upsert", "POST", body)
    }

    suspend fun deleteReminder(context: Context, id: String) {
        request(context, "/api/reminders/$id", "DELETE")
    }

    suspend fun getGmailConnectUrl(context: Context): String =
        request(context, "/api/gmail/connect", "POST", JSONObject()).getString("authUrl")

    suspend fun getGmailStatus(context: Context): JSONObject = request(context, "/api/gmail/status")

    suspend fun disconnectGmail(context: Context) {
        request(context, "/api/gmail/disconnect", "POST", JSONObject())
    }
}
