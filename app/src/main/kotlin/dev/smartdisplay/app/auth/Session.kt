package dev.smartdisplay.app.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.smartdisplay.app.server.ServerStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SignInProblem {
    /** No browser to show Home Assistant's login page in. */
    NoBrowser,
    /** The browser came back without a result for the sign-in this app started. */
    Interrupted,
    /** Home Assistant refused the sign-in result. */
    Rejected,
    /** Home Assistant didn't answer while finishing the sign-in. */
    NoAnswer,
    /** The saved sign-in stopped working (revoked in Home Assistant, or unreadable). */
    Expired,
    /** The device's keystore couldn't encrypt the sign-in for storage. */
    CantStore,
}

sealed interface SessionState {
    data class SignedOut(val problem: SignInProblem? = null) : SessionState
    /** Back from the browser, swapping the sign-in code for tokens. */
    data object Finishing : SessionState
    data object SignedIn : SessionState
}

/**
 * The display's sign-in to Home Assistant: starts the browser login, finishes it when the redirect comes back, and
 * hands out access tokens, refreshing them as they expire. The refresh token is the only thing kept on disk.
 */
class Session(
    private val auth: HomeAssistantAuth,
    private val store: AuthStore,
    private val servers: ServerStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        if (currentServer()?.let(store::refreshToken) != null) SessionState.SignedIn else SessionState.SignedOut()
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val tokenLock = Mutex()
    private var access: AccessToken? = null

    /** Opens Home Assistant's login page in a Custom Tab (or the default browser if Custom Tabs aren't supported). */
    fun beginSignIn(context: Context) {
        val server = currentServer() ?: return
        val state = randomState()
        store.savePending(PendingSignIn(state, server))
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(TOOLBAR_COLOR).build())
            .build()
        try {
            tab.launchUrl(context, auth.authorizeUrl(server, state).toUri())
            _state.value = SessionState.SignedOut()
        } catch (e: ActivityNotFoundException) {
            _state.value = SessionState.SignedOut(SignInProblem.NoBrowser)
        }
    }

    /** Finishes a sign-in from the [REDIRECT_URI] callback. */
    fun handleRedirect(uri: Uri) {
        val pending = store.takePending()
        val code = uri.getQueryParameter("code")
        val valid = pending != null && code != null &&
            uri.getQueryParameter("state") == pending.state &&
            pending.serverUrl == currentServer()
        if (!valid) {
            _state.value = SessionState.SignedOut(SignInProblem.Interrupted)
            return
        }
        _state.value = SessionState.Finishing
        scope.launch {
            _state.value = try {
                val tokens = auth.exchangeCode(pending!!.serverUrl, code!!)
                store.saveRefreshToken(pending.serverUrl, tokens.refreshToken)
                tokenLock.withLock { access = tokens.access }
                SessionState.SignedIn
            } catch (e: AuthRejectedException) {
                SessionState.SignedOut(SignInProblem.Rejected)
            } catch (e: IOException) {
                SessionState.SignedOut(SignInProblem.NoAnswer)
            } catch (e: GeneralSecurityException) {
                SessionState.SignedOut(SignInProblem.CantStore)
            }
        }
    }

    /**
     * A current access token, refreshed if it's about to expire, or null if signed out (including when Home Assistant
     * refuses the refresh token). Throws [IOException] if Home Assistant can't be reached; the caller should retry.
     */
    suspend fun accessToken(): String? = tokenLock.withLock {
        val server = currentServer() ?: return null
        access?.takeIf { it.isFresh() }?.let { return it.token }
        val refreshToken = store.refreshToken(server)
        if (refreshToken == null) {
            if (_state.value == SessionState.SignedIn) _state.value = SessionState.SignedOut(SignInProblem.Expired)
            return null
        }
        try {
            auth.refresh(server, refreshToken).also { access = it }.token
        } catch (e: AuthRejectedException) {
            access = null
            store.clearRefreshToken()
            _state.value = SessionState.SignedOut(SignInProblem.Expired)
            null
        }
    }

    /** Checks the sign-in end to end with an authenticated request. Null if signed out. */
    suspend fun serverConfig(): ServerConfig? {
        val server = currentServer() ?: return null
        val token = accessToken() ?: return null
        return auth.fetchConfig(server, token)
    }

    /** Signs out here and, if Home Assistant answers, revokes the sign-in there too. */
    suspend fun signOut() {
        val server = currentServer()
        val refreshToken = server?.let(store::refreshToken)
        tokenLock.withLock {
            access = null
            store.clearRefreshToken()
        }
        _state.value = SessionState.SignedOut()
        if (server != null && refreshToken != null) {
            try {
                auth.revoke(server, refreshToken)
            } catch (e: IOException) {
                // Signed out here regardless; the token expires on the server if it's never used again.
            }
        }
    }

    /** Signs out and forgets the server, back to setup. */
    suspend fun forgetServer() {
        signOut()
        servers.clear()
    }

    private fun currentServer(): String? = servers.server.value?.url

    private fun randomState(): String {
        val bytes = ByteArray(STATE_BYTES).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val STATE_BYTES = 24
        const val TOOLBAR_COLOR = 0xFF151517.toInt()
    }
}
