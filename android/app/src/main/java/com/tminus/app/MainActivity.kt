package com.tminus.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tminus.app.data.Reminder
import com.tminus.app.data.ReminderStore
import com.tminus.app.network.BackendClient
import com.tminus.app.notifications.ReminderScheduler
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private val TMinusOrange = Color(0xFFF39A42)
private val TMinusBackground = Color(0xFF101820)
private val TMinusSurface = Color(0xFF1A2731)
private val TMinusMuted = Color(0xFF9AABB8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TMinusApp() }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun TMinusApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reminders by remember { mutableStateOf(ReminderStore.getAll(context).sortedBy { it.targetDate }) }
    var showEditor by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<Reminder?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf("LOCAL SYSTEM READY") }

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            syncStatus = if (granted) "NOTIFICATIONS ENABLED" else "NOTIFICATION PERMISSION NOT GRANTED"
        }
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ReminderScheduler.scheduleNext(context)
    }

    fun reload() {
        reminders = ReminderStore.getAll(context).sortedBy { it.targetDate }
        ReminderScheduler.scheduleNext(context)
    }

    fun saveReminder(reminder: Reminder) {
        ReminderStore.upsert(context, reminder)
        reload()
        showEditor = false
        editingReminder = null
        syncStatus = "SAVED ON THIS DEVICE · SYNCING…"
        scope.launch {
            try {
                BackendClient.upsertReminder(context, reminder)
                syncStatus = "SAVED · CLOUD EMAIL SCHEDULE UPDATED"
            } catch (error: Exception) {
                syncStatus = if (BackendClient.getBaseUrl(context).isBlank()) {
                    "SAVED LOCALLY · ADD BACKEND URL TO ENABLE EMAIL"
                } else {
                    "SAVED LOCALLY · CLOUD SYNC FAILED: ${error.message ?: "UNKNOWN ERROR"}"
                }
            }
        }
    }

    fun deleteReminder(reminder: Reminder) {
        ReminderStore.delete(context, reminder.id)
        reload()
        scope.launch {
            runCatching { BackendClient.deleteReminder(context, reminder.id) }
            syncStatus = "COUNTDOWN REMOVED"
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TMinusOrange,
            onPrimary = Color(0xFF25170B),
            secondary = Color(0xFFFFBD78),
            background = TMinusBackground,
            surface = TMinusSurface,
            onSurface = Color(0xFFEAF0F3),
            onBackground = Color(0xFFEAF0F3)
        )
    ) {
        Scaffold(
            containerColor = TMinusBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("T-MINUS", fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("DEADLINE MONITOR", color = TMinusMuted, fontSize = 10.sp, letterSpacing = 1.6.sp)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = TMinusOrange)
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { editingReminder = null; showEditor = true },
                    containerColor = TMinusOrange,
                    contentColor = Color(0xFF25170B),
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("NEW COUNTDOWN", fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp) }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SystemBanner(reminders.size)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("ACTIVE COUNTDOWNS", color = TMinusMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("${reminders.size.toString().padStart(2, '0')} TRACKED", color = TMinusOrange, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (reminders.isEmpty()) {
                    EmptyState(onAdd = { editingReminder = null; showEditor = true })
                } else {
                    reminders.forEach { reminder ->
                        ReminderCard(
                            reminder = reminder,
                            onEdit = { editingReminder = reminder; showEditor = true },
                            onDelete = { deleteReminder(reminder) }
                        )
                    }
                }
                Text(syncStatus, color = TMinusMuted, fontSize = 10.sp, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 4.dp, bottom = 90.dp))
            }
        }

        if (showEditor) {
            ReminderEditorDialog(
                initial = editingReminder,
                onDismiss = { showEditor = false; editingReminder = null },
                onSave = ::saveReminder
            )
        }

        if (showSettings) {
            SettingsDialog(
                onDismiss = { showSettings = false },
                onStatus = { syncStatus = it },
                onSyncAll = {
                    scope.launch {
                        var synced = 0
                        reminders.forEach { item ->
                            runCatching { BackendClient.upsertReminder(context, item) }.onSuccess { synced++ }
                        }
                        syncStatus = "CLOUD SYNC COMPLETE · $synced/${reminders.size} COUNTDOWNS"
                    }
                }
            )
        }
    }
}

