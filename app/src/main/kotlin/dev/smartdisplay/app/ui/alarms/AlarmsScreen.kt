package dev.smartdisplay.app.ui.alarms

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.alarm.Alarm
import dev.smartdisplay.app.alarm.DayWords
import dev.smartdisplay.app.alarm.alarmTimeText
import dev.smartdisplay.app.alarm.daysSummary
import dev.smartdisplay.app.alarm.weekOrder
import dev.smartdisplay.app.ui.common.Panel
import dev.smartdisplay.app.ui.common.PanelBody
import java.time.DayOfWeek
import java.time.format.TextStyle

/** The display's alarms: add, edit, turn on and off. Open to guests as well as the owner. */
@Composable
fun AlarmsScreen(onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    val alarms = (context.applicationContext as SmartDisplayApp).alarms
    val list by alarms.alarms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var adding by remember { mutableStateOf(false) }

    // Alarms need to show a notification (Android 13+) and take over the screen (Android 14+).
    var notificationsAllowed by remember { mutableStateOf(canNotify(context)) }
    var fullScreenAllowed by remember { mutableStateOf(canUseFullScreen(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = canNotify(context)
        fullScreenAllowed = canUseFullScreen(context)
        onPauseOrDispose {}
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }

    Panel(eyebrow = stringResource(R.string.alarms_eyebrow), title = stringResource(R.string.alarms_title)) {
        if (list.isEmpty()) PanelBody(stringResource(R.string.alarms_none))
        list.forEach { alarm ->
            AlarmRow(
                alarm = alarm,
                onClick = { editing = alarm },
                onEnabled = { alarms.update(alarm.copy(enabled = it)) },
            )
        }
        if (list.isNotEmpty() && !notificationsAllowed) {
            PermissionNote(stringResource(R.string.alarms_need_notifications), stringResource(R.string.alarms_allow)) {
                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (list.isNotEmpty() && notificationsAllowed && !fullScreenAllowed) {
            PermissionNote(stringResource(R.string.alarms_need_full_screen), stringResource(R.string.alarms_allow)) {
                openFullScreenSettings(context)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 32.dp)) {
            Button(onClick = { adding = true }) { Text(stringResource(R.string.alarms_add)) }
            TextButton(onClick = onDone, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_done))
            }
        }
    }

    if (adding || editing != null) {
        val current = editing
        AlarmEditor(
            alarm = current,
            onSave = { hour, minute, days, label ->
                if (current == null) {
                    alarms.add(hour, minute, days, label)
                    // Ask the first time there's something to ring for.
                    if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    alarms.update(current.copy(hour = hour, minute = minute, days = days, label = label, enabled = true))
                }
                adding = false
                editing = null
            },
            onDelete = current?.let { { alarms.delete(it.id); editing = null } },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, onClick: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val words = dayWords()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = alarmTimeText(context, alarm),
                style = MaterialTheme.typography.headlineMedium,
                color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = listOf(alarm.label, daysSummary(alarm.days, locale, words)).filter { it.isNotBlank() }
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = alarm.enabled, onCheckedChange = onEnabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(
    alarm: Alarm?,
    onSave: (hour: Int, minute: Int, days: Set<DayOfWeek>, label: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val time = rememberTimePickerState(
        initialHour = alarm?.hour ?: 7,
        initialMinute = alarm?.minute ?: 0,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    var days by remember { mutableStateOf(alarm?.days.orEmpty()) }
    var label by remember { mutableStateOf(alarm?.label.orEmpty()) }
    val focus = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        // Wider than the default, so the time picker, all seven days and the label fit on a phone.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 560.dp),
        title = { Text(stringResource(if (alarm == null) R.string.alarms_add else R.string.alarms_edit)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                TimePicker(state = time)
                Text(
                    text = stringResource(R.string.alarms_repeat),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    weekOrder(locale).forEach { day ->
                        DayToggle(
                            letter = day.getDisplayName(TextStyle.NARROW, locale),
                            name = day.getDisplayName(TextStyle.FULL, locale),
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text(stringResource(R.string.alarms_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(time.hour, time.minute, days, label) }) {
                Text(stringResource(R.string.pin_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.alarms_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun PermissionNote(text: String, action: String, onAction: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 16.dp),
    )
    OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
}

@Composable
internal fun dayWords() = DayWords(
    once = stringResource(R.string.alarms_once),
    everyDay = stringResource(R.string.alarms_every_day),
    weekdays = stringResource(R.string.alarms_weekdays),
    weekends = stringResource(R.string.alarms_weekends),
)

private fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun canUseFullScreen(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

private fun openFullScreenSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** One day of the week, as a round toggle; seven fit across a phone. */
@Composable
private fun DayToggle(letter: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = name },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.labelLarge)
        }
    }
}
