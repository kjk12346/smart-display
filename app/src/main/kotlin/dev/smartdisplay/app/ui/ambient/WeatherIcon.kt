package dev.smartdisplay.app.ui.ambient

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import dev.smartdisplay.app.ui.theme.Blue300
import dev.smartdisplay.app.ui.theme.Blue500
import dev.smartdisplay.app.ui.theme.CloudGrey
import dev.smartdisplay.app.ui.theme.Lavender300
import dev.smartdisplay.app.ui.theme.SunYellow
import kotlin.math.cos
import kotlin.math.sin

/**
 * A weather icon for a Home Assistant condition, drawn on a 48×48 grid and scaled to [size]. Drawn in code rather
 * than bundling an icon font, to stay small and match the display's pastel accents.
 */
@Composable
fun WeatherIcon(condition: String, night: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / GRID
        scale(unit, pivot = Offset.Zero) {
            when (condition) {
                "sunny" -> if (night) moon(Offset(24f, 24f), 13f) else sun(Offset(24f, 24f), 9f)
                "clear-night" -> moon(Offset(24f, 24f), 13f)
                "partlycloudy" -> {
                    if (night) moon(Offset(17f, 17f), 9f) else sun(Offset(17f, 17f), 6.5f)
                    cloud(dy = 6f)
                }
                "cloudy" -> cloud()
                "fog" -> fog()
                "rainy" -> {
                    cloud(dy = -4f)
                    rain(count = 3)
                }
                "pouring" -> {
                    cloud(dy = -4f)
                    rain(count = 5)
                }
                "snowy" -> {
                    cloud(dy = -4f)
                    snow()
                }
                "snowy-rainy" -> {
                    cloud(dy = -4f)
                    rain(count = 2, startX = 15f)
                    drawCircle(Blue300, radius = 2f, center = Offset(33f, 40f))
                }
                "hail" -> {
                    cloud(dy = -4f)
                    listOf(16f, 24f, 32f).forEach { x -> drawCircle(Blue300, radius = 2.4f, center = Offset(x, 40f)) }
                }
                "lightning" -> {
                    cloud(dy = -4f)
                    bolt()
                }
                "lightning-rainy" -> {
                    cloud(dy = -4f)
                    rain(count = 2, startX = 13f)
                    bolt(dx = 6f)
                }
                "windy" -> wind()
                "windy-variant" -> {
                    cloud(dy = -6f)
                    translate(top = 10f) { wind(scale = 0.8f) }
                }
                else -> cloud()
            }
        }
    }
}

private const val GRID = 48f
private const val STROKE = 2.6f

private fun DrawScope.sun(center: Offset, radius: Float) {
    drawCircle(SunYellow, radius = radius, center = center)
    repeat(8) { i ->
        val angle = Math.toRadians(i * 45.0)
        val from = radius + radius * 0.45f
        val to = radius + radius * 0.95f
        drawLine(
            SunYellow,
            start = center + Offset((cos(angle) * from).toFloat(), (sin(angle) * from).toFloat()),
            end = center + Offset((cos(angle) * to).toFloat(), (sin(angle) * to).toFloat()),
            strokeWidth = STROKE,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.moon(center: Offset, radius: Float) {
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply {
        addOval(Rect(center + Offset(radius * 0.55f, -radius * 0.45f), radius * 0.85f))
    }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Lavender300)
}

private fun DrawScope.cloud(dy: Float = 0f, color: Color = CloudGrey) {
    val base = Path().apply {
        addRoundRect(RoundRect(8f, 26f + dy, 40f, 37f + dy, CornerRadius(5.5f)))
    }
    val left = Path().apply { addOval(Rect(Offset(17f, 26f + dy), 8f)) }
    val right = Path().apply { addOval(Rect(Offset(28f, 22.5f + dy), 10f)) }
    drawPath(Path.combine(PathOperation.Union, Path.combine(PathOperation.Union, base, left), right), color)
}

private fun DrawScope.rain(count: Int, startX: Float = 12f) {
    val gap = if (count > 3) 6f else 8f
    repeat(count) { i ->
        val x = startX + i * gap
        drawLine(Blue500, Offset(x + 2f, 37f), Offset(x - 1.5f, 45f), strokeWidth = STROKE, cap = StrokeCap.Round)
    }
}

private fun DrawScope.snow() {
    listOf(Offset(15f, 40f), Offset(24f, 44f), Offset(33f, 40f)).forEach { center ->
        repeat(3) { i ->
            val angle = Math.toRadians(i * 60.0 + 90)
            val d = Offset((cos(angle) * 3.2f).toFloat(), (sin(angle) * 3.2f).toFloat())
            drawLine(Blue300, center - d, center + d, strokeWidth = 1.8f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.bolt(dx: Float = 0f) {
    val bolt = Path().apply {
        moveTo(25f + dx, 33f)
        lineTo(19f + dx, 41f)
        lineTo(23.5f + dx, 41f)
        lineTo(21f + dx, 47f)
        lineTo(28.5f + dx, 38f)
        lineTo(24f + dx, 38f)
        lineTo(27f + dx, 33f)
        close()
    }
    drawPath(bolt, SunYellow)
}

private fun DrawScope.fog() {
    listOf(18f to 10f, 25f to 6f, 32f to 12f).forEach { (y, inset) ->
        drawLine(CloudGrey, Offset(inset, y), Offset(GRID - inset, y), strokeWidth = STROKE + 0.6f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.wind(scale: Float = 1f) {
    val style = Stroke(width = STROKE, cap = StrokeCap.Round)
    scale(scale, pivot = Offset(24f, 24f)) {
        drawPath(Path().apply {
            moveTo(6f, 18f)
            lineTo(30f, 18f)
            cubicTo(38f, 18f, 38f, 8f, 31f, 9f)
        }, Blue300, style = style)
        drawPath(Path().apply {
            moveTo(6f, 26f)
            lineTo(38f, 26f)
            cubicTo(46f, 26f, 46f, 37f, 38f, 36f)
        }, Blue300, style = style)
        drawLine(Blue300, Offset(10f, 33f), Offset(26f, 33f), strokeWidth = STROKE, cap = StrokeCap.Round)
    }
}
