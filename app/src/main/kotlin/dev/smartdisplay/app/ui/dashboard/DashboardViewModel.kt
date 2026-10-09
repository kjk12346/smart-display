package dev.smartdisplay.app.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartdisplay.app.SmartDisplayApp
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a button's run went, shown on it for a moment. */
enum class RunState { Running, Done, Failed }

/** Runs the kitchen dashboard's buttons (scenes, scripts, automations, Home and Away). */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home

    private val _runs = MutableStateFlow<Map<String, RunState>>(emptyMap())

    /** Buttons running now or just finished, by entity ID. */
    val runs: StateFlow<Map<String, RunState>> = _runs.asStateFlow()

    fun run(entityId: String) {
        val call = runCall(entityId) ?: return
        if (_runs.value[entityId] == RunState.Running) return
        _runs.update { it + (entityId to RunState.Running) }
        viewModelScope.launch {
            val result = try {
                home.callService(
                    domain = call.domain,
                    service = call.service,
                    data = call.data,
                    target = buildJsonObject { put("entity_id", entityId) },
                )
                RunState.Done
            } catch (e: IOException) {
                // Includes not being connected.
                Log.w(TAG, "${call.domain}.${call.service} on $entityId failed", e)
                RunState.Failed
            }
            _runs.update { it + (entityId to result) }
            delay(RESULT_SHOWN_MS)
            _runs.update { if (it[entityId] == result) it - entityId else it }
        }
    }

    private companion object {
        const val TAG = "DashboardViewModel"
        const val RESULT_SHOWN_MS = 2_500L
    }
}
