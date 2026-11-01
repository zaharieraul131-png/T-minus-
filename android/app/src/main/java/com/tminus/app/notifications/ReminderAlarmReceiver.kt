package com.tminus.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tminus.app.MainActivity
import com.tminus.app.data.ReminderStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        createChannel(context)
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        ReminderStore.getAll(context).forEach { reminder ->
            if (!reminder.notificationEnabled) return@forEach
            val target = runCatching { LocalDate.parse(reminder.targetDate) }.getOrNull() ?: return@forEach
            if (target.isBefore(today) || reminder.lastNotificationDate == today.toString()) return@forEach
            val time = runCatching { LocalTime.parse(reminder.reminderTime) }.getOrNull() ?: return@forEach
            if (now.toLocalTime().isBefore(time)) return@forEach

            val days = ChronoUnit.DAYS.between(today, target)
            val message = when (days) {
                0L -> "Your deadline is today."
                1L -> "1 day remaining."
                else -> "$days days remaining."
            }
            val openApp = PendingIntent.getActivity(
                context,
                reminder.id.hashCode(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("T-MINUS  /  ${reminder.title}")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$message  Target date: ${reminder.targetDate}."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
            } catch (_: SecurityException) {
                // Android 13+ notification permission may have been denied.
            } finally {
                // Avoid repeatedly firing an alarm if notification permission is off.
                ReminderStore.markNotificationSent(context, reminder.id, today.toString())
            }
        }
        ReminderScheduler.scheduleNext(context)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "T-MINUS reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Daily countdown reminders" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "tminus_daily_reminders"
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ReminderScheduler.scheduleNext(context)
    }
}

object ReminderScheduler {
    private const val REQUEST_CODE = 7019

    fun scheduleNext(context: Context) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val candidates = ReminderStore.getAll(context).mapNotNull { reminder ->
            if (!reminder.notificationEnabled) return@mapNotNull null
            val target = runCatching { LocalDate.parse(reminder.targetDate) }.getOrNull() ?: return@mapNotNull null
            if (target.isBefore(today)) return@mapNotNull null
            val time = runCatching { LocalTime.parse(reminder.reminderTime) }.getOrNull() ?: return@mapNotNull null

            val nextDay = if (reminder.lastNotificationDate == today.toString()) today.plusDays(1) else today
            var candidate = nextDay.atTime(time)
            if (nextDay == today && candidate.isBefore(now)) candidate = now.plusSeconds(5)
            if (candidate.toLocalDate().isAfter(target)) null else candidate
        }

        val next = candidates.minOrNull()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (next == null) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }
}
