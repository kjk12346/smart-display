package dev.smartdisplay.app.kiosk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What a dashboard card shows. Calendar, Who's home, Buttons and Weather take their sources from [KitchenConfig] and
 * there's one of each; the others keep their own ([DashCard.areaId], [DashCard.entities]) and can be added more than
 * once.
 */
enum class CardType(val single: Boolean) {
    Calendar(true),
    People(true),
    Buttons(true),
    Devices(false),
    Sensors(false),
    NowPlaying(false),
    Camera(false),
    Weather(true),
    Wifi(true),
}

/** A grid of cells the cards snap to: one for landscape, one for portrait. */
data class Grid(val columns: Int, val rows: Int)

val WIDE_GRID = Grid(columns = 12, rows = 8)
val TALL_GRID = Grid(columns = 6, rows = 12)

/** The smallest a card can be, in cells each way. */
const val MIN_CARD_CELLS = 2

/** A card's place on a [Grid], in cells. */
data class CardRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(other: CardRect): Boolean =
        x < other.x + other.w && other.x < x + w && y < other.y + other.h && other.y < y + h

    fun fits(grid: Grid): Boolean =
        x >= 0 && y >= 0 && w >= MIN_CARD_CELLS && h >= MIN_CARD_CELLS && x + w <= grid.columns && y + h <= grid.rows
}

/**
 * A card on the dashboard, with its place in landscape ([wide]) and in portrait ([tall]), so turning the display
 * keeps each arrangement. Devices shows an area's controls ([areaId]) plus any [entities] chosen one by one; Sensors
 * shows [entities]; Now playing and Camera show the first of [entities].
 */
data class DashCard(
    val id: String,
    val type: CardType,
    val wide: CardRect,
    val tall: CardRect,
    val areaId: String? = null,
    val entities: List<String> = emptyList(),
) {
    fun rect(grid: Grid): CardRect = if (grid == WIDE_GRID) wide else tall

    fun withRect(grid: Grid, rect: CardRect): DashCard = if (grid == WIDE_GRID) copy(wide = rect) else copy(tall = rect)
}

/** The dashboard's cards. Leaving one out hides it; it can be added back, in any free space. */
data class DashLayout(val cards: List<DashCard> = DEFAULT_CARDS) {
    /** Whether [rect] is a place [cardId] can move to on [grid]: inside it, and over no other card. */
    fun canPlace(cardId: String, grid: Grid, rect: CardRect): Boolean =
        rect.fits(grid) && cards.none { it.id != cardId && it.rect(grid).overlaps(rect) }

    /** [cardId] moved or resized to [rect] on [grid]; unchanged if it can't go there. */
    fun move(cardId: String, grid: Grid, rect: CardRect): DashLayout {
        if (!canPlace(cardId, grid, rect)) return this
        return copy(cards = cards.map { if (it.id == cardId) it.withRect(grid, rect) else it })
    }

    /**
     * [cardId] moved or resized to [rect] on [grid], the other cards making way: those it lands on move down, and then
     * every card slides up as far as it can, so no gaps are left. If they'd no longer fit, a card dropped on a single
     * other card swaps places with it, or else the tallest of the others give up rows. Null if nothing makes it fit.
     */
    fun arrange(cardId: String, grid: Grid, rect: CardRect): DashLayout? {
        val moving = cards.firstOrNull { it.id == cardId } ?: return null
        if (!rect.fits(grid)) return null
        val others = cards.filter { it.id != cardId }.associate { it.id to it.rect(grid) }
        val placed = packed(cardId, rect, others, grid)
            ?: swapped(cardId, moving.rect(grid), rect, others, grid)
            ?: shrunk(cardId, rect, others, grid)
            ?: return null
        return copy(cards = cards.map { it.withRect(grid, placed.getValue(it.id)) })
    }

    fun remove(cardId: String): DashLayout = copy(cards = cards.filterNot { it.id == cardId })

    fun update(card: DashCard): DashLayout = copy(cards = cards.map { if (it.id == card.id) card else it })

    /** The kinds of card that can be added: the one-of-a-kind ones not already shown, and every other kind. */
    fun addable(): List<CardType> = CardType.entries.filter { type -> !type.single || cards.none { it.type == type } }

    /**
     * Adds a card of [type] on both grids, in the first free space, as big as it usually is or as big as fits there.
     * Where there's no room even for the smallest card, the biggest cards give up a row or column at a time until it
     * fits; null only when every card is already as small as it goes.
     */
    fun add(type: CardType, id: String): DashLayout? {
        val (wideSize, tallSize) = DEFAULT_SIZES.getValue(type)
        val (afterWide, wide) = makeRoom(WIDE_GRID, wideSize) ?: return null
        val (afterTall, tall) = afterWide.makeRoom(TALL_GRID, tallSize) ?: return null
        return afterTall.copy(cards = afterTall.cards + DashCard(id, type, wide, tall))
    }

