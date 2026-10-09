package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.alarm.FALLBACK_BEDTIME_CHOICES
import dev.smartdisplay.app.alarm.SLEEP_CHOICES
import dev.smartdisplay.app.alarm.WIND_DOWN_CHOICES
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ui.common.PanelBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Domains of things that can be turned off at the start of a wind-down. */
private val HOUSE_OFF_DOMAINS = setOf("light", "switch", "media_player", "fan", "climate")

/**
 * The evening wind-down: whether it starts by itself before bedtime, the lights that dim and go off, how long it
 * takes, when bedtime is (from the next morning's alarm, or a fixed time), and what else goes off when it starts.
 */
@Composable
fun WindDownSettings(home: HomeState) {
    val context = LocalContext.current
    val alarms = (context.applicationContext as SmartDisplayApp).alarms
    val settings by alarms.windDown.collectAsStateWithLifecycle()
    val alarmList by alarms.alarms.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<WindDownChoice?>(null) }
    fun nameOf(entityId: String) = home.entities[entityId]?.friendlyName ?: entityId
    fun items(domains: Set<String>) = home.entities.values
        .filter { it.domain in domains }
        .filterNot { home.registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
        .map { it.entityId to it.friendlyName }
        .sortedBy { it.second.lowercase() }

    PanelBody(stringResource(R.string.wind_down_settings_body), topPadding = 2.dp)

    Heading(stringResource(R.string.wind_down_lights))
    PanelBody(
        if (settings.lights.isEmpty()) {
            stringResource(R.string.wind_down_lights_none)
        } else {
            settings.lights.map(::nameOf).sorted().joinToString(", ")
        },
        topPadding = 2.dp,
    )
    OutlinedButton(onClick = { choosing = WindDownChoice.Lights }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.wind_down_lights_choose))
    }

    Heading(stringResource(R.string.wind_down_length))
    Chips(WIND_DOWN_CHOICES, settings.minutes, { stringResource(R.string.settings_dim_minutes, it) }) {
        alarms.setWindDown(settings.copy(minutes = it))
    }

    SwitchRow(
        title = stringResource(R.string.wind_down_auto),
        description = stringResource(R.string.wind_down_auto_body),
        checked = settings.enabled,
        onCheckedChange = { alarms.setWindDown(settings.copy(enabled = it)) },
    )
    if (settings.enabled) {
        Heading(stringResource(R.string.wind_down_sleep))
        Chips(SLEEP_CHOICES, settings.sleepMinutes, { minutes ->
            val hours = minutes / 60
            if (minutes % 60 == 0) {
                stringResource(R.string.wind_down_hours, hours)
            } else {
                stringResource(R.string.wind_down_hours_half, hours)
            }
        }) { alarms.setWindDown(settings.copy(sleepMinutes = it)) }

        Heading(stringResource(R.string.wind_down_no_alarm_nights))
        Chips(FALLBACK_BEDTIME_CHOICES, settings.fallbackBedtime, { time ->
            if (time == null) stringResource(R.string.wind_down_not_automatic) else timeText(context, time)
        }) { alarms.setWindDown(settings.copy(fallbackBedtime = it)) }

        // Recomputed whenever the alarms or the settings change.
        val next = remember(settings, alarmList) { alarms.nextWindDown() }
        PanelBody(
            if (next == null) {
                stringResource(R.string.wind_down_next_none)
            } else {
                val zone = ZoneId.systemDefault()
                val start = Instant.ofEpochMilli(next.startMillis).atZone(zone)
                val bedtime = Instant.ofEpochMilli(next.bedtimeMillis).atZone(zone)
                val day = start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                stringResource(R.string.wind_down_next, day, timeText(context, start), timeText(context, bedtime))
            },
            topPadding = 12.dp,
        )
    }

    Heading(stringResource(R.string.wind_down_house_off))
    PanelBody(
        if (settings.houseOff.isEmpty()) {
            stringResource(R.string.wind_down_house_off_none)
        } else {
            settings.houseOff.map(::nameOf).sorted().joinToString(", ")
        },
        topPadding = 2.dp,
    )
    OutlinedButton(onClick = { choosing = WindDownChoice.HouseOff }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.wind_down_house_off_choose))
    }

    when (choosing) {
        WindDownChoice.Lights -> ChoiceDialog(
            title = stringResource(R.string.wind_down_lights_choose),
            items = items(setOf("light")),
            selected = settings.lights,
            multiple = true,
            onChange = { id, on ->
                alarms.setWindDown(settings.copy(lights = if (on) settings.lights + id else settings.lights - id))
            },
            onDismiss = { choosing = null },
        )
        WindDownChoice.HouseOff -> ChoiceDialog(
            title = stringResource(R.string.wind_down_house_off_choose),
            items = items(HOUSE_OFF_DOMAINS).filterNot { it.first in settings.lights },
            selected = settings.houseOff,
            multiple = true,
            onChange = { id, on ->
                alarms.setWindDown(settings.copy(houseOff = if (on) settings.houseOff + id else settings.houseOff - id))
            },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class WindDownChoice { Lights, HouseOff }

@Composable
private fun <T> Chips(choices: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
    ) {
        choices.forEach { choice ->
            FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(label(choice)) })
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}
