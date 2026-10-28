package dev.smartdisplay.app.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.CardRect
import dev.smartdisplay.app.kiosk.DashCard
import dev.smartdisplay.app.kiosk.DashLayout
import dev.smartdisplay.app.kiosk.Grid
import kotlin.math.roundToInt

/** The space between cards, and between grid cells. */
private val GAP = 16.dp
private val CARD_SHAPE = RoundedCornerShape(24.dp)

/**
 * The cards on a [grid] that fills the space given. While [editing], each card can be dragged to another place or
 * resized from its corner; it snaps to the cells, and the other cards make way ([DashLayout.arrange]), moving as it's
 * dragged to show where they'd go. Each also offers Sources (what it shows) and Hide.
 */
@Composable
internal fun CardGrid(
    layout: DashLayout,
    grid: Grid,
    editing: Boolean,
    onLayoutChange: (DashLayout) -> Unit,
    onSources: (DashCard) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (card: DashCard, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cellW = (maxWidth - GAP * (grid.columns - 1)) / grid.columns
        val cellH = (maxHeight - GAP * (grid.rows - 1)) / grid.rows
        val cells = Cells(cellW, cellH)
        if (editing) GridCells(grid, cells)
        // The card being dragged and where it would land, and the layout that would make.
        var moving by remember { mutableStateOf<Pair<String, CardRect>?>(null) }
        val preview = remember(layout, moving, grid) {
            moving?.let { (id, target) -> layout.arrange(id, grid, target) }
        }
        layout.cards.forEach { card ->
            key(card.id) {
                val rect = card.rect(grid)
                val place = Modifier.offset(cells.x(rect), cells.y(rect)).size(cells.width(rect), cells.height(rect))
                if (editing) {
                    val dragged = moving?.first == card.id
                    val previewRect = preview?.cards?.firstOrNull { it.id == card.id }?.rect(grid)
                    EditableCard(
                        card = card,
                        rect = rect,
                        // The others show where they'd go; the one dragged shows where it would land.
                        shown = if (dragged) rect else previewRect ?: rect,
                        landing = if (dragged) previewRect else null,
                        blocked = dragged && moving?.second != rect && preview == null,
                        layout = layout,
                        grid = grid,
                        cells = cells,
                        onTarget = { target -> moving = target?.let { card.id to it } },
                        onLayoutChange = onLayoutChange,
                        onSources = onSources,
                    ) { content(card, it) }
                } else {
                    content(card, place)
                }
            }
        }
    }
}

/** Grid cell sizes, and where a card on them goes. */
private class Cells(val w: Dp, val h: Dp) {
    fun x(rect: CardRect) = (w + GAP) * rect.x
    fun y(rect: CardRect) = (h + GAP) * rect.y
    fun width(rect: CardRect) = w * rect.w + GAP * (rect.w - 1)
    fun height(rect: CardRect) = h * rect.h + GAP * (rect.h - 1)
}

/** Faint outlines of every cell, so it's clear where a card can go. */
@Composable
private fun GridCells(grid: Grid, cells: Cells) {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    for (row in 0 until grid.rows) {
        for (column in 0 until grid.columns) {
            val rect = CardRect(column, row, 1, 1)
            Box(
                Modifier
                    .offset(cells.x(rect), cells.y(rect))
                    .size(cells.w, cells.h)
                    .border(1.dp, color, RoundedCornerShape(12.dp)),
            )
        }
    }
}

/**
 * A card while arranging. It sits at [shown] (where the card being dragged would push it, or [rect]), gliding there;
 * the one being dragged follows the finger, with a ring at its [landing] (red where it can't go, [blocked]).
 */
