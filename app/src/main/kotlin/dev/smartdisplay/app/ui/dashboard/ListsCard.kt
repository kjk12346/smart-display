package dev.smartdisplay.app.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.DashCard
import dev.smartdisplay.app.ui.theme.OnPersonColor
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A line on one of Home Assistant's to-do lists. */
data class TodoItem(val uid: String, val summary: String, val done: Boolean)

/** The items from Home Assistant's `todo/item/list` command. Items it can't identify are left out. */
fun parseTodoItems(result: JsonElement): List<TodoItem> {
    val items = (result as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
    return items.filterIsInstance<JsonObject>().mapNotNull { item ->
        val uid = (item["uid"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val summary = (item["summary"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        TodoItem(uid, summary, (item["status"] as? JsonPrimitive)?.content == "completed")
    }
}

/** To do first, in the list's order, then what's done. */
fun List<TodoItem>.toShow(): List<TodoItem> = sortedBy { it.done }

/** Keeps the Lists cards' items, and changes them on Home Assistant, so the phone app and others see the same. */
class ListsViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home
    private val _items = MutableStateFlow<Map<String, List<TodoItem>>>(emptyMap())

    /** Each list's items, by entity ID, once fetched. */
    val items: StateFlow<Map<String, List<TodoItem>>> = _items.asStateFlow()

    private val _failed = MutableStateFlow(false)

    /** The last change couldn't be saved. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** Fetches [lists] now and then every so often, for changes made elsewhere, until cancelled. */
    suspend fun keepFresh(lists: List<String>) {
        while (true) {
            lists.forEach { fetch(it) }
            delay(REFRESH_MS)
        }
    }

    private suspend fun fetch(list: String) {
        try {
            val result = home.command("todo/item/list", buildJsonObject { put("entity_id", list) })
            _items.update { it + (list to parseTodoItems(result)) }
        } catch (e: IOException) {
            Log.w(TAG, "Reading $list failed", e)
        }
    }

    fun add(list: String, text: String) = change(list, "add_item", buildJsonObject { put("item", text.trim()) })

    fun setDone(list: String, item: TodoItem, done: Boolean) {
        // Shows at once; the list is read back after.
        _items.update { lists ->
            lists + (list to lists[list].orEmpty().map { if (it.uid == item.uid) it.copy(done = done) else it })
        }
        change(
            list,
            "update_item",
            buildJsonObject {
                put("item", item.uid)
                put("status", if (done) "completed" else "needs_action")
            },
        )
    }

    fun clearDone(list: String) = change(list, "remove_completed_items", JsonObject(emptyMap()))

    private fun change(list: String, service: String, data: JsonObject) {
        viewModelScope.launch {
            _failed.value = false
            try {
                home.callService("todo", service, data, target = buildJsonObject { put("entity_id", list) })
            } catch (e: IOException) {
                Log.w(TAG, "todo.$service on $list failed", e)
                _failed.value = true
            }
            fetch(list)
        }
    }

    private companion object {
        const val TAG = "ListsViewModel"
        const val REFRESH_MS = 60_000L
    }
}

/**
 * One or more of Home Assistant's to-do lists, such as its shopping list: add a line, tick it off, clear what's done.
 * [bounded] is a card of fixed size on the grid; its lines scroll by themselves.
 */
@Composable
internal fun ListsCard(
    card: DashCard,
    home: HomeState,
    modifier: Modifier,
    bounded: Boolean,
    viewModel: ListsViewModel = viewModel(),
) {
    val lists = card.entities.filter { it in home.entities }
    val allItems by viewModel.items.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val connected = home.status == ConnectionStatus.Connected
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, lists) {
        if (connected && lists.isNotEmpty()) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepFresh(lists) }
        }
    }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val list = picked?.takeIf { it in lists } ?: lists.firstOrNull()
    val title = when {
        list != null && lists.size == 1 -> home.entities[list]?.friendlyName ?: list
        else -> stringResource(R.string.card_lists)
    }
    TitledCard(title, modifier, scroll = false) {
        if (list == null) {
            Text(
                stringResource(R.string.card_lists_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TitledCard
        }
        if (lists.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            ) {
                lists.forEach { id ->
                    FilterChip(
                        selected = id == list,
                        onClick = { picked = id },
                        label = { Text(home.entities[id]?.friendlyName ?: id, maxLines = 1) },
                    )
                }
            }
        }
        var typed by rememberSaveable(list) { mutableStateOf("") }
        val add = {
            if (typed.isNotBlank()) {
                viewModel.add(list, typed)
                typed = ""
            }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = { Text(stringResource(R.string.lists_add)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                if (typed.isNotBlank()) TextButton(onClick = add) { Text(stringResource(R.string.lists_add_button)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (failed) {
            Text(
                stringResource(R.string.lists_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val items = allItems[list].orEmpty().toShow()
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .then(if (bounded) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier),
        ) {
            items.forEach { item -> TodoRow(item) { viewModel.setDone(list, item, !item.done) } }
        }
        if (items.any { it.done }) {
            TextButton(onClick = { viewModel.clearDone(list) }) { Text(stringResource(R.string.lists_clear_done)) }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
    ) {
        DoneMark(item.done, MaterialTheme.colorScheme.primary)
        Text(
            item.summary,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** A circle to tick off: a ring of [color], filled with a tick once done. */
@Composable
internal fun DoneMark(done: Boolean, color: Color) {
    Surface(color = Color.Transparent) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(if (done) Modifier.background(color) else Modifier.border(2.dp, color, CircleShape)),
        ) {
            if (done) Text("✓", color = OnPersonColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}
