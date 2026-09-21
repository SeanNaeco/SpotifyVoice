package au.com.naeco.voicedj

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * Authorization Code with PKCE. No client secret — there is nowhere safe to
 * put one in a distributed app, and PKCE is what Spotify expects for native
 * clients anyway.
 */
object SpotifyAuth {

    const val REDIRECT_URI = "au.com.naeco.voicedj://callback"

    private const val AUTH_BASE = "https://accounts.spotify.com"

    private val SCOPES = listOf(
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-follow-read"
    ).joinToString(" ")

    private val http = OkHttpClient()

    // ---- step 1: send them to Spotify ----------------------------------

    fun beginLogin(ctx: Context, clientId: String) {
        val verifier = randomVerifier()
        Prefs.codeVerifier = verifier
        Prefs.clientId = clientId

        val url = Uri.parse("$AUTH_BASE/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("scope", SCOPES)
            .build()

        // A Custom Tab keeps them in their real browser session, so an
        // already-logged-in Spotify account signs in without retyping anything.
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, url)
    }

    // ---- step 2: swap the code for tokens ------------------------------

    suspend fun exchangeCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val verifier = Prefs.codeVerifier
            ?: return@withContext Result.failure(IllegalStateException("Missing PKCE verifier — start sign-in again"))

        val form = FormBody.Builder()
            .add("client_id", Prefs.clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        runCatching {
            http.newCall(Request.Builder().url("$AUTH_BASE/api/token").post(form).build())
                .execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (!res.isSuccessful) {
                        error(json.optString("error_description", json.optString("error", "token exchange failed")))
                    }
                    store(json)
                    Prefs.codeVerifier = null
                }
        }
    }

    // ---- step 3: keep it alive -----------------------------------------

    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        val rt = Prefs.refreshToken
            ?: return@withContext Result.failure(IllegalStateException("Signed out"))

        val form = FormBody.Builder()
            .add("client_id", Prefs.clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .build()

        runCatching {
            http.newCall(Request.Builder().url("$AUTH_BASE/api/token").post(form).build())
                .execute().use { res ->
                    val json = JSONObject(res.body?.string().orEmpty())
                    if (!res.isSuccessful) {
                        error(json.optString("error_description", "refresh failed"))
                    }
                    store(json)
                }
        }
    }

    private fun store(json: JSONObject) {
        Prefs.accessToken = json.getString("access_token")
        // Spotify only returns a new refresh token sometimes; keep the old one otherwise.
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { Prefs.refreshToken = it }
        Prefs.expiresAt = System.currentTimeMillis() + (json.optInt("expires_in", 3600) - 60) * 1000L
    }

    // ---- PKCE bits ------------------------------------------------------

    private fun randomVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
