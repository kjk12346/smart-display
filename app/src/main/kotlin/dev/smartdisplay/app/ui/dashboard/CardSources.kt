package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.CardType
import dev.smartdisplay.app.kiosk.DashCard
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.ui.ambient.weatherEntities
import dev.smartdisplay.app.ui.controls.rooms
import dev.smartdisplay.app.ui.settings.ChoiceDialog
import dev.smartdisplay.app.ui.settings.SettingRow
import dev.smartdisplay.app.ui.settings.WhoseCalendarDialog
import dev.smartdisplay.app.ui.settings.whoseCalendarName

/** The choice for "none" in a list of entities. */
private const val NONE = ""

/** Ticked items go on the end, so cards show them in the order they were chosen. */
private fun List<String>.toggled(id: String, on: Boolean): List<String> = if (on) this - id + id else this - id

/**
 * What [card] shows. Calendar, Who's home and Buttons change the display's [KitchenConfig] (the same choices as in
 * Settings); the other cards keep their own, in [DashCard].
 */
@Composable
internal fun CardSourcesDialog(
    card: DashCard,
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onCardChange: (DashCard) -> Unit,
    onDismiss: () -> Unit,
) {
    fun nameOf(entityId: String) = home.entities[entityId]?.friendlyName ?: entityId
    when (card.type) {
        CardType.Calendar -> CalendarSources(kitchen, home, onKitchenChange, onDismiss)
        CardType.People -> PeopleSources(kitchen, home, onKitchenChange, onDismiss)
        CardType.Buttons -> ButtonsSources(kitchen, home, onKitchenChange, onDismiss)
        CardType.Devices -> DevicesSources(card, home, onCardChange, onDismiss)
        CardType.Weather -> WeatherSourceDialog(kitchen, home, onKitchenChange, onDismiss)
        // Set in Settings, with the network's password; nothing to choose here.
        CardType.Wifi -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.card_wifi)) },
            text = { Text(stringResource(R.string.card_wifi_sources)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
        )
        CardType.Sensors -> ChoiceDialog(
            title = stringResource(R.string.card_sensors_choose),
            items = home.choosable("sensor", "binary_sensor").map { it.entityId to it.friendlyName },
            selected = card.entities.toSet(),
            multiple = true,
            onChange = { id, on -> onCardChange(card.copy(entities = card.entities.toggled(id, on))) },
            onDismiss = onDismiss,
        )
        CardType.NowPlaying, CardType.Camera -> ChoiceDialog(
            title = stringResource(
                if (card.type == CardType.Camera) R.string.card_camera_choose else R.string.card_player_choose,
            ),
            items = home.choosable(if (card.type == CardType.Camera) "camera" else "media_player")
                .map { it.entityId to nameOf(it.entityId) },
            selected = card.entities.take(1).toSet(),
            multiple = false,
            onChange = { id, _ ->
                onCardChange(card.copy(entities = listOf(id)))
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** Each calendar, and whose it is (or that it isn't shown). */
@Composable
private fun CalendarSources(
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var whose by remember { mutableStateOf<String?>(null) }
    val calendars = home.calendars()
    SourcesDialog(stringResource(R.string.kitchen_calendars), onDismiss) {
        if (calendars.isEmpty()) Text(stringResource(R.string.kitchen_calendars_none))
        calendars.forEach { calendar ->
            SettingRow(calendar.friendlyName, whoseCalendarName(calendar.entityId, kitchen, home)) {
                whose = calendar.entityId
            }
        }
    }
    whose?.let { WhoseCalendarDialog(it, kitchen, home, onKitchenChange, onDismiss = { whose = null }) }
}

private enum class PeopleChoice { Shown, Home, Away }

/** Who's shown, and what the Home and Away buttons run. */
@Composable
private fun PeopleSources(
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by remember { mutableStateOf<PeopleChoice?>(null) }
    val people = home.people().sortedBy { it.name.lowercase() }
    val shown = people.filterNot { it.entityId in kitchen.hiddenPeople }
    val runnables = home.runnables().map { it.entityId to it.friendlyName }
    val nothing = stringResource(R.string.kitchen_not_set)
    fun nameOf(entityId: String?) = entityId?.let { home.entities[it]?.friendlyName ?: it } ?: nothing

    SourcesDialog(stringResource(R.string.dashboard_people), onDismiss) {
        SettingRow(
            stringResource(R.string.card_people_shown),
            if (shown.size == people.size) {
                stringResource(R.string.card_everyone)
            } else {
                shown.joinToString(", ") { it.name }
            },
        ) { choosing = PeopleChoice.Shown }
        SettingRow(stringResource(R.string.kitchen_choose_home), nameOf(kitchen.homeAction)) {
            choosing = PeopleChoice.Home
        }
        SettingRow(stringResource(R.string.kitchen_choose_away), nameOf(kitchen.awayAction)) {
            choosing = PeopleChoice.Away
        }
    }
    when (choosing) {
        PeopleChoice.Shown -> ChoiceDialog(
            title = stringResource(R.string.card_people_shown),
            items = people.map { it.entityId to it.name },
            selected = shown.map { it.entityId }.toSet(),
            multiple = true,
            onChange = { id, on ->
                onKitchenChange(
                    kitchen.copy(hiddenPeople = if (on) kitchen.hiddenPeople - id else kitchen.hiddenPeople + id),
                )
            },
            onDismiss = { choosing = null },
        )
        PeopleChoice.Home, PeopleChoice.Away -> {
            val isHome = choosing == PeopleChoice.Home
            ChoiceDialog(
                title = stringResource(if (isHome) R.string.kitchen_choose_home else R.string.kitchen_choose_away),
                items = listOf(NONE to nothing) + runnables,
                selected = setOf((if (isHome) kitchen.homeAction else kitchen.awayAction) ?: NONE),
                multiple = false,
                onChange = { id, _ ->
                    val action = id.takeIf { it != NONE }
                    onKitchenChange(
                        if (isHome) kitchen.copy(homeAction = action) else kitchen.copy(awayAction = action),
                    )
                    choosing = null
                },
                onDismiss = { choosing = null },
            )
        }
        null -> Unit
    }
}

private enum class DevicesChoice { Room, Others }

/** A room whose controls all show, and other devices chosen one by one. */
@Composable
private fun DevicesSources(card: DashCard, home: HomeState, onCardChange: (DashCard) -> Unit, onDismiss: () -> Unit) {
    var choosing by remember { mutableStateOf<DevicesChoice?>(null) }
    val rooms = home.rooms()
    val none = stringResource(R.string.kitchen_not_set)
    val roomName = card.areaId?.let { id -> rooms.firstOrNull { it.id == id }?.name } ?: none
    val controls = rooms.flatMap { room -> room.controls.map { room to it } }
    val others = card.entities.mapNotNull { id -> controls.firstOrNull { it.second.entityId == id }?.second?.name }

    SourcesDialog(stringResource(R.string.card_devices), onDismiss) {
        SettingRow(stringResource(R.string.card_devices_room), roomName) { choosing = DevicesChoice.Room }
        SettingRow(stringResource(R.string.card_devices_others), others.joinToString(", ").ifEmpty { none }) {
            choosing = DevicesChoice.Others
        }
    }
    val otherRoom = stringResource(R.string.controls_other_room)
    when (choosing) {
        DevicesChoice.Room -> ChoiceDialog(
            title = stringResource(R.string.card_devices_room),
            items = listOf(NONE to none) + rooms.mapNotNull { room -> room.id?.let { it to (room.name ?: it) } },
            selected = setOf(card.areaId ?: NONE),
            multiple = false,
            onChange = { id, _ ->
                onCardChange(card.copy(areaId = id.takeIf { it != NONE }))
                choosing = null
            },
            onDismiss = { choosing = null },
        )
        DevicesChoice.Others -> ChoiceDialog(
            title = stringResource(R.string.card_devices_others),
            items = controls.map { (room, control) ->
                control.entityId to "${room.name ?: otherRoom} · ${control.name}"
            },
            selected = card.entities.toSet(),
            multiple = true,
            onChange = { id, on -> onCardChange(card.copy(entities = card.entities.toggled(id, on))) },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

/** The dashboard's weather: the first weather entity, or one chosen. */
@Composable
internal fun WeatherSourceDialog(
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.card_weather_choose),
        items = listOf(NONE to stringResource(R.string.card_weather_first)) +
            home.weatherEntities().map { it.entityId to it.friendlyName },
        selected = setOf(kitchen.weatherEntity ?: NONE),
        multiple = false,
        onChange = { id, _ ->
            onKitchenChange(kitchen.copy(weatherEntity = id.takeIf { it != NONE }))
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** A dialog of setting rows, closed with Done. */
@Composable
private fun SourcesDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}
