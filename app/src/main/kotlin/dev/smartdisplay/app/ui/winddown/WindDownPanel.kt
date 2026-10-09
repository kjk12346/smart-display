package dev.smartdisplay.app.ui.winddown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.alarm.WindDownService
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ui.ambient.rememberNextAlarmText
import dev.smartdisplay.app.ui.common.rememberMinuteClock
import dev.smartdisplay.app.ui.controls.ServiceCall
import dev.smartdisplay.app.ui.dashboard.calendars
import java.time.Instant
import java.time.ZoneId

/**
 * While a wind-down runs, a panel over the display: when the lights go off, tomorrow's alarm and first event, and a
 * check of what's still unlocked, open or on, with one tap to fix each. It can be hidden to a small bar, and has
 * Stop (the lights stay as they are) and Lights off now.
 */
@Composable
fun WindDownOverlay(home: HomeState, hiddenCalendars: Set<String>, viewModel: WindDownViewModel = viewModel()) {
    val active by WindDownService.active.collectAsStateWithLifecycle()
    val windDown = active ?: return
    val context = LocalContext.current
    val app = context.applicationContext as SmartDisplayApp
    val settings by app.alarms.windDown.collectAsStateWithLifecycle()
    val tomorrow by viewModel.tomorrow.collectAsStateWithLifecycle()
    val fixing by viewModel.fixing.collectAsStateWithLifecycle()
    val now by rememberMinuteClock()
    val nextAlarm = rememberNextAlarmText(now)
    var hidden by rememberSaveable(windDown.bedtimeMillis) { mutableStateOf(false) }

    val calendarIds = remember(home.entities, home.registry, hiddenCalendars) {
        home.calendars().map { it.entityId }.filterNot { it in hiddenCalendars }
    }
    val connected = home.status == ConnectionStatus.Connected
    LaunchedEffect(connected, calendarIds, windDown) {
        if (connected && calendarIds.isNotEmpty()) viewModel.loadTomorrow(calendarIds)
    }
    val checks = remember(home.entities, home.registry, settings.lights) {
        home.bedtimeChecks(ignore = settings.lights)
    }
    val bedtime = timeText(context, Instant.ofEpochMilli(windDown.bedtimeMillis).atZone(ZoneId.systemDefault()))

    if (hidden) {
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(16.dp).clickable { hidden = false },
            ) {
                Text(
                    stringResource(R.string.wind_down_bar, bedtime),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        return
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // A tap beside the panel hides it, rather than reaching the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { hidden = true }
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(stringResource(R.string.wind_down_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.wind_down_lights_off_at, bedtime),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                LinearProgressIndicator(
                    progress = { windDown.progress(nowMillis) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                Label(stringResource(R.string.wind_down_tomorrow))
                Line(
                    nextAlarm?.let { stringResource(R.string.wind_down_alarm, it) }
                        ?: stringResource(R.string.wind_down_no_alarm),
                )
                tomorrow?.takeIf { it.calendarIds == calendarIds }?.let { day ->
                    val first = day.first
                    Line(
                        when {
                            first == null -> stringResource(R.string.wind_down_no_events)
                            first.allDay -> stringResource(R.string.wind_down_all_day, first.summary)
                            else -> stringResource(
                                R.string.wind_down_first, timeText(context, first.start), first.summary,
                            )
                        },
                    )
                }

                Label(stringResource(R.string.wind_down_check))
                if (checks.isEmpty()) Line(stringResource(R.string.wind_down_all_set))
                val lightsOn = checks.filter { it.kind == CheckKind.LightOn }
                checks.forEach { check ->
                    CheckRow(check, busy = check.entityId in fixing) { viewModel.fix(listOf(it)) }
                }
                if (lightsOn.size > 1) {
                    TextButton(onClick = { viewModel.fix(lightsOn.mapNotNull { it.fix }) }) {
                        Text(stringResource(R.string.wind_down_all_lights_off, lightsOn.size))
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    TextButton(onClick = { hidden = true }) { Text(stringResource(R.string.wind_down_hide)) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { WindDownService.stop(context) }) {
                        Text(stringResource(R.string.wind_down_stop))
                    }
                    Button(onClick = { WindDownService.lightsOffNow(context) }) {
                        Text(stringResource(R.string.wind_down_lights_off_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun CheckRow(check: BedtimeCheck, busy: Boolean, onFix: (ServiceCall) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(check.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    when (check.kind) {
                        CheckKind.Unlocked -> R.string.check_unlocked
                        CheckKind.GarageOpen, CheckKind.Open -> R.string.check_open
                        CheckKind.LightOn, CheckKind.MediaOn -> R.string.check_on
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val fix = check.fix
        if (fix != null) {
            TextButton(onClick = { onFix(fix) }, enabled = !busy) {
                Text(
                    stringResource(
                        when (check.kind) {
                            CheckKind.Unlocked -> R.string.check_lock
                            CheckKind.GarageOpen -> R.string.check_close
                            else -> R.string.check_turn_off
                        },
                    ),
                )
            }
        }
    }
}

/** Starts a wind-down now, on displays that have one set up (not guest rooms). */
@Composable
fun WindDownButton() {
    val context = LocalContext.current
    val app = context.applicationContext as SmartDisplayApp
    val settings by app.alarms.windDown.collectAsStateWithLifecycle()
    val kiosk by app.kiosk.config.collectAsStateWithLifecycle()
    if (kiosk.isGuest || !settings.configured) return
    TextButton(onClick = { WindDownService.startNow(context) }) { Text(stringResource(R.string.wind_down_button)) }
}