@Composable
private fun SystemBanner(count: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF17242E)),
        border = BorderStroke(1.dp, Color(0xFF34434D)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Color(0xFF3B2818), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Timer, contentDescription = null, tint = TMinusOrange, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("COUNTDOWN SYSTEM", fontSize = 11.sp, color = TMinusMuted, letterSpacing = 1.1.sp)
                Text(if (count == 0) "Awaiting first deadline" else "$count deadline${if (count == 1) "" else "s"} under watch", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("ONLINE", color = Color(0xFF8ACB9A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = TMinusSurface), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = TMinusOrange, modifier = Modifier.size(36.dp))
            Text("NO DEADLINES TRACKED", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Create a countdown to start receiving daily reminders.", color = TMinusMuted, fontSize = 13.sp)
            OutlinedButton(onClick = onAdd) { Text("CREATE FIRST COUNTDOWN") }
        }
    }
}

@Composable
private fun ReminderCard(reminder: Reminder, onEdit: () -> Unit, onDelete: () -> Unit) {
    val target = runCatching { LocalDate.parse(reminder.targetDate) }.getOrNull()
    val days = target?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val dateText = target?.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())) ?: reminder.targetDate
    Card(
        colors = CardDefaults.cardColors(containerColor = TMinusSurface),
        border = BorderStroke(1.dp, Color(0xFF34434D)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(reminder.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(dateText, color = TMinusMuted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            days == null -> "--"
                            days < 0 -> "PASSED"
                            days == 0L -> "TODAY"
                            else -> days.toString().padStart(2, '0')
                        },
                        color = TMinusOrange,
                        fontSize = if (days != null && days >= 0) 28.sp else 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    if (days != null && days > 0) Text("DAYS LEFT", color = TMinusMuted, fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF34434D))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Alarm, contentDescription = null, tint = TMinusOrange, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("DAILY · ${reminder.reminderTime}", color = TMinusMuted, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.Email, contentDescription = null, tint = if (reminder.emailEnabled) TMinusOrange else TMinusMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (reminder.emailEnabled) "EMAIL ON" else "EMAIL OFF", color = TMinusMuted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = TMinusMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFE08A78), modifier = Modifier.size(18.dp))
                }
            }
            if (reminder.emailEnabled && reminder.recipientEmail.isNotBlank()) {
                Text("TO  ${reminder.recipientEmail}", color = TMinusMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ReminderEditorDialog(initial: Reminder?, onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    val context = LocalContext.current
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var targetDate by remember(initial?.id) { mutableStateOf(initial?.targetDate ?: LocalDate.now().plusDays(7).toString()) }
    var recipient by remember(initial?.id) { mutableStateOf(initial?.recipientEmail ?: "") }
    var reminderTime by remember(initial?.id) { mutableStateOf(initial?.reminderTime ?: "09:00") }
    var notifications by remember(initial?.id) { mutableStateOf(initial?.notificationEnabled ?: true) }
    var emails by remember(initial?.id) { mutableStateOf(initial?.emailEnabled ?: true) }
    var error by remember { mutableStateOf("") }
    val date = runCatching { LocalDate.parse(targetDate) }.getOrNull()
    val prettyDate = date?.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault())) ?: targetDate

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TMinusSurface,
        title = { Text(if (initial == null) "NEW COUNTDOWN" else "EDIT COUNTDOWN", color = TMinusOrange, fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 530.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it; error = "" }, label = { Text("Countdown name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        val selected = date?.takeIf { !it.isBefore(LocalDate.now()) } ?: LocalDate.now().plusDays(7)
                        DatePickerDialog(
                            context,
                            { _, year, month, day -> targetDate = LocalDate.of(year, month + 1, day).toString() },
                            selected.year,
                            selected.monthValue - 1,
                            selected.dayOfMonth
                        ).apply { datePicker.minDate = System.currentTimeMillis() - 60_000L }.show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("DATE  ·  $prettyDate")
                }
                OutlinedButton(
                    onClick = {
                        val parts = reminderTime.split(":")
                        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
                        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        TimePickerDialog(context, { _, h, m -> reminderTime = String.format(Locale.ROOT, "%02d:%02d", h, m) }, hour, minute, true).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Alarm, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("DAILY REMINDER  ·  $reminderTime")
                }
                HorizontalDivider(color = Color(0xFF34434D))
                SettingToggle("Android notification", "Show a daily notification on this phone", notifications) { notifications = it }
                SettingToggle("Automatic email", "Send a daily email until the target date", emails) { emails = it }
                if (emails) {
                    OutlinedTextField(
                        value = recipient,
                        onValueChange = { recipient = it.trim(); error = "" },
                        label = { Text("Recipient email address") },
                        placeholder = { Text("someone@example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Each countdown has its own recipient. All emails use the one Gmail account connected in Settings.", color = TMinusMuted, fontSize = 11.sp)
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF9385), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = title.trim()
                    val validEmail = recipient.matches(Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
                    when {
                        cleanTitle.isBlank() -> error = "Enter a name for this countdown."
                        date == null || date.isBefore(LocalDate.now()) -> error = "Choose today or a future date."
                        emails && !validEmail -> error = "Enter a valid recipient email address."
                        !notifications && !emails -> error = "Enable at least one reminder type."
                        else -> onSave(
                            Reminder(
                                id = initial?.id ?: UUID.randomUUID().toString(),
                                title = cleanTitle,
                                targetDate = targetDate,
                                recipientEmail = recipient.trim(),
                                reminderTime = reminderTime,
                                notificationEnabled = notifications,
                                emailEnabled = emails,
                                lastNotificationDate = initial?.lastNotificationDate ?: ""
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TMinusOrange, contentColor = Color(0xFF25170B))
            ) { Text("SAVE COUNTDOWN", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = TMinusMuted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SettingsDialog(onDismiss: () -> Unit, onStatus: (String) -> Unit, onSyncAll: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backendUrl by remember { mutableStateOf(BackendClient.getBaseUrl(context)) }
    var gmailStatus by remember { mutableStateOf("Not checked") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun checkGmail() {
        busy = true
        error = ""
        scope.launch {
            try {
                val result: JSONObject = BackendClient.getGmailStatus(context)
                gmailStatus = if (result.optBoolean("connected")) "Connected${result.optString("email").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}" else "Not connected"
            } catch (e: Exception) {
                error = e.message ?: "Could not reach backend."
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TMinusSurface,
        title = { Text("SYSTEM SETTINGS", color = TMinusOrange, fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CLOUD BACKEND", color = TMinusMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it.trim(); error = "" },
                    label = { Text("Cloudflare Worker URL") },
                    placeholder = { Text("https://your-worker.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        BackendClient.saveBaseUrl(context, backendUrl)
                        onStatus("BACKEND URL SAVED · SYNCING COUNTDOWNS…")
                        onSyncAll()
                        error = "Backend URL saved."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TMinusOrange, contentColor = Color(0xFF25170B))
                ) { Text("SAVE BACKEND URL") }
                HorizontalDivider(color = Color(0xFF34434D))
                Text("GMAIL SENDER", color = TMinusMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("One Gmail account sends all countdown emails. Each countdown keeps its own recipient address.", color = TMinusMuted, fontSize = 12.sp)
                Text("STATUS  ·  $gmailStatus", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { checkGmail() }, modifier = Modifier.weight(1f), enabled = !busy) { Text(if (busy) "CHECKING…" else "CHECK GMAIL") }
                    Button(
                        onClick = {
                            BackendClient.saveBaseUrl(context, backendUrl)
                            busy = true
                            error = ""
                            scope.launch {
                                try {
                                    val authUrl = BackendClient.getGmailConnectUrl(context)
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)))
                                    error = "Google authorization opened. Return here after approving, then tap CHECK GMAIL."
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not start Gmail authorization."
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && backendUrl.startsWith("https://"),
                        colors = ButtonDefaults.buttonColors(containerColor = TMinusOrange, contentColor = Color(0xFF25170B))
                    ) { Text("CONNECT GMAIL") }
                }
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                BackendClient.disconnectGmail(context)
                                gmailStatus = "Not connected"
                                error = "Gmail access disconnected."
                            } catch (e: Exception) { error = e.message ?: "Disconnect failed." }
                            finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && backendUrl.startsWith("https://")
                ) { Text("DISCONNECT GMAIL") }
                if (error.isNotBlank()) Text(error, color = if (error.contains("saved", true) || error.contains("opened", true) || error.contains("disconnected", true)) Color(0xFF8ACB9A) else Color(0xFFFF9385), fontSize = 11.sp)
                Text("Local Android notifications work without the backend. Automatic email requires the Cloudflare Worker to be deployed and Gmail connected.", color = TMinusMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE") } }
    )
}
