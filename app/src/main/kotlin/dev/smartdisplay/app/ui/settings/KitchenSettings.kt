package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.dashboard.runnables

/** The kitchen dashboard's buttons, and what its Home and Away buttons run. */
@Composable
fun KitchenSettings(kitchen: KitchenConfig, home: HomeState, onChange: (KitchenConfig) -> Unit) {
    var choosing by remember { mutableStateOf<KitchenChoice?>(null) }
    val runnables = remember(home.entities, home.registry) { home.runnables() }
    val items = runnables.map { it.entityId to it.friendlyName }
    fun nameOf(entityId: String) = home.entities[entityId]?.friendlyName ?: entityId

    Heading(stringResource(R.string.kitchen_buttons))
    PanelBody(
        if (kitchen.shortcuts.isEmpty()) {
            stringResource(R.string.kitchen_buttons_body) + " " + stringResource(R.string.kitchen_buttons_none)
        } else {
            kitchen.shortcuts.joinToString(", ", transform = ::nameOf)
        },
        topPadding = 2.dp,
    )
    OutlinedButton(onClick = { choosing = KitchenChoice.Buttons }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.kitchen_buttons_choose))
    }

    Heading(stringResource(R.string.kitchen_modes))
    PanelBody(stringResource(R.string.kitchen_modes_body), topPadding = 2.dp)
    val notSet = stringResource(R.string.kitchen_not_set)
    val homeRuns = kitchen.homeAction?.let(::nameOf) ?: notSet
    val awayRuns = kitchen.awayAction?.let(::nameOf) ?: notSet
    PanelBody(stringResource(R.string.kitchen_home_runs, homeRuns), topPadding = 8.dp)
    PanelBody(stringResource(R.string.kitchen_away_runs, awayRuns), topPadding = 2.dp)
    Row(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { choosing = KitchenChoice.Home }) {
            Text(stringResource(R.string.kitchen_choose_home))
        }
        OutlinedButton(onClick = { choosing = KitchenChoice.Away }, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.kitchen_choose_away))
        }
        if (kitchen.homeAction != null || kitchen.awayAction != null) {
            TextButton(onClick = { onChange(kitchen.copy(homeAction = null, awayAction = null)) }) {
                Text(stringResource(R.string.kitchen_clear))
            }
        }
    }

    when (choosing) {
        KitchenChoice.Buttons -> ChoiceDialog(
            title = stringResource(R.string.kitchen_buttons_choose),
            items = items,
            selected = kitchen.shortcuts.toSet(),
            multiple = true,
            // Ticked buttons go on the end, so the dashboard shows them in the order they were chosen.
            onChange = { id, on ->
                onChange(kitchen.copy(shortcuts = if (on) kitchen.shortcuts - id + id else kitchen.shortcuts - id))
            },
            onDismiss = { choosing = null },
        )
        KitchenChoice.Home -> ChoiceDialog(
            title = stringResource(R.string.kitchen_choose_home),
            items = items,
            selected = setOfNotNull(kitchen.homeAction),
            multiple = false,
            onChange = { id, _ ->
                onChange(kitchen.copy(homeAction = id))
                choosing = null
            },
            onDismiss = { choosing = null },
        )
        KitchenChoice.Away -> ChoiceDialog(
            title = stringResource(R.string.kitchen_choose_away),
            items = items,
            selected = setOfNotNull(kitchen.awayAction),
            multiple = false,
            onChange = { id, _ ->
                onChange(kitchen.copy(awayAction = id))
                choosing = null
            },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class KitchenChoice { Buttons, Home, Away }

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}
