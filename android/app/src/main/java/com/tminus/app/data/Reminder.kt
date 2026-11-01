package com.tminus.app.data

data class Reminder(
    val id: String,
    val title: String,
    val targetDate: String,
    val recipientEmail: String,
    val reminderTime: String,
    val notificationEnabled: Boolean,
    val emailEnabled: Boolean,
    val lastNotificationDate: String = ""
)
