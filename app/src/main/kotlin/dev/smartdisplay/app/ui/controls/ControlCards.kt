package dev.smartdisplay.app.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How long a thermostat waits after the last − or + tap before sending, so five taps send one change. */
private const val SETPOINT_COMMIT_MS = 900L

/** How long a sent value is shown before falling back to Home Assistant's, if it never confirms it. */
private const val PENDING_TIMEOUT_MS = 5_000L

@Composable
fun ControlCard(control: Control, temperatureUnit: String?, onCall: (ServiceCall) -> Unit) {
    when (control.kind) {
        ControlKind.Light -> LightCard(control, onCall)
        ControlKind.Switch -> SwitchCard(control, onCall)
        ControlKind.Climate -> ClimateCard(control, temperatureUnit, onCall)
        ControlKind.MediaPlayer -> MediaCard(control, onCall)
    }
}

@Composable
private fun CardFrame(
    on: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = if (on) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 112.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .alpha(if (enabled) 1f else 0.5f),
            content = content,
        )
    }
    if (onClick != null) {
        Card(onClick, modifier, enabled = enabled, shape = CARD_SHAPE, colors = colors, content = body)
    } else {
        Card(modifier, shape = CARD_SHAPE, colors = colors, content = body)
    }
}

private val CARD_SHAPE = RoundedCornerShape(20.dp)

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun CardStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LightCard(control: Control, onCall: (ServiceCall) -> Unit) {
    val info = control.entity.lightInfo()
    val available = !control.unavailable
    CardFrame(on = info.on, enabled = available, onClick = { onCall(Calls.toggle(control)) }) {
        CardTitle(control.name)
        CardStatus(
            when {
                !available -> stringResource(R.string.control_unavailable)
                info.on && info.brightnessPercent != null ->
                    stringResource(R.string.control_on_percent, info.brightnessPercent)
                info.on -> stringResource(R.string.control_on)
                else -> stringResource(R.string.control_off)
            }
        )
        if (info.dimmable && available) {
            SentSlider(
                value = (info.brightnessPercent ?: 0) / 100f,
                onSend = { onCall(Calls.brightness(control.entityId, (it * 100).roundToInt())) },
            )
        }
    }
}

@Composable
private fun SwitchCard(control: Control, onCall: (ServiceCall) -> Unit) {
    val on = control.entity.state == "on"
    val available = !control.unavailable
    CardFrame(on = on, enabled = available, onClick = { onCall(Calls.toggle(control)) }) {
        Row(verticalAlignment = Alignment.Top) {
            CardTitle(control.name, Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { onCall(Calls.toggle(control)) }, enabled = available)
        }
        CardStatus(
            stringResource(
                when {
                    !available -> R.string.control_unavailable
                    on -> R.string.control_on
                    else -> R.string.control_off
                }
            )
        )
    }
}

@Composable
private fun MediaCard(control: Control, onCall: (ServiceCall) -> Unit) {
    val info = control.entity.mediaInfo()
    val available = !control.unavailable
    // The volume stays out of the way until the player's name is tapped.
    var showVolume by rememberSaveable(control.entityId) { mutableStateOf(false) }
    CardFrame(on = info.playing, enabled = available, onClick = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(
                control.name,
                Modifier
                    .weight(1f)
                    .clickable(enabled = available && info.canSetVolume) { showVolume = !showVolume },
            )
            if (available && info.active && info.canPlayPause) {
                FilledTonalIconButton(onClick = { onCall(Calls.playPause(control.entityId)) }) {
                    PlayPauseGlyph(playing = info.playing)
                }
            }
        }
        val nowPlaying = listOfNotNull(info.title, info.artist).joinToString(" – ").ifBlank { null }
        CardStatus(
            when {
                !available -> stringResource(R.string.control_unavailable)
                info.active && nowPlaying != null -> nowPlaying
                else -> mediaStateLabel(control.entity.state)
            }
        )
        if (showVolume && available && info.canSetVolume) {
            SentSlider(value = info.volume ?: 0f, onSend = { onCall(Calls.volume(control.entityId, it)) })
        }
    }
}

