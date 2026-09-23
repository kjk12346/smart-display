package dev.smartdisplay.app.server

import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

sealed interface CheckResult {
    data class Ok(val url: String) : CheckResult
    data class Failed(val problem: ServerProblem) : CheckResult
}

enum class ServerProblem {
    /** The typed text isn't an address. */
    InvalidAddress,
    /** The host name didn't resolve. */
    NotFound,
    /** Nothing answered, or the connection dropped. */
    NoAnswer,
    /** HTTPS failed, usually an untrusted or mismatched certificate. */
    Certificate,
    /** Something answered, but not Home Assistant. */
    NotHomeAssistant,
    /** Home Assistant hasn't been through its first-run setup, so nobody can sign in yet. */
    NotOnboarded,
}

/** Checks that a base URL is a Home Assistant server, using its login providers endpoint (no sign-in needed). */
class ServerCheck(private val http: OkHttpClient) {

    suspend fun check(url: String): CheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$url/auth/providers").build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.isSuccessful && looksLikeProviders(body) -> CheckResult.Ok(url)
                    response.code == 400 && "onboarding" in body.lowercase() ->
                        CheckResult.Failed(ServerProblem.NotOnboarded)
                    else -> CheckResult.Failed(ServerProblem.NotHomeAssistant)
                }
            }
        } catch (e: UnknownHostException) {
            CheckResult.Failed(ServerProblem.NotFound)
        } catch (e: SSLException) {
            CheckResult.Failed(ServerProblem.Certificate)
        } catch (e: IOException) {
            CheckResult.Failed(ServerProblem.NoAnswer)
        } catch (e: IllegalArgumentException) {
            CheckResult.Failed(ServerProblem.InvalidAddress)
        }
    }

    // Current versions return {"providers": [...], ...}; older ones returned the list itself.
    private fun looksLikeProviders(body: String): Boolean = try {
        when (val json = JSONTokener(body).nextValue()) {
            is JSONObject -> json.optJSONArray("providers") != null
            is JSONArray -> true
            else -> false
        }
    } catch (e: JSONException) {
        false
    }
}
