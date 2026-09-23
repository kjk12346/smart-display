package dev.smartdisplay.app.ui.ambient

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dev.smartdisplay.app.SmartDisplayApp
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keeps today's forecast for the ambient screen, across rotations. */
class AmbientViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home

    private val _forecast = MutableStateFlow<Pair<String, DailyForecast>?>(null)

    /** The forecast and the weather entity it belongs to. */
    val forecast: StateFlow<Pair<String, DailyForecast>?> = _forecast.asStateFlow()

    private var fetchedAt = 0L
    private var fetchedFor: String? = null

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

    private companion object {
        const val TAG = "AmbientViewModel"
        const val REFRESH_MS = 30 * 60_000L
        const val RETRY_MS = 60_000L
    }
}
