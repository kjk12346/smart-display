package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.ui.settings.ChoiceDialog

/** Words for something left open or on: worth a look, so tinted, but not a problem like [StatusChip.alert]. */
private val ATTENTION = setOf(StatusWord.Open, StatusWord.Unlocked, StatusWord.Detected, StatusWord.On)

/** The status bar: a small chip for each of [chips], wrapping onto a second line if it must. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusBar(
    chips: List<StatusChip>,
    modifier: Modifier = Modifier,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
) {
    FlowRow(
        horizontalArrangement = arrangement,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxLines = 2,
        modifier = modifier,
    ) {
        chips.forEach { StatusChipView(it) }
    }
}

/**
 * A chip: a dot, the name and the state, small enough that several fit beside the weather. Something open, unlocked
 * or on is tinted (its dot lit), a problem is red, and the rest (closed, locked, a sensor's value) stays quiet.
 */
@Composable
private fun StatusChipView(chip: StatusChip) {
    val colors = MaterialTheme.colorScheme
    val (background, content) = when {
        chip.alert -> colors.errorContainer to colors.onErrorContainer
        chip.word in ATTENTION -> colors.primaryContainer to colors.onPrimaryContainer
        else -> dashCardColor() to colors.onSurface
    }
    val dot = when {
        chip.alert || chip.word in ATTENTION -> content
        else -> content.copy(alpha = 0.35f)
    }
    Surface(shape = CircleShape, color = background, contentColor = content) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 30.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (chip.word != null) {
                Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(dot))
            }
            Text(
                chip.name,
                style = MaterialTheme.typography.labelMedium,
                color = content.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 5.dp).widthIn(max = 140.dp),
            )
            Text(
                chip.value ?: chip.word?.let { stringResource(it.label()) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private fun StatusWord.label(): Int = when (this) {
    StatusWord.Open -> R.string.sensor_open
    StatusWord.Closed -> R.string.sensor_closed
    StatusWord.Detected -> R.string.sensor_detected
    StatusWord.Clear -> R.string.sensor_clear
    StatusWord.Wet -> R.string.sensor_wet
    StatusWord.Dry -> R.string.sensor_dry
    StatusWord.On -> R.string.sensor_on
    StatusWord.Off -> R.string.sensor_off
    StatusWord.Locked -> R.string.status_locked
    StatusWord.Unlocked -> R.string.status_unlocked
    StatusWord.Jammed -> R.string.status_jammed
    StatusWord.Disarmed -> R.string.status_disarmed
    StatusWord.ArmedHome -> R.string.status_armed_home
    StatusWord.ArmedAway -> R.string.status_armed_away
    StatusWord.ArmedNight -> R.string.status_armed_night
    StatusWord.Armed -> R.string.status_armed
    StatusWord.Arming -> R.string.status_arming
    StatusWord.Triggered -> R.string.status_triggered
}

/** What the status bar shows: chosen sensors, doors, locks, covers and alarm panels, in the order ticked. */
@Composable
internal fun StatusSourcesDialog(
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.status_choose),
        note = stringResource(R.string.status_note),
        items = home.choosable(*STATUS_DOMAINS).map { it.entityId to it.friendlyName },
        selected = kitchen.statusEntities.toSet(),
        multiple = true,
        onChange = { id, on ->
            val ids = kitchen.statusEntities - id
            onKitchenChange(kitchen.copy(statusEntities = if (on) ids + id else ids))
        },
        onDismiss = onDismiss,
    )
}
