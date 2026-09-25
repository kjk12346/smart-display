package dev.smartdisplay.app.ui.ambient

import android.content.Context
import coil3.request.ImageRequest
import coil3.size.Scale
import kotlin.random.Random

/**
 * The order wallpaper photos are shown in: shuffled rounds, so each photo appears once before any repeats, and a
 * new round never starts with the photo that ended the last one. Photos removed from the folder drop out; new ones
 * join the next round.
 */
class PhotoQueue(private val random: Random = Random.Default) {
    private val queue = ArrayDeque<String>()
    private var last: String? = null

    /** The next photo from [photos] (the folder's current contents), or null if there are none. */
    fun next(photos: List<String>): String? {
        if (photos.isEmpty()) return null
        val present = photos.toSet()
        queue.removeAll { it !in present }
        if (queue.isEmpty()) {
            val round = photos.distinct().shuffled(random).toMutableList()
            if (round.size > 1 && round.first() == last) round.add(round.removeAt(0))
            queue.addAll(round)
        }
        return queue.removeFirst().also { last = it }
    }
}

/**
 * The image request for a wallpaper photo, decoded to fill the screen and no bigger (old tablets have little
 * memory). Preloading and showing use the same request, so the shown photo comes straight from Coil's memory cache.
 */
fun wallpaperRequest(context: Context, url: String): ImageRequest {
    val metrics = context.resources.displayMetrics
    return ImageRequest.Builder(context)
        .data(url)
        .size(metrics.widthPixels, metrics.heightPixels)
        .scale(Scale.FILL)
        .build()
}