    /**
     * The cards on [grid] back as they started: the calendar, Who's home and Buttons where they first were, and every
     * other card fitted in around them, as when it was added. Hidden cards stay hidden, and the other grid is left as
     * it is. Unchanged in the unlikely case the added cards no longer fit.
     */
    fun reset(grid: Grid): DashLayout {
        val defaults = DEFAULT_CARDS.associateBy { it.id }
        val (original, added) = cards.partition { it.id in defaults }
        var layout = copy(cards = original.map { it.withRect(grid, defaults.getValue(it.id).rect(grid)) })
        for (card in added) {
            val (wideSize, tallSize) = DEFAULT_SIZES.getValue(card.type)
            val (roomy, rect) = layout.makeRoom(grid, if (grid == WIDE_GRID) wideSize else tallSize) ?: return this
            layout = roomy.copy(cards = roomy.cards + card.withRect(grid, rect))
        }
        // In the order they were, which is the order they're drawn.
        val placed = layout.cards.associateBy { it.id }
        return copy(cards = cards.map { placed.getValue(it.id) })
    }

    /** Space on [grid] for a card [size] big, shrinking the biggest cards until there is; null if none is left. */
    private fun makeRoom(grid: Grid, size: Pair<Int, Int>): Pair<DashLayout, CardRect>? {
        var layout = this
        while (true) {
            layout.freeSpot(grid, size)?.let { return layout to it }
            layout = layout.shrinkBiggest(grid) ?: return null
        }
    }

    /**
     * The biggest card on [grid] that can shrink, a row shorter (or else a column narrower), then every card slid up
     * into any space above it, so the room gathers at the bottom; null if no card can shrink.
     */
    private fun shrinkBiggest(grid: Grid): DashLayout? {
        val card = cards.filter { it.rect(grid).let { r -> r.w > MIN_CARD_CELLS || r.h > MIN_CARD_CELLS } }
            .maxByOrNull { it.rect(grid).let { r -> r.w * r.h } } ?: return null
        val rect = card.rect(grid)
        val smaller = if (rect.h > MIN_CARD_CELLS) rect.copy(h = rect.h - 1) else rect.copy(w = rect.w - 1)
        var layout = update(card.withRect(grid, smaller))
        for (id in layout.cards.sortedWith(compareBy({ it.rect(grid).y }, { it.rect(grid).x })).map { it.id }) {
            val at = layout.cards.first { it.id == id }.rect(grid)
            val highest = (0 until at.y).firstOrNull { y -> layout.canPlace(id, grid, at.copy(y = y)) } ?: continue
            layout = layout.move(id, grid, at.copy(y = highest))
        }
        return layout
    }

    /** The first space on [grid], row by row, for a card [wanted] big, shrinking it toward the smallest if needed. */
    private fun freeSpot(grid: Grid, wanted: Pair<Int, Int>): CardRect? {
        val taken = cards.map { it.rect(grid) }
        val (wantW, wantH) = wanted
        for (h in wantH downTo MIN_CARD_CELLS) {
            for (w in wantW downTo MIN_CARD_CELLS) {
                for (y in 0..grid.rows - h) {
                    for (x in 0..grid.columns - w) {
                        val rect = CardRect(x, y, w, h)
                        if (taken.none { it.overlaps(rect) }) return rect
                    }
                }
            }
        }
        return null
    }
}

/**
 * [rect] for [cardId], and [others] where they first fit from the top, in their order down the grid (so the ones it
 * lands on go below it); then all of them slid up in that order. Null if one doesn't fit.
 */
private fun packed(cardId: String, rect: CardRect, others: Map<String, CardRect>, grid: Grid): Map<String, CardRect>? {
    val placed = linkedMapOf(cardId to rect)
    for ((id, at) in others.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }))) {
        placed[id] = at.copy(y = firstFreeRow(at, placed.values, grid) ?: return null)
    }
    val slid = linkedMapOf<String, CardRect>()
    for ((id, at) in placed.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }))) {
        slid[id] = at.copy(y = firstFreeRow(at, slid.values, grid) ?: return null)
    }
    return slid
}

