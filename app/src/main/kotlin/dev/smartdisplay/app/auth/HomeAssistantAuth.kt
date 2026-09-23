package dev.smartdisplay.app.auth

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * Identifies the app to Home Assistant. It must be a URL; Home Assistant fetches it and only sends sign-in results to
 * a `redirect_uri` that the page lists in a `<link rel="redirect_uri">` tag (see docs/index.html, served by GitHub
 * Pages). Changing it signs every display out, since refresh tokens are tied to the client_id.
 */
const val CLIENT_ID = "https://kjk12346.github.io/smart-display/"

/** Where Home Assistant sends the sign-in result; MainActivity handles this scheme. */
const val REDIRECT_URI = "smartdisplay://auth-callback"

class AccessToken(val token: String, private val expiresAtMillis: Long) {
    /** False a minute before expiry, so a request never goes out with a token about to lapse. */
    fun isFresh(now: Long = System.currentTimeMillis()) = now < expiresAtMillis - EXPIRY_MARGIN_MS

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

class Tokens(val access: AccessToken, val refreshToken: String)

class ServerConfig(val locationName: String?, val version: String?)

/** Home Assistant refused the request (bad or expired code or token), as opposed to not answering. */
class AuthRejectedException(val code: Int) : IOException("Home Assistant refused the request: HTTP $code")

/** Home Assistant's OAuth-style login endpoints. Stateless; [Session] keeps the tokens. */
class HomeAssistantAuth(private val http: OkHttpClient) {

    fun authorizeUrl(server: String, state: String): String =
        "$server/auth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("state", state)
            .build()
            .toString()

    suspend fun exchangeCode(server: String, code: String): Tokens {
        val json = postToken(
            server,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .build(),
        )
        return json.parsed { Tokens(accessToken(), getString("refresh_token")) }
    }

    suspend fun refresh(server: String, refreshToken: String): AccessToken =
        postToken(
            server,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .build(),
        ).parsed { accessToken() }

    /** Ends the sign-in on the server, so the refresh token stops working everywhere. */
    suspend fun revoke(server: String, refreshToken: String) {
        call(Request.Builder().url("$server/auth/revoke").post(FormBody.Builder().add("token", refreshToken).build()))
            .close()
    }

    suspend fun fetchConfig(server: String, accessToken: String): ServerConfig {
        val request = Request.Builder().url("$server/api/config").header("Authorization", "Bearer $accessToken")
        val json = call(request).use { it.jsonOrThrow() }
        return ServerConfig(json.optString("location_name").ifBlank { null }, json.optString("version").ifBlank { null })
    }

    private suspend fun postToken(server: String, body: FormBody): JSONObject =
        call(Request.Builder().url("$server/auth/token").post(body)).use { it.jsonOrThrow() }

    private suspend fun call(request: Request.Builder): Response = withContext(Dispatchers.IO) {
        http.newCall(request.build()).execute()
    }

    private fun Response.jsonOrThrow(): JSONObject {
        if (code in 400..499) throw AuthRejectedException(code)
        if (!isSuccessful) throw IOException("HTTP $code")
        return try {
            JSONObject(body.string())
        } catch (e: JSONException) {
            throw IOException("Unexpected response", e)
        }
    }

    /** Runs [read] on the response, treating a missing field like any other bad response. */
    private inline fun <T> JSONObject.parsed(read: JSONObject.() -> T): T = try {
        read()
    } catch (e: JSONException) {
        throw IOException("Unexpected response", e)
    }

    private fun JSONObject.accessToken() = AccessToken(
        token = getString("access_token"),
        expiresAtMillis = System.currentTimeMillis() + optLong("expires_in", DEFAULT_EXPIRES_IN_S) * 1000,
    )

    private companion object {
        const val DEFAULT_EXPIRES_IN_S = 1800L
    }
}