@Composable
private fun ClimateCard(control: Control, temperatureUnit: String?, onCall: (ServiceCall) -> Unit) {
    val info = control.entity.climateInfo(temperatureUnit)
    val available = !control.unavailable
    CardFrame(on = available && info.mode != "off", enabled = available, onClick = null) {
        Row(verticalAlignment = Alignment.Top) {
            CardTitle(control.name, Modifier.weight(1f))
            info.current?.let { CardStatus(stringResource(R.string.climate_now, formatSetpoint(it))) }
        }
        if (!available) {
            CardStatus(stringResource(R.string.control_unavailable))
            return@CardFrame
        }
        val low = info.targetLow
        val high = info.targetHigh
        when {
            info.target != null -> SetpointStepper(null, info.target, info) {
                onCall(Calls.temperature(control.entityId, it))
            }
            low != null && high != null -> {
                SetpointStepper(stringResource(R.string.climate_heat_to), low, info) {
                    onCall(Calls.temperatureRange(control.entityId, it, high))
                }
                SetpointStepper(stringResource(R.string.climate_cool_to), high, info) {
                    onCall(Calls.temperatureRange(control.entityId, low, it))
                }
            }
        }
        if (info.modes.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                info.modes.forEach { mode ->
                    FilterChip(
                        selected = mode == info.mode,
                        onClick = { if (mode != info.mode) onCall(Calls.hvacMode(control.entityId, mode)) },
                        label = { Text(hvacModeLabel(mode)) },
                    )
                }
            }
        }
    }
}

/**
 * − and + around a setpoint. Taps change a pending value shown straight away; it's sent once the taps stop, and
 * dropped when Home Assistant reports a new value (or after [PENDING_TIMEOUT_MS] if it never does).
 */
@Composable
private fun SetpointStepper(label: String?, value: Double, info: ClimateInfo, onCommit: (Double) -> Unit) {
    var pending by remember { mutableStateOf<Double?>(null) }
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(value) { pending = null }
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        delay(SETPOINT_COMMIT_MS)
        commit(target)
        delay(PENDING_TIMEOUT_MS)
        pending = null
    }
    val shown = pending ?: value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 64.dp))
        }
        OutlinedButton(onClick = { pending = info.adjust(shown, -1) }, enabled = shown > info.min) { Text("−") }
        Text(
            text = formatSetpoint(shown),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        OutlinedButton(onClick = { pending = info.adjust(shown, 1) }, enabled = shown < info.max) { Text("+") }
    }
}

/**
 * A slider that follows Home Assistant's value, except while dragging and just after: the dragged value is sent on
 * release and shown until Home Assistant reports the change (or [PENDING_TIMEOUT_MS] passes).
 */
@Composable
private fun SentSlider(value: Float, onSend: (Float) -> Unit) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    var sent by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(value) { sent = null }
    LaunchedEffect(sent) {
        if (sent != null) {
            delay(PENDING_TIMEOUT_MS)
            sent = null
        }
    }
    Slider(
        value = dragged ?: sent ?: value,
        onValueChange = { dragged = it },
        onValueChangeFinished = {
            dragged?.let {
                sent = it
                onSend(it)
            }
            dragged = null
        },
    )
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        if (playing) {
            val bar = Size(w * 0.28f, h * 0.9f)
            val radius = CornerRadius(w * 0.06f)
            drawRoundRect(color, topLeft = Offset(w * 0.12f, h * 0.05f), size = bar, cornerRadius = radius)
            drawRoundRect(color, topLeft = Offset(w * 0.60f, h * 0.05f), size = bar, cornerRadius = radius)
        } else {
            drawPath(Path().apply {
                moveTo(w * 0.22f, h * 0.05f)
                lineTo(w * 0.92f, h * 0.5f)
                lineTo(w * 0.22f, h * 0.95f)
                close()
            }, color)
        }
    }
}

@Composable
private fun mediaStateLabel(state: String): String = when (state) {
    "off" -> stringResource(R.string.control_off)
    "on" -> stringResource(R.string.control_on)
    "idle" -> stringResource(R.string.media_idle)
    "standby" -> stringResource(R.string.media_standby)
    "playing" -> stringResource(R.string.media_playing)
    "paused" -> stringResource(R.string.media_paused)
    "buffering" -> stringResource(R.string.media_buffering)
    else -> state.replaceFirstChar { it.titlecase(LocalConfiguration.current.locales[0]) }
}

@Composable
private fun hvacModeLabel(mode: String): String = when (mode) {
    "off" -> stringResource(R.string.control_off)
    "heat" -> stringResource(R.string.hvac_heat)
    "cool" -> stringResource(R.string.hvac_cool)
    "heat_cool" -> stringResource(R.string.hvac_heat_cool)
    "auto" -> stringResource(R.string.hvac_auto)
    "dry" -> stringResource(R.string.hvac_dry)
    "fan_only" -> stringResource(R.string.hvac_fan_only)
    else -> mode.replace('_', ' ').replaceFirstChar { it.titlecase(LocalConfiguration.current.locales[0]) }
}
