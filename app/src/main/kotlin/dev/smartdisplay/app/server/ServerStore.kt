package dev.smartdisplay.app.server

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Home Assistant server this display uses. [uuid] lets it be found again by discovery if its address changes. */
data class SavedServer(val url: String, val name: String?, val uuid: String?)

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val _server = MutableStateFlow(load())
    val server: StateFlow<SavedServer?> = _server.asStateFlow()

    fun save(server: SavedServer) {
        prefs.edit {
            putString(KEY_URL, server.url)
            putString(KEY_NAME, server.name)
            putString(KEY_UUID, server.uuid)
        }
        _server.value = server
    }

    fun clear() {
        prefs.edit { clear() }
        _server.value = null
    }

    private fun load(): SavedServer? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        return SavedServer(url, prefs.getString(KEY_NAME, null), prefs.getString(KEY_UUID, null))
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_NAME = "name"
        const val KEY_UUID = "uuid"
    }
}
