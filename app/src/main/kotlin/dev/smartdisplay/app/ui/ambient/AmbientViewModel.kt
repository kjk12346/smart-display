package dev.smartdisplay.app.ui.ambient

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.kiosk.WallpaperConfig
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keeps today's forecast and the current wallpaper photo for the ambient screen, across rotations. */
class AmbientViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home

    private val _forecast = MutableStateFlow<Pair<String, DailyForecast>?>(null)

    /** The forecast and the weather entity it belongs to. */
    val forecast: StateFlow<Pair<String, DailyForecast>?> = _forecast.asStateFlow()

    private var fetchedAt = 0L
    private var fetchedFor: String? = null

    private val _wallpaper = MutableStateFlow<Wallpaper?>(null)

    /** The photo to show behind the clock, already downloaded; null for a plain background. */
    val wallpaper: StateFlow<Wallpaper?> = _wallpaper.asStateFlow()

    private val photoQueue = PhotoQueue()
    private var photos: List<String> = emptyList()
    private var listedFor: String? = null
    private var listedAt = 0L

    /**
     * Fetches the forecast for [entityId] now (unless it was fetched in the last [REFRESH_MS]) and then every
     * [REFRESH_MS], until cancelled. Run it only while connected and on screen.
     */
    suspend fun keepForecastFresh(entityId: String) {
        while (true) {
            val age = System.currentTimeMillis() - fetchedAt
            if (fetchedFor != entityId || age >= REFRESH_MS) {
                val fetched = fetch(entityId)
                val wait = if (fetched) REFRESH_MS else RETRY_MS
                delay(wait)
            } else {
                delay(REFRESH_MS - age)
            }
        }
    }

    private suspend fun fetch(entityId: String): Boolean = try {
        val result = home.callService(
            domain = "weather",
            service = "get_forecasts",
            data = buildJsonObject { put("type", "daily") },
            target = buildJsonObject { put("entity_id", entityId) },
            returnResponse = true,
        )
        parseDailyForecast(result, entityId)?.let { _forecast.value = entityId to it }
        fetchedAt = System.currentTimeMillis()
        fetchedFor = entityId
        true
    } catch (e: IOException) {
        // Includes a weather integration without daily forecasts (Home Assistant answers with an error).
        Log.w(TAG, "Forecast for $entityId failed", e)
        false
    }

    /**
     * Shows the photos in [config]'s folder one after another, each for its interval, until cancelled. Each photo is
     * downloaded before it's shown, so the screen never flashes empty. Run it only while connected and on screen.
     */
    suspend fun keepWallpaperFresh(config: WallpaperConfig) {
        val folder = config.folderId
        if (folder == null) {
            _wallpaper.value = null
            return
        }
        val interval = config.intervalMinutes.coerceAtLeast(1) * 60_000L
        while (true) {
            val current = _wallpaper.value
            val shownFor = System.currentTimeMillis() - (current?.shownAt ?: 0L)
            if (current != null && current.folderId == folder && shownFor < interval) {
                delay(interval - shownFor)
                continue
            }
            if (!showNextPhoto(folder)) delay(RETRY_MS)
        }
    }

    private suspend fun showNextPhoto(folder: String): Boolean = try {
        val now = System.currentTimeMillis()
        if (listedFor != folder || photoQueue.roundOver || now - listedAt >= LIST_REFRESH_MS) {
            photos = home.browseMedia(folder).children.filter { it.isImage }.map { it.contentId }
            listedFor = folder
            listedAt = now
        }
        val next = photoQueue.next(photos)
        if (next == null) {
            _wallpaper.value = null
            false
        } else {
            val url = home.resolveMedia(next).url
            val context = getApplication<Application>()
            when (val result = SingletonImageLoader.get(context).execute(wallpaperRequest(context, url))) {
                is SuccessResult -> {
                    Log.i(TAG, "Wallpaper: showing $next")
                    _wallpaper.value = Wallpaper(url, folder, System.currentTimeMillis())
                    true
                }
                is ErrorResult -> {
                    // Skipped until the next round; say which one, since an unreadable file would otherwise just
                    // never appear.
                    Log.w(TAG, "Wallpaper photo $next couldn't be loaded", result.throwable)
                    false
                }
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Wallpaper from $folder failed", e)
        false
    }

    private companion object {
        const val TAG = "AmbientViewModel"
        const val REFRESH_MS = 30 * 60_000L
        const val RETRY_MS = 60_000L
        /** The folder is listed again at each round's start and at least this often, for added or removed photos. */
        const val LIST_REFRESH_MS = 10 * 60_000L
    }
}

/** A downloaded wallpaper photo, from [folderId], shown since [shownAt]. */
data class Wallpaper(val url: String, val folderId: String, val shownAt: Long)
