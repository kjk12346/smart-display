package dev.smartdisplay.app.ui.controls

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ha.NotConnectedException
import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A control that didn't work: either there's no connection, or Home Assistant refused or failed the action. */
data class ControlError(val name: String, val notConnected: Boolean)

/** Sends the controls screen's actions to Home Assistant. The screen updates from the resulting state changes. */
class ControlsViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home

    private val _errors = MutableSharedFlow<ControlError>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<ControlError> = _errors.asSharedFlow()

    fun call(call: ServiceCall, name: String) {
        viewModelScope.launch {
            try {
                home.callService(
                    domain = call.domain,
                    service = call.service,
                    data = call.data,
                    target = buildJsonObject { put("entity_id", call.entityId) },
                )
            } catch (e: NotConnectedException) {
                _errors.tryEmit(ControlError(name, notConnected = true))
            } catch (e: IOException) {
                Log.w(TAG, "${call.domain}.${call.service} on ${call.entityId} failed", e)
                _errors.tryEmit(ControlError(name, notConnected = false))
            }
        }
    }

    private companion object {
        const val TAG = "ControlsViewModel"
    }
}
