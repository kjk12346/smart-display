package dev.smartdisplay.app.ui.ambient

import android.content.Context
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The order wallpaper photos are shown in: shuffled rounds, so each photo appears once before any repeats, and a
 * new round never starts with the photo that ended the last one. Photos removed from the folder drop out; new ones
 * join the current round at a random place, so they show up soon rather than after a whole round.
 */
class PhotoQueue(private val random: Random = Random.Default) {
    private val queue = ArrayDeque<String>()
    private val shownThisRound = mutableSetOf<String>()
    private var last: String? = null

    /** True when the next photo starts a new round: a good moment to list the folder again. */
    val roundOver: Boolean get() = queue.isEmpty()

    /** The next photo from [photos] (the folder's current contents), or null if there are none. */
    fun next(photos: List<String>): String? {
        if (photos.isEmpty()) return null
        val present = photos.toSet()
        queue.removeAll { it !in present }
        shownThisRound.retainAll(present)
        val added = present.filter { it !in shownThisRound && it !in queue }
        if (queue.isEmpty() && added.isEmpty()) {
            shownThisRound.clear()
            val round = present.shuffled(random).toMutableList()
            if (round.size > 1 && round.first() == last) round.add(round.removeAt(0))
            queue.addAll(round)
        } else {
            for (photo in added.shuffled(random)) queue.add(random.nextInt(queue.size + 1), photo)
        }
        return queue.removeFirst().also {
            shownThisRound += it
            last = it
        }
    }
}

/**
 * A photo's slow pan and zoom: from one framing to another, zooming in or out between [MIN_ZOOM] and [MAX_ZOOM] while
 * drifting across the middle. Pan positions are fractions (-1 to 1) of the room the zoom gives, so the photo always
 * covers the screen.
 */
data class PanZoom(
    val startZoom: Float,
    val endZoom: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
) {
    /** The framing at [progress] (0 at the start, 1 at the end). */
    fun at(progress: Float): Framing {
        fun between(a: Float, b: Float) = a * (1 - progress) + b * progress
        return Framing(between(startZoom, endZoom), between(startX, endX), between(startY, endY))
    }

    companion object {
        const val MIN_ZOOM = 1.05f
        const val MAX_ZOOM = 1.15f

        /** A random move: zoom in or out, panning from one side through the middle to the other. */
        fun random(random: Random = Random.Default): PanZoom {
            val zoomIn = random.nextBoolean()
            val angle = random.nextDouble(0.0, 2 * PI)
            val x = (0.8 * cos(angle)).toFloat()
            val y = (0.8 * sin(angle)).toFloat()
            return PanZoom(
                startZoom = if (zoomIn) MIN_ZOOM else MAX_ZOOM,
                endZoom = if (zoomIn) MAX_ZOOM else MIN_ZOOM,
                startX = x, startY = y, endX = -x, endY = -y,
            )
        }
    }
}

/** A zoom and a pan position: see [PanZoom]. */
data class Framing(val zoom: Float, val x: Float, val y: Float)

/**
 * The image request for a wallpaper photo, decoded big enough to fill the screen at the pan-and-zoom's closest zoom
 * and no bigger (old tablets have little memory). Preloading and showing use the same request, so the shown photo
 * comes straight from Coil's memory cache.
 */
fun wallpaperRequest(context: Context, url: String): ImageRequest {
    val metrics = context.resources.displayMetrics
    return ImageRequest.Builder(context)
        .data(url)
        .size((metrics.widthPixels * PanZoom.MAX_ZOOM).toInt(), (metrics.heightPixels * PanZoom.MAX_ZOOM).toInt())
        .scale(Scale.FILL)
        .build()
}