/** [cardId] at [rect] and the one card it covers moved to where [cardId] was ([from]), if that's all it covers. */
private fun swapped(
    cardId: String,
    from: CardRect,
    rect: CardRect,
    others: Map<String, CardRect>,
    grid: Grid,
): Map<String, CardRect>? {
    val covered = others.filter { it.value.overlaps(rect) }.entries.singleOrNull() ?: return null
    val moved = covered.value.copy(x = from.x, y = from.y)
    if (!moved.fits(grid)) return null
    return packed(cardId, rect, others + (covered.key to moved), grid)
}

/** As [packed], with the tallest of [others] a row shorter at a time until they fit; null if they never do. */
private fun shrunk(cardId: String, rect: CardRect, others: Map<String, CardRect>, grid: Grid): Map<String, CardRect>? {
    var sizes = others
    while (true) {
        val tallest = sizes.entries.filter { it.value.h > MIN_CARD_CELLS }.maxByOrNull { it.value.h } ?: return null
        sizes = sizes + (tallest.key to tallest.value.copy(h = tallest.value.h - 1))
        packed(cardId, rect, sizes, grid)?.let { return it }
    }
}

/** The highest row [rect] can be at in its columns without covering any of [taken]; null if there's none. */
private fun firstFreeRow(rect: CardRect, taken: Collection<CardRect>, grid: Grid): Int? =
    (0..grid.rows - rect.h).firstOrNull { y -> taken.none { it.overlaps(rect.copy(y = y)) } }

/** Today's arrangement: the calendar most of the way across, Who's home and Buttons beside it (or under it). */
val DEFAULT_CARDS = listOf(
    DashCard("calendar", CardType.Calendar, wide = CardRect(0, 0, 9, 8), tall = CardRect(0, 0, 6, 7)),
    DashCard("people", CardType.People, wide = CardRect(9, 0, 3, 4), tall = CardRect(0, 7, 3, 5)),
    DashCard("buttons", CardType.Buttons, wide = CardRect(9, 4, 3, 4), tall = CardRect(3, 7, 3, 5)),
)

/** How big a new card starts, in cells: (columns, rows) on the wide grid, then on the tall one. */
private val DEFAULT_SIZES = mapOf(
    CardType.Calendar to ((6 to 5) to (6 to 6)),
    CardType.People to ((3 to 4) to (3 to 4)),
    CardType.Buttons to ((3 to 3) to (3 to 3)),
    CardType.Devices to ((4 to 4) to (6 to 4)),
    CardType.Sensors to ((3 to 3) to (3 to 3)),
    CardType.NowPlaying to ((3 to 4) to (3 to 4)),
    CardType.Camera to ((4 to 4) to (6 to 4)),
    CardType.Weather to ((4 to 4) to (6 to 4)),
    CardType.Wifi to ((3 to 3) to (3 to 3)),
)

/** The layout as JSON, for [KioskStore]. */
internal fun encodeLayout(layout: DashLayout): String = JsonArray(
    layout.cards.map { card ->
        buildJsonObject {
            put("id", card.id)
            put("type", card.type.name)
            put("wide", encodeRect(card.wide))
            put("tall", encodeRect(card.tall))
            card.areaId?.let { put("area", it) }
            put("entities", JsonArray(card.entities.map(::JsonPrimitive)))
        }
    },
).toString()

/**
 * Reads [encodeLayout]'s JSON; null (the default layout) when there's none or it can't be read. Cards of an unknown
 * type, or that don't fit or overlap, are dropped.
 */
internal fun decodeLayout(text: String?): DashLayout? {
    val array = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return null
    var layout = DashLayout(emptyList())
    array.filterIsInstance<JsonObject>().forEach { obj ->
        val id = obj.text("id") ?: return@forEach
        val type = CardType.entries.firstOrNull { it.name == obj.text("type") } ?: return@forEach
        val wide = decodeRect(obj["wide"]) ?: return@forEach
        val tall = decodeRect(obj["tall"]) ?: return@forEach
        if (layout.cards.any { it.id == id } || (type.single && layout.cards.any { it.type == type })) return@forEach
        if (!layout.canPlace(id, WIDE_GRID, wide) || !layout.canPlace(id, TALL_GRID, tall)) return@forEach
        val entities = (obj["entities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val card = DashCard(id, type, wide, tall, obj.text("area"), entities.distinct())
        layout = layout.copy(cards = layout.cards + card)
    }
    return layout
}

private fun encodeRect(rect: CardRect) = JsonArray(listOf(rect.x, rect.y, rect.w, rect.h).map(::JsonPrimitive))

private fun decodeRect(json: Any?): CardRect? {
    val values = (json as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: return null } ?: return null
    return values.takeIf { it.size == 4 }?.let { (x, y, w, h) -> CardRect(x, y, w, h) }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
