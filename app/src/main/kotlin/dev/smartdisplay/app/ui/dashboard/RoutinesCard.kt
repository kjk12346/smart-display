package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.Reward
import dev.smartdisplay.app.kiosk.Routine
import dev.smartdisplay.app.kiosk.RoutineBook
import dev.smartdisplay.app.kiosk.personColors
import dev.smartdisplay.app.ui.settings.SettingRow
import dev.smartdisplay.app.ui.theme.OnPersonColor
import dev.smartdisplay.app.ui.theme.color
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * Today's routines by person: tap one when it's done to earn its points (and tap again to undo, today). Rewards spends
 * them. What the routines and rewards are is set in the card's Sources, behind the PIN, so nobody gives themselves
 * more; ticking off and spending aren't.
 */
@Composable
internal fun RoutinesCard(
    kitchen: KitchenConfig,
    people: List<CalendarOwner>,
    onKitchenChange: (KitchenConfig) -> Unit,
    today: LocalDate,
    modifier: Modifier,
    bounded: Boolean,
) {
    val book = kitchen.routineBook
    var rewards by remember { mutableStateOf(false) }
    val doing = people.filter { book.routinesFor(it.id, today).isNotEmpty() }
    ActionCard(
        title = stringResource(R.string.card_routines),
        action = stringResource(R.string.routines_rewards).takeIf { book.rewards.isNotEmpty() },
        onAction = { rewards = true },
        modifier = modifier,
        scroll = bounded,
    ) {
        if (doing.isEmpty()) {
            EmptyNote(
                stringResource(if (book.routines.isEmpty()) R.string.routines_none else R.string.routines_none_today),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            doing.forEach { person ->
                PersonRoutines(person, book, today) { routine ->
                    onKitchenChange(kitchen.copy(routineBook = book.toggled(routine, person.id, today)))
                }
            }
        }
    }
    if (rewards) {
        RewardsDialog(book, people, today, onChange = { onKitchenChange(kitchen.copy(routineBook = it)) }) {
            rewards = false
        }
    }
}

@Composable
private fun PersonRoutines(person: CalendarOwner, book: RoutineBook, today: LocalDate, onToggle: (Routine) -> Unit) {
    val (done, total) = book.progress(person.id, today)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(person.color))
            Text(
                person.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text(
                stringResource(R.string.routines_progress, done, total, book.balances[person.id] ?: 0),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (done == total) {
            Text(
                stringResource(R.string.routines_all_done, person.name),
                style = MaterialTheme.typography.bodyMedium,
                color = person.color,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        book.routinesFor(person.id, today).forEach { routine ->
            val isDone = book.isDone(routine, person.id, today)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onToggle(routine) }.padding(vertical = 5.dp),
            ) {
                DoneMark(isDone, person.color)
                routine.emoji?.let { Text(it, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp)) }
                Text(
                    routine.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text(
                    "+${routine.points}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Everyone's points, the rewards they can spend them on, and the latest changes. */
@Composable
private fun RewardsDialog(
    book: RoutineBook,
    people: List<CalendarOwner>,
    today: LocalDate,
    onChange: (RoutineBook) -> Unit,
    onDismiss: () -> Unit,
) {
    val earners = people.filter { person -> book.routines.any { person.id in it.people } || person.id in book.balances }
    var who by remember { mutableStateOf(earners.firstOrNull()?.id) }
    var confirming by remember { mutableStateOf<Reward?>(null) }
    val names = people.associate { it.id to it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routines_rewards)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    earners.forEach { person ->
                        FilterChip(
                            selected = who == person.id,
                            onClick = { who = person.id },
                            label = {
                                Text(stringResource(R.string.routines_balance, person.name, book.balances[person.id] ?: 0))
                            },
                        )
                    }
                }
                book.rewards.forEach { reward ->
                    val enough = who != null && (book.balances[who] ?: 0) >= reward.cost
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(reward.emoji ?: "🎁", fontSize = 22.sp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(reward.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                pluralStringResource(R.plurals.routines_points, reward.cost, reward.cost),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { confirming = reward }, enabled = enough) {
                            Text(stringResource(R.string.routines_redeem))
                        }
                    }
                }
                val locale = LocalConfiguration.current.locales[0]
                val recent = book.history.take(HISTORY_SHOWN)
                if (recent.isNotEmpty()) {
                    Text(
                        stringResource(R.string.routines_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    recent.forEach { entry ->
                        val sign = if (entry.delta > 0) "+" else ""
                        Text(
                            "${dayName(entry.date, locale)} · ${names[entry.person] ?: entry.person} · " +
                                "$sign${entry.delta} · ${entry.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
    confirming?.let { reward ->
        val person = who
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.routines_redeem_title, reward.title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.routines_redeem_body,
                        reward.cost,
                        reward.cost,
                        names[person].orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (person != null) onChange(book.redeemed(reward, person, today))
                        confirming = null
                    },
                ) { Text(stringResource(R.string.routines_redeem)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val HISTORY_SHOWN = 8

/** The routines and rewards, and points given or taken by hand: the card's Sources, behind the PIN. */
@Composable
internal fun RoutinesSources(
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val book = kitchen.routineBook
    fun save(changed: RoutineBook) = onKitchenChange(kitchen.copy(routineBook = changed))
    val colors = personColors(home.people().map { it.entityId }, kitchen.personColors)
    val people = home.people().sortedBy { it.name.lowercase() }.map {
        CalendarOwner(it.entityId, it.name.substringBefore(' '), colors[it.entityId]?.color() ?: Color.Gray)
    }
    val today = LocalDate.now()
    var routine by remember { mutableStateOf<Routine?>(null) }
    var addingRoutine by remember { mutableStateOf(false) }
    var reward by remember { mutableStateOf<Reward?>(null) }
    var addingReward by remember { mutableStateOf(false) }
    val adjustLabel = stringResource(R.string.routines_adjusted)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_routines)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                book.routines.forEach { item ->
                    SettingRow(
                        listOfNotNull(item.emoji, item.title).joinToString(" "),
                        item.people.mapNotNull { id -> people.firstOrNull { it.id == id }?.name }.joinToString(", ") +
                            " · +${item.points}",
                    ) { routine = item }
                }
                TextButton(onClick = { addingRoutine = true }) { Text(stringResource(R.string.routines_add)) }
                Text(
                    stringResource(R.string.routines_rewards),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                book.rewards.forEach { item ->
                    SettingRow(
                        listOfNotNull(item.emoji, item.title).joinToString(" "),
                        pluralStringResource(R.plurals.routines_points, item.cost, item.cost),
                    ) { reward = item }
                }
                TextButton(onClick = { addingReward = true }) { Text(stringResource(R.string.routines_add_reward)) }
                if (people.isNotEmpty()) {
                    Text(
                        stringResource(R.string.routines_points_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    people.forEach { person ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(person.name, Modifier.weight(1f))
                            TextButton(onClick = { save(book.adjusted(person.id, -1, adjustLabel, today)) }) { Text("−") }
                            Text((book.balances[person.id] ?: 0).toString())
                            TextButton(onClick = { save(book.adjusted(person.id, 1, adjustLabel, today)) }) { Text("+") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
    if (addingRoutine) {
        RoutineEditor(
            null,
            people,
            onSave = {
                save(book.copy(routines = book.routines + it))
                addingRoutine = false
            },
            onDelete = null,
            onDismiss = { addingRoutine = false },
        )
    }
    routine?.let { item ->
        RoutineEditor(
            item,
            people,
            onSave = { changed ->
                save(book.copy(routines = book.routines.map { if (it.id == item.id) changed else it }))
                routine = null
            },
            onDelete = {
                save(book.copy(routines = book.routines.filterNot { it.id == item.id }))
                routine = null
            },
            onDismiss = { routine = null },
        )
    }
    if (addingReward) {
        RewardEditor(
            null,
            onSave = {
                save(book.copy(rewards = book.rewards + it))
                addingReward = false
            },
            onDelete = null,
            onDismiss = { addingReward = false },
        )
    }
    reward?.let { item ->
        RewardEditor(
            item,
            onSave = { changed ->
                save(book.copy(rewards = book.rewards.map { if (it.id == item.id) changed else it }))
                reward = null
            },
            onDelete = {
                save(book.copy(rewards = book.rewards.filterNot { it.id == item.id }))
                reward = null
            },
            onDismiss = { reward = null },
        )
    }
}

@Composable
private fun RoutineEditor(
    routine: Routine?,
    people: List<CalendarOwner>,
    onSave: (Routine) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(routine?.title.orEmpty()) }
    var emoji by remember { mutableStateOf(routine?.emoji) }
    var points by remember { mutableStateOf(routine?.points ?: 1) }
    val who = remember { mutableStateListOf(*routine?.people.orEmpty().toTypedArray()) }
    val days = remember { mutableStateListOf(*(routine?.days ?: DayOfWeek.entries.toSet()).toTypedArray()) }
    val locale = LocalConfiguration.current.locales[0]
    val first = WeekFields.of(locale).firstDayOfWeek
    EditorDialog(
        title = stringResource(if (routine == null) R.string.routines_add else R.string.routines_edit),
        canSave = title.isNotBlank() && who.isNotEmpty() && days.isNotEmpty(),
        onSave = {
            val id = routine?.id ?: "routine-${System.currentTimeMillis()}"
            onSave(Routine(id, title.trim(), emoji, who.toList(), days.toSet(), points))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(MAX_TITLE) },
            label = { Text(stringResource(R.string.routines_what)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.routines_who), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { person ->
                val on = person.id in who
                FilterChip(
                    selected = on,
                    onClick = { if (on) who.remove(person.id) else who.add(person.id) },
                    label = { Text(person.name) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (on) OnPersonColor else person.color),
                        )
                    },
                )
            }
        }
        Text(stringResource(R.string.routines_days), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0L until 7L).map { first.plus(it) }.forEach { day ->
                val on = day in days
                FilterChip(
                    selected = on,
                    onClick = { if (on) days.remove(day) else days.add(day) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                )
            }
        }
        Stepper(stringResource(R.string.routines_points_title), points, 1, MAX_POINTS) { points = it }
        EmojiPicker(emoji) { emoji = it }
    }
}

@Composable
private fun RewardEditor(
    reward: Reward?,
    onSave: (Reward) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(reward?.title.orEmpty()) }
    var emoji by remember { mutableStateOf(reward?.emoji) }
    var cost by remember { mutableStateOf(reward?.cost ?: 10) }
    EditorDialog(
        title = stringResource(if (reward == null) R.string.routines_add_reward else R.string.routines_edit_reward),
        canSave = title.isNotBlank(),
        onSave = {
            val id = reward?.id ?: "reward-${System.currentTimeMillis()}"
            onSave(Reward(id, title.trim(), emoji, cost))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(MAX_TITLE) },
            label = { Text(stringResource(R.string.routines_reward_what)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Stepper(stringResource(R.string.routines_cost), cost, 1, MAX_COST, step = 5) { cost = it }
        EmojiPicker(emoji) { emoji = it }
    }
}

/** A number with buttons to change it. */
@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

private const val MAX_POINTS = 100
private const val MAX_COST = 1_000
