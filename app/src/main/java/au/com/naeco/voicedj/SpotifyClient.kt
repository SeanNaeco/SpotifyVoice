package au.com.naeco.voicedj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SpotifyException(message: String, val code: Code = Code.OTHER) : Exception(message) {
    enum class Code { NO_DEVICE, PREMIUM_REQUIRED, RATE_LIMITED, SIGNED_OUT, OTHER }
}

data class LibItem(val name: String, val uri: String, val extra: String = "")

data class Library(
    val playlists: List<LibItem>,
    val albums: List<LibItem>,
    val artists: List<LibItem>
) {
    fun toJson(): String {
        fun arr(items: List<LibItem>) = JSONArray().apply {
            items.forEach { put(JSONObject().put("n", it.name).put("u", it.uri).put("x", it.extra)) }
        }
        return JSONObject()
            .put("playlists", arr(playlists))
            .put("albums", arr(albums))
            .put("artists", arr(artists))
            .toString()
    }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList())

        fun fromJson(s: String?): Library {
            if (s.isNullOrEmpty()) return EMPTY
            return runCatching {
                val o = JSONObject(s)
                fun list(key: String): List<LibItem> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map {
                        val i = a.getJSONObject(it)
                        LibItem(i.optString("n"), i.optString("u"), i.optString("x"))
                    }
                }
                Library(list("playlists"), list("albums"), list("artists"))
            }.getOrDefault(EMPTY)
        }
    }
}

/** What a resolved command turned into, ready to hand to the player. */
data class Resolution(val label: String, val body: JSONObject)

object SpotifyClient {

    private const val API = "https://api.spotify.com/v1"
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---- plumbing -------------------------------------------------------

    private suspend fun ensureToken() {
        if (Prefs.accessToken.isNullOrEmpty()) throw SpotifyException("Not signed in", SpotifyException.Code.SIGNED_OUT)
        if (System.currentTimeMillis() > Prefs.expiresAt) {
            SpotifyAuth.refresh().getOrElse {
                throw SpotifyException("Session expired — sign in again", SpotifyException.Code.SIGNED_OUT)
            }
        }
    }

    private suspend fun call(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        retryOn401: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        ensureToken()

        val url = if (path.startsWith("http")) path else API + path
        val rb = body?.toString()?.toRequestBody(JSON)
            ?: if (method != "GET") "".toRequestBody(JSON) else null

        val req = Request.Builder()
            .url(url)
            .method(method, rb)
            .header("Authorization", "Bearer ${Prefs.accessToken}")
            .build()

        http.newCall(req).execute().use { res ->
            when {
                res.code == 401 && retryOn401 -> {
                    SpotifyAuth.refresh().getOrElse {
                        throw SpotifyException("Session expired — sign in again", SpotifyException.Code.SIGNED_OUT)
                    }
                    return@withContext call(path, method, body, retryOn401 = false)
                }
                res.code == 204 -> return@withContext null
                res.code == 404 -> throw SpotifyException("No active device", SpotifyException.Code.NO_DEVICE)
                res.code == 403 -> {
                    val t = res.body?.string().orEmpty()
                    if (t.contains("premium", ignoreCase = true)) {
                        throw SpotifyException("Spotify Premium required", SpotifyException.Code.PREMIUM_REQUIRED)
                    }
                    throw SpotifyException("Spotify refused that request")
                }
                res.code == 429 -> throw SpotifyException("Rate limited — wait a moment", SpotifyException.Code.RATE_LIMITED)
                !res.isSuccessful -> {
                    val t = res.body?.string().orEmpty()
                    val msg = runCatching { JSONObject(t).getJSONObject("error").getString("message") }
                        .getOrDefault("Spotify error ${res.code}")
                    throw SpotifyException(msg)
                }
                else -> return@withContext res.body?.string()
            }
        }
    }

    private suspend fun getJson(path: String): JSONObject? =
        call(path)?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- library --------------------------------------------------------