@Composable
private fun EditableCard(
    card: DashCard,
    rect: CardRect,
    shown: CardRect,
    landing: CardRect?,
    blocked: Boolean,
    layout: DashLayout,
    grid: Grid,
    cells: Cells,
    onTarget: (CardRect?) -> Unit,
    onLayoutChange: (DashLayout) -> Unit,
    onSources: (DashCard) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var drag by remember { mutableStateOf(Offset.Zero) }
    var stretch by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    // After a drop, the card glides from where it was let go to where it landed.
    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // Gestures outlive recompositions; they read the latest of these.
    val currentLayout by rememberUpdatedState(layout)
    val currentRect by rememberUpdatedState(rect)
    val currentCells by rememberUpdatedState(cells)
    val currentOnChange by rememberUpdatedState(onLayoutChange)
    val currentOnTarget by rememberUpdatedState(onTarget)
    val step by rememberUpdatedState(with(density) { Offset((cells.w + GAP).toPx(), (cells.h + GAP).toPx()) })

    // Where the card would land: snapped to the nearest cells.
    fun targetOf(from: CardRect) = from.copy(
        x = from.x + (drag.x / step.x).roundToInt(),
        y = from.y + (drag.y / step.y).roundToInt(),
        w = from.w + (stretch.x / step.x).roundToInt(),
        h = from.h + (stretch.y / step.y).roundToInt(),
    )
    fun moved() = currentOnTarget(targetOf(currentRect).takeIf { it != currentRect })
    fun drop() {
        val from = currentRect
        val target = targetOf(from)
        val arranged = if (target != from) currentLayout.arrange(card.id, grid, target) else null
        val to = arranged?.cards?.firstOrNull { it.id == card.id }?.rect(grid) ?: from
        // Where it was let go, measured from where it now sits.
        val leftover = with(density) {
            Offset(
                (currentCells.x(from) - currentCells.x(to)).toPx() + drag.x,
                (currentCells.y(from) - currentCells.y(to)).toPx() + drag.y,
            )
        }
        scope.launch {
            settle.snapTo(leftover)
            if (arranged != null) currentOnChange(arranged)
            drag = Offset.Zero
            stretch = Offset.Zero
            active = false
            currentOnTarget(null)
            settle.animateTo(Offset.Zero, tween(SETTLE_MS))
        }
    }

    if (active && landing != null && landing != rect) {
        Box(
            Modifier
                .offset(cells.x(landing), cells.y(landing))
                .size(cells.width(landing), cells.height(landing))
                .border(3.dp, MaterialTheme.colorScheme.primary, CARD_SHAPE),
        )
    }
    // Others glide to make way; the dragged card itself jumps (its own movement is the finger's).
    val glide = !active && settle.value == Offset.Zero
    val spec: AnimationSpec<Dp> = if (glide) tween(SETTLE_MS) else snap()
    val x by animateDpAsState(cells.x(shown), spec, label = "x")
    val y by animateDpAsState(cells.y(shown), spec, label = "y")
    val width by animateDpAsState(cells.width(shown), spec, label = "width")
    val height by animateDpAsState(cells.height(shown), spec, label = "height")
    val stretchW = with(density) { stretch.x.toDp() }
    val stretchH = with(density) { stretch.y.toDp() }
    val outline = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Box(
        Modifier
            .zIndex(if (active || settle.value != Offset.Zero) 1f else 0f)
            .offset(x, y)
            .graphicsLayer {
                translationX = drag.x + settle.value.x
                translationY = drag.y + settle.value.y
                alpha = if (active) 0.85f else 1f
            }
            .size((width + stretchW).coerceAtLeast(cells.w), (height + stretchH).coerceAtLeast(cells.h)),
    ) {
        content(Modifier.fillMaxSize())
        // Over the card: catches every touch (so nothing inside reacts while arranging), and moves it.
        Box(
            Modifier
                .fillMaxSize()
                .clip(CARD_SHAPE)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                .border(2.dp, outline, CARD_SHAPE)
                .pointerInput(card.id, grid) {
                    detectDragGestures(
                        onDragStart = { active = true },
                        onDragEnd = { drop() },
                        onDragCancel = { drop() },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
                        moved()
                    }
                },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            ) {
                FilledTonalButton(onClick = { onSources(card) }) {
                    Text(stringResource(R.string.dashboard_edit_sources))
                }
                FilledTonalButton(onClick = { onLayoutChange(currentLayout.remove(card.id)) }) {
                    Text(stringResource(R.string.dashboard_edit_hide))
                }
            }
            ResizeHandle(
                Modifier
                    .align(Alignment.BottomEnd)
                    .pointerInput(card.id, grid) {
                        detectDragGestures(
                            onDragStart = { active = true },
                            onDragEnd = { drop() },
                            onDragCancel = { drop() },
                        ) { change, amount ->
                            change.consume()
                            stretch += amount
                            moved()
                        }
                    },
            )
        }
    }
}

/** How long cards take to glide into place. */
private const val SETTLE_MS = 220

/** A corner with two diagonal strokes, to drag a card bigger or smaller. */
@Composable
private fun ResizeHandle(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(56.dp).padding(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 3.dp.toPx()
        drawLine(color, Offset(w, h * 0.15f), Offset(w * 0.15f, h), stroke, StrokeCap.Round)
        drawLine(color, Offset(w, h * 0.6f), Offset(w * 0.6f, h), stroke, StrokeCap.Round)
    }
}
