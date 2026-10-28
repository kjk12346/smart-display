package dev.smartdisplay.app.ui.dashboard

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
 * resized from its corner; it snaps to the cells, and goes back if it would cover another card or leave the grid.
 * Each also offers Sources (what it shows) and Hide.
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
        layout.cards.forEach { card ->
            key(card.id) {
                val rect = card.rect(grid)
                val place = Modifier.offset(cells.x(rect), cells.y(rect)).size(cells.width(rect), cells.height(rect))
                if (editing) {
                    EditableCard(card, rect, layout, grid, cells, onLayoutChange, onSources) { content(card, it) }
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

@Composable
private fun EditableCard(
    card: DashCard,
    rect: CardRect,
    layout: DashLayout,
    grid: Grid,
    cells: Cells,
    onLayoutChange: (DashLayout) -> Unit,
    onSources: (DashCard) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    var drag by remember { mutableStateOf(Offset.Zero) }
    var stretch by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    // Gestures outlive recompositions; they read the latest of these.
    val currentLayout by rememberUpdatedState(layout)
    val currentRect by rememberUpdatedState(rect)
    val currentOnChange by rememberUpdatedState(onLayoutChange)
    val step by rememberUpdatedState(with(density) { Offset((cells.w + GAP).toPx(), (cells.h + GAP).toPx()) })

    // Where the card would land: snapped to the nearest cells.
    fun targetOf(from: CardRect) = from.copy(
        x = from.x + (drag.x / step.x).roundToInt(),
        y = from.y + (drag.y / step.y).roundToInt(),
        w = from.w + (stretch.x / step.x).roundToInt(),
        h = from.h + (stretch.y / step.y).roundToInt(),
    )
    val target = targetOf(rect)
    val valid = layout.canPlace(card.id, grid, target)
    fun drop() {
        val landing = targetOf(currentRect)
        if (landing != currentRect) {
            // move() leaves the layout as it was if the card can't go there.
            currentOnChange(currentLayout.move(card.id, grid, landing))
        }
        drag = Offset.Zero
        stretch = Offset.Zero
        active = false
    }

    if (active && target != rect) {
        val ring = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Box(
            Modifier
                .offset(cells.x(target), cells.y(target))
                .size(cells.width(target), cells.height(target))
                .border(3.dp, ring, CARD_SHAPE),
        )
    }
    val stretchW = with(density) { stretch.x.toDp() }
    val stretchH = with(density) { stretch.y.toDp() }
    Box(
        Modifier
            .zIndex(if (active) 1f else 0f)
            .offset(cells.x(rect), cells.y(rect))
            .graphicsLayer {
                translationX = drag.x
                translationY = drag.y
                alpha = if (active) 0.85f else 1f
            }
            .size(
                (cells.width(rect) + stretchW).coerceAtLeast(cells.w),
                (cells.height(rect) + stretchH).coerceAtLeast(cells.h),
            ),
    ) {
        content(Modifier.fillMaxSize())
        // Over the card: catches every touch (so nothing inside reacts while arranging), and moves it.
        Box(
            Modifier
                .fillMaxSize()
                .clip(CARD_SHAPE)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CARD_SHAPE)
                .pointerInput(card.id, grid) {
                    detectDragGestures(
                        onDragStart = { active = true },
                        onDragEnd = { drop() },
                        onDragCancel = { drop() },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
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
                        }
                    },
            )
        }
    }
}

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
