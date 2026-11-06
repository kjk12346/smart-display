package dev.smartdisplay.app.kiosk

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * The family's notes, countdowns, routines and points. Home Assistant has nowhere to keep these, so they live on the
 * display, as the choice of who an event is for does, and aren't shared with other displays.
 */

/** A short note on the dashboard. [pinned] ones come first and show on the morning summary. */
data class Sticky(
    val id: String,
    val text: String,
    val emoji: String? = null,
    val color: PersonColor = PersonColor.Butter,
    val pinned: Boolean = false,
)

/** A date to count down to. A [yearly] one (a birthday) starts again the next year; another is gone once it's past. */
data class Countdown(val id: String, val title: String, val date: LocalDate, val emoji: String? = null, val yearly: Boolean = false)

/** The next time [Countdown] comes round, from [today] on; null once a one-off has passed. */
fun Countdown.nextDate(today: LocalDate): LocalDate? {
    if (!yearly) return date.takeIf { !it.isBefore(today) }
    // The 29th of February falls back to the 28th in other years, by withYear.
    val thisYear = date.withYear(today.year)
    return if (thisYear.isBefore(today)) date.withYear(today.year + 1) else thisYear
}

/** Whole days until [Countdown.nextDate]; null once a one-off has passed. */
fun Countdown.daysLeft(today: LocalDate): Long? = nextDate(today)?.let { ChronoUnit.DAYS.between(today, it) }

/** The countdowns still to come, soonest first. */
fun List<Countdown>.coming(today: LocalDate): List<Countdown> =
    filter { it.nextDate(today) != null }.sortedBy { it.nextDate(today) }

/** Something someone does on certain [days] of the week, worth [points]. */
data class Routine(
    val id: String,
    val title: String,
    val emoji: String? = null,
    /** `person.*` entity IDs; each does it themselves and earns their own points. */
    val people: List<String> = emptyList(),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val points: Int = 1,
)

/** Something points can be spent on. */
data class Reward(val id: String, val title: String, val emoji: String? = null, val cost: Int = 10)

/** One change to someone's points, for their history. [delta] is negative for a reward. */
data class PointsEntry(val person: String, val delta: Int, val label: String, val date: LocalDate)

/** Every routine, reward and point on this display. */
data class RoutineBook(
    val routines: List<Routine> = emptyList(),
    val rewards: List<Reward> = emptyList(),
    /** Who did what when, by [doneKey]; kept for [DONE_KEEP_DAYS]. */
    val done: Set<String> = emptySet(),
    val balances: Map<String, Int> = emptyMap(),
    /** Newest first, up to [HISTORY_KEEP]. */
    val history: List<PointsEntry> = emptyList(),
) {
    /** One person's routines on [date], in the order they were added. */
    fun routinesFor(person: String, date: LocalDate): List<Routine> =
        routines.filter { person in it.people && date.dayOfWeek in it.days }

    fun isDone(routine: Routine, person: String, date: LocalDate): Boolean = doneKey(date, routine.id, person) in done

    /** How many of [person]'s routines on [date] are done, and how many there are. */
    fun progress(person: String, date: LocalDate): Pair<Int, Int> {
        val due = routinesFor(person, date)
        return due.count { isDone(it, person, date) } to due.size
    }

    /** Marks [routine] done, or not done any more, and moves the points with it. */
    fun toggled(routine: Routine, person: String, date: LocalDate): RoutineBook {
        val key = doneKey(date, routine.id, person)
        return if (key in done) {
            // Undone: takes back the points, and the line in the history with them.
            val index = history.indexOfFirst {
                it.person == person && it.label == routine.title && it.date == date && it.delta == routine.points
            }
            copy(
                done = done - key,
                balances = balances.plusPoints(person, -routine.points),
                history = if (index >= 0) history.filterIndexed { i, _ -> i != index } else history,
            )
        } else {
            copy(
                done = pruned(date) + key,
                balances = balances.plusPoints(person, routine.points),
                history = (listOf(PointsEntry(person, routine.points, routine.title, date)) + history).take(HISTORY_KEEP),
            )
        }
    }

    /** [reward] bought with [person]'s points; unchanged if they don't have enough. */
    fun redeemed(reward: Reward, person: String, date: LocalDate): RoutineBook {
        if (balances[person] ?: 0 < reward.cost) return this
        return adjusted(person, -reward.cost, reward.title, date)
    }

    /** Points given or taken by hand. A balance doesn't go below zero. */
    fun adjusted(person: String, delta: Int, label: String, date: LocalDate): RoutineBook = copy(
        balances = balances.plusPoints(person, delta),
        history = (listOf(PointsEntry(person, delta, label, date)) + history).take(HISTORY_KEEP),
    )

    private fun pruned(today: LocalDate): Set<String> {
        val oldest = today.minusDays(DONE_KEEP_DAYS)
        return done.filterTo(mutableSetOf()) { key ->
            runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()?.isBefore(oldest) == false
        }
    }
}