    suspend fun syncLibrary(): Library {
        val playlists = mutableListOf<LibItem>()
        var next: String? = "/me/playlists?limit=50"
        while (next != null && playlists.size < 1000) {
            val j = getJson(next) ?: break
            val items = j.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                playlists += LibItem(
                    p.optString("name"),
                    p.optString("uri"),
                    p.optJSONObject("owner")?.optString("display_name").orEmpty()
                )
            }
            next = j.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }

        val albums = mutableListOf<LibItem>()
        runCatching {
            var an: String? = "/me/albums?limit=50"
            while (an != null && albums.size < 600) {
                val j = getJson(an) ?: break
                val items = j.optJSONArray("items") ?: break
                for (i in 0 until items.length()) {
                    val a = items.optJSONObject(i)?.optJSONObject("album") ?: continue
                    albums += LibItem(
                        a.optString("name"),
                        a.optString("uri"),
                        a.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                    )
                }
                an = j.optString("next").takeIf { it.isNotEmpty() && it != "null" }
            }
        }

        val artists = mutableListOf<LibItem>()
        runCatching {
            val j = getJson("/me/following?type=artist&limit=50")
            val items = j?.optJSONObject("artists")?.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val a = items.optJSONObject(i) ?: continue
                artists += LibItem(a.optString("name"), a.optString("uri"))
            }
        }

        val lib = Library(playlists, albums, artists)
        Prefs.libraryJson = lib.toJson()
        Prefs.librarySyncedAt = System.currentTimeMillis()
        return lib
    }

    fun cachedLibrary(): Library = Library.fromJson(Prefs.libraryJson)

    // ---- devices --------------------------------------------------------

    suspend fun devices(): List<Pair<String, String>> {
        val j = getJson("/me/player/devices") ?: return emptyList()
        val arr = j.optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            val d = arr.optJSONObject(it) ?: return@mapNotNull null
            d.optString("id") to d.optString("name")
        }
    }

    private suspend fun activeDeviceId(): String? {
        val j = getJson("/me/player/devices") ?: return null
        val arr = j.optJSONArray("devices") ?: return null
        if (arr.length() == 0) return null

        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        Prefs.deviceId?.let { saved ->
            if (all.any { it.optString("id") == saved && !it.optBoolean("is_restricted") }) return saved
        }
        val pick = all.firstOrNull { it.optBoolean("is_active") }
            ?: all.firstOrNull { !it.optBoolean("is_restricted") }
            ?: all.first()
        return pick.optString("id").takeIf { it.isNotEmpty() }
    }

    private suspend fun deviceIdByName(name: String): String? {
        val list = devices()
        return Matcher.best(name, list, floor = 0.45) { it.second }?.item?.first
    }

    // ---- resolving ------------------------------------------------------

    /**
     * Library first, always. A playlist of his beats a same-named public one,
     * which is exactly the behaviour Assistant gets wrong.
     */
    suspend fun resolve(cmd: CommandParser.Command, lib: Library): Resolution? {
        val q = cmd.query
        val type = cmd.type

        if (type == null || type == CommandParser.TargetType.PLAYLIST) {
            val floor = if (type == CommandParser.TargetType.PLAYLIST) 0.40 else 0.58
            Matcher.best(q, lib.playlists, floor) { it.name }?.let {
                return Resolution("your playlist “${it.item.name}”", JSONObject().put("context_uri", it.item.uri))
            }
        }
        if (type == null || type == CommandParser.TargetType.ALBUM) {
            val floor = if (type == CommandParser.TargetType.ALBUM) 0.45 else 0.72
            Matcher.best(q, lib.albums, floor) { it.name }?.let {
                return Resolution("your album “${it.item.name}”", JSONObject().put("context_uri", it.item.uri))
            }
        }
        if (type == null || type == CommandParser.TargetType.ARTIST) {
            val floor = if (type == CommandParser.TargetType.ARTIST) 0.45 else 0.82
            Matcher.best(q, lib.artists, floor) { it.name }?.let {
                return Resolution(it.item.name, JSONObject().put("context_uri", it.item.uri))
            }
        }

        // fall back to the public catalogue
        val types = when (type) {
            CommandParser.TargetType.TRACK -> "track"
            CommandParser.TargetType.ALBUM -> "album"
            CommandParser.TargetType.ARTIST -> "artist"
            CommandParser.TargetType.PLAYLIST -> "playlist"
            null -> "track,album,artist"
        }
        val sq = if (cmd.artist != null) "track:$q artist:${cmd.artist}" else q
        val r = getJson("/search?q=${enc(sq)}&type=$types&limit=10") ?: return null

        val track = r.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)
        val album = r.optJSONObject("albums")?.optJSONArray("items")?.optJSONObject(0)
        val artistObj = r.optJSONObject("artists")?.optJSONArray("items")?.optJSONObject(0)
        val playlist = r.optJSONObject("playlists")?.optJSONArray("items")?.optJSONObject(0)

        fun trackLabel(t: JSONObject): String {
            val names = t.optJSONArray("artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
            }.orEmpty()
            return "${t.optString("name")} — ${names.joinToString(", ")}"
        }

        when (type) {
            CommandParser.TargetType.TRACK ->
                track?.let { return Resolution(trackLabel(it), JSONObject().put("uris", JSONArray().put(it.optString("uri")))) }
            CommandParser.TargetType.ALBUM ->
                album?.let { return Resolution(it.optString("name"), JSONObject().put("context_uri", it.optString("uri"))) }
            CommandParser.TargetType.ARTIST ->
                artistObj?.let { return Resolution(it.optString("name"), JSONObject().put("context_uri", it.optString("uri"))) }
            CommandParser.TargetType.PLAYLIST ->
                playlist?.let { return Resolution("${it.optString("name")} (public playlist)", JSONObject().put("context_uri", it.optString("uri"))) }
            null -> {}
        }

        artistObj?.let {
            if (Matcher.score(q, it.optString("name")) > 0.9)
                return Resolution(it.optString("name"), JSONObject().put("context_uri", it.optString("uri")))
        }
        album?.let {
            if (Matcher.score(q, it.optString("name")) > 0.9)
                return Resolution(it.optString("name"), JSONObject().put("context_uri", it.optString("uri")))
        }
        track?.let { return Resolution(trackLabel(it), JSONObject().put("uris", JSONArray().put(it.optString("uri")))) }
        artistObj?.let { return Resolution(it.optString("name"), JSONObject().put("context_uri", it.optString("uri"))) }
        return null
    }

    // ---- playback -------------------------------------------------------

    suspend fun play(body: JSONObject, preferredDeviceName: String? = null) {
        val id = preferredDeviceName?.let { deviceIdByName(it) }
            ?: activeDeviceId()
            ?: throw SpotifyException("No active device", SpotifyException.Code.NO_DEVICE)
        call("/me/player/play?device_id=${enc(id)}", "PUT", body)
        Prefs.deviceId = id
    }

    suspend fun next() = call("/me/player/next", "POST").let {}
    suspend fun previous() = call("/me/player/previous", "POST").let {}
    suspend fun pause() = call("/me/player/pause", "PUT").let {}
    suspend fun resume() = call("/me/player/play", "PUT").let {}
    suspend fun shuffle(on: Boolean) = call("/me/player/shuffle?state=$on", "PUT").let {}
    suspend fun repeat(mode: String) = call("/me/player/repeat?state=$mode", "PUT").let {}
    suspend fun setVolume(pct: Int) = call("/me/player/volume?volume_percent=${pct.coerceIn(0, 100)}", "PUT").let {}
    suspend fun transferTo(deviceId: String) {
        call("/me/player", "PUT", JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", false))
        Prefs.deviceId = deviceId
    }

    suspend fun currentVolume(): Int =
        getJson("/me/player")?.optJSONObject("device")?.optInt("volume_percent", 50) ?: 50

    data class NowPlaying(val title: String, val artists: String, val device: String, val isPlaying: Boolean)

    suspend fun nowPlaying(): NowPlaying? {
        val p = getJson("/me/player") ?: return null
        val item = p.optJSONObject("item") ?: return null
        val names = item.optJSONArray("artists")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
        }.orEmpty()
        return NowPlaying(
            title = item.optString("name"),
            artists = names.joinToString(", "),
            device = p.optJSONObject("device")?.optString("name").orEmpty(),
            isPlaying = p.optBoolean("is_playing")
        )
    }
}
