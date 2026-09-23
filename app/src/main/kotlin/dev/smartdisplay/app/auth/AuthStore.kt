package dev.smartdisplay.app.auth

import android.content.Context
import androidx.core.content.edit

/** A sign-in started in the browser, kept on disk because Android may stop the app while the browser is open. */
class PendingSignIn(val state: String, val serverUrl: String)

/** Keeps the refresh token (encrypted) and any sign-in in progress. */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val cipher = TokenCipher()

    /** The refresh token for [serverUrl], or null if signed out, signed in to another server, or unreadable. */
    fun refreshToken(serverUrl: String): String? {
        if (prefs.getString(KEY_TOKEN_SERVER, null) != serverUrl) return null
        return prefs.getString(KEY_REFRESH_TOKEN, null)?.let(cipher::decrypt)
    }

    fun saveRefreshToken(serverUrl: String, refreshToken: String) {
        prefs.edit {
            putString(KEY_TOKEN_SERVER, serverUrl)
            putString(KEY_REFRESH_TOKEN, cipher.encrypt(refreshToken))
        }
    }

    fun clearRefreshToken() {
        prefs.edit {
            remove(KEY_TOKEN_SERVER)
            remove(KEY_REFRESH_TOKEN)
        }
    }

    fun savePending(pending: PendingSignIn) {
        prefs.edit {
            putString(KEY_PENDING_STATE, pending.state)
            putString(KEY_PENDING_SERVER, pending.serverUrl)
        }
    }

    /** Returns the sign-in in progress and forgets it, so a result can only be used once. */
    fun takePending(): PendingSignIn? {
        val state = prefs.getString(KEY_PENDING_STATE, null)
        val server = prefs.getString(KEY_PENDING_SERVER, null)
        prefs.edit {
            remove(KEY_PENDING_STATE)
            remove(KEY_PENDING_SERVER)
        }
        return if (state != null && server != null) PendingSignIn(state, server) else null
    }

    private companion object {
        const val KEY_TOKEN_SERVER = "token_server"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_PENDING_SERVER = "pending_server"
    }
}