private fun Map<String, Int>.plusPoints(person: String, delta: Int): Map<String, Int> =
    this + (person to ((this[person] ?: 0) + delta).coerceAtLeast(0))

internal fun doneKey(date: LocalDate, routineId: String, person: String) = "$date|$routineId|$person"

private const val DONE_KEEP_DAYS = 60L
private const val HISTORY_KEEP = 100

// JSON for KioskStore. Anything that can't be read is left out rather than losing the rest.

internal fun encodeStickies(stickies: List<Sticky>): String = JsonArray(
    stickies.map { sticky ->
        buildJsonObject {
            put("id", sticky.id)
            put("text", sticky.text)
            sticky.emoji?.let { put("emoji", it) }
            put("color", sticky.color.name)
            put("pinned", sticky.pinned)
        }
    },
).toString()

internal fun decodeStickies(text: String?): List<Sticky> = objects(text).mapNotNull { obj ->
    Sticky(
        id = obj.text("id") ?: return@mapNotNull null,
        text = obj.text("text") ?: return@mapNotNull null,
        emoji = obj.text("emoji"),
        color = PersonColor.entries.firstOrNull { it.name == obj.text("color") } ?: PersonColor.Butter,
        pinned = (obj["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

internal fun encodeCountdowns(countdowns: List<Countdown>): String = JsonArray(
    countdowns.map { countdown ->
        buildJsonObject {
            put("id", countdown.id)
            put("title", countdown.title)
            put("date", countdown.date.toString())
            countdown.emoji?.let { put("emoji", it) }
            put("yearly", countdown.yearly)
        }
    },
).toString()

internal fun decodeCountdowns(text: String?): List<Countdown> = objects(text).mapNotNull { obj ->
    Countdown(
        id = obj.text("id") ?: return@mapNotNull null,
        title = obj.text("title") ?: return@mapNotNull null,
        date = obj.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null,
        emoji = obj.text("emoji"),
        yearly = (obj["yearly"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

internal fun encodeRoutineBook(book: RoutineBook): String = buildJsonObject {
    put(
        "routines",
        JsonArray(
            book.routines.map { routine ->
                buildJsonObject {
                    put("id", routine.id)
                    put("title", routine.title)
                    routine.emoji?.let { put("emoji", it) }
                    put("people", JsonArray(routine.people.map(::JsonPrimitive)))
                    put("days", JsonArray(routine.days.sorted().map { JsonPrimitive(it.name) }))
                    put("points", routine.points)
                }
            },
        ),
    )
    put(
        "rewards",
        JsonArray(
            book.rewards.map { reward ->
                buildJsonObject {
                    put("id", reward.id)
                    put("title", reward.title)
                    reward.emoji?.let { put("emoji", it) }
                    put("cost", reward.cost)
                }
            },
        ),
    )
    put("done", JsonArray(book.done.sorted().map(::JsonPrimitive)))
    put("balances", JsonObject(book.balances.mapValues { JsonPrimitive(it.value) }))
    put(
        "history",
        JsonArray(
            book.history.map { entry ->
                buildJsonObject {
                    put("person", entry.person)
                    put("delta", entry.delta)
                    put("label", entry.label)
                    put("date", entry.date.toString())
                }
            },
        ),
    )
}.toString()

internal fun decodeRoutineBook(text: String?): RoutineBook {
    val obj = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return RoutineBook()
    return RoutineBook(
        routines = obj["routines"].objectList().mapNotNull { item ->
            Routine(
                id = item.text("id") ?: return@mapNotNull null,
                title = item.text("title") ?: return@mapNotNull null,
                emoji = item.text("emoji"),
                people = item["people"].stringList(),
                days = item["days"].stringList()
                    .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }.toSet(),
                points = (item["points"] as? JsonPrimitive)?.intOrNull ?: 1,
            )
        },
        rewards = obj["rewards"].objectList().mapNotNull { item ->
            Reward(
                id = item.text("id") ?: return@mapNotNull null,
                title = item.text("title") ?: return@mapNotNull null,
                emoji = item.text("emoji"),
                cost = (item["cost"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
            )
        },
        done = obj["done"].stringList().toSet(),
        balances = (obj["balances"] as? JsonObject)?.mapNotNull { (person, value) ->
            (value as? JsonPrimitive)?.intOrNull?.let { person to it }
        }?.toMap().orEmpty(),
        history = obj["history"].objectList().mapNotNull { item ->
            PointsEntry(
                person = item.text("person") ?: return@mapNotNull null,
                delta = (item["delta"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                label = item.text("label").orEmpty(),
                date = item.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return@mapNotNull null,
            )
        },
    )
}

private fun objects(text: String?): List<JsonObject> =
    (text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }).objectList()

private fun JsonElement?.objectList(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonElement?.stringList(): List<String> =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
