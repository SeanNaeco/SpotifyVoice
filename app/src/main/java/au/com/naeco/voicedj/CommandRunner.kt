package au.com.naeco.voicedj

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The single place a phrase becomes an action, whether it arrived from the
 * wake-word service or from the tap-to-talk button.
 */
object CommandRunner {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** What's left of the last long answer, so "more" can continue it. */
    private var pending: List<String> = emptyList()

    private const val SPOKEN_BATCH = 5

    /**
     * Playlist names are full of emoji and punctuation that a speech engine
     * reads out literally ("fire emoji deep house"). Strip those for speech
     * only - the on-screen list keeps the real name.
     */
    private fun speakable(s: String): String =
        s.replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("[/_|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Speaks a count plus the first few, and shows the whole lot on screen. */
    private fun announceList(noun: String, items: List<String>, firstBatch: Boolean = true) {
        if (items.isEmpty()) {
            Bus.setStatus("No $noun found")
            speak("I could not find any $noun")
            return
        }

        val batch = items.take(SPOKEN_BATCH)
        pending = items.drop(SPOKEN_BATCH)

        val head = if (firstBatch) "You have ${items.size} $noun. " else ""
        val tail = if (pending.isNotEmpty()) " Say more to hear the rest." else ""
        speak(head + batch.joinToString(", ") { speakable(it) } + "." + tail)

        Bus.setStatus(
            if (firstBatch) "${items.size} $noun" else "${pending.size} more to go"
        )
        if (firstBatch) {
            Bus.setListing(items.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n"))
        }
    }

    fun initTts(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    private fun speak(text: String) {
        if (!Prefs.speakBack || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voicedj")
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    /** Cached library, re-synced if it is missing or more than six hours old. */
    private suspend fun freshLibrary(): Library {
        val cached = SpotifyClient.cachedLibrary()
        val stale = System.currentTimeMillis() - Prefs.librarySyncedAt > 6 * 3600_000L
        if (cached.playlists.isNotEmpty() && !stale) return cached
        return runCatching { SpotifyClient.syncLibrary() }.getOrDefault(cached)
    }

    suspend fun run(ctx: Context, raw: String) {
        initTts(ctx)
        val cmd = CommandParser.parse(raw)

        try {
            when (cmd.kind) {
                CommandParser.Kind.NOOP ->
                    Bus.setStatus("Didn't catch a command")

                CommandParser.Kind.NEXT -> {
                    SpotifyClient.next(); Bus.setStatus("Skipped")
                }
                CommandParser.Kind.PREVIOUS -> {
                    SpotifyClient.previous(); Bus.setStatus("Previous track")
                }
                CommandParser.Kind.PAUSE -> {
                    SpotifyClient.pause(); Bus.setStatus("Paused")
                }
                CommandParser.Kind.RESUME -> {
                    SpotifyClient.resume(); Bus.setStatus("Playing")
                }
                CommandParser.Kind.SHUFFLE_ON -> {
                    SpotifyClient.shuffle(true); Bus.setStatus("Shuffle on")
                }
                CommandParser.Kind.SHUFFLE_OFF -> {
                    SpotifyClient.shuffle(false); Bus.setStatus("Shuffle off")
                }
                CommandParser.Kind.REPEAT -> {
                    SpotifyClient.repeat(cmd.repeatMode); Bus.setStatus("Repeat ${cmd.repeatMode}")
                }
                CommandParser.Kind.VOLUME -> {
                    SpotifyClient.setVolume(cmd.value); Bus.setStatus("Volume ${cmd.value}%")
                }
                CommandParser.Kind.VOLUME_DELTA -> {
                    val target = (SpotifyClient.currentVolume() + cmd.delta).coerceIn(0, 100)
                    SpotifyClient.setVolume(target)
                    Bus.setStatus("Volume $target%")
                }

                CommandParser.Kind.WHATS_PLAYING -> {
                    val np = SpotifyClient.nowPlaying()
                    if (np == null) {
                        Bus.setStatus("Nothing playing"); speak("Nothing playing")
                    } else {
                        val line = "${np.title} by ${np.artists}"
                        Bus.setStatus(line); speak(line)
                    }
                }

                CommandParser.Kind.LIST_PLAYLISTS -> {
                    val lib = freshLibrary()
                    announceList("playlists", lib.playlists.map { it.name })
                }

                CommandParser.Kind.TOP_TRACKS -> {
                    Bus.setStatus("Checking what you have been playing\u2026")
                    val top = SpotifyClient.topTracks(20)
                    if (top.isEmpty()) {
                        Bus.setStatus("Spotify has no recent favourites for you yet")
                        speak("Spotify does not have enough recent listening to tell.")
                    } else {
                        pending = top.drop(SPOKEN_BATCH)
                        speak(
                            "Lately you have been playing " +
                                top.take(SPOKEN_BATCH).joinToString(", ") { speakable(it) } + "." +
                                if (pending.isNotEmpty()) " Say more to hear the rest." else ""
                        )
                        Bus.setStatus("Your top ${top.size} over the last few weeks")
                        Bus.setListing(top.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n"))
                    }
                }

                CommandParser.Kind.WHAT_DEVICE -> {
                    val np = SpotifyClient.nowPlaying()
                    val all = runCatching { SpotifyClient.devices() }.getOrDefault(emptyList())
                    val current = np?.device?.takeIf { it.isNotEmpty() }
                    val others = all.map { it.name }.filter { it != current && it.isNotEmpty() }

                    val line = when {
                        current != null && others.isEmpty() -> "Playing on $current."
                        current != null -> "Playing on $current. Also available: ${others.joinToString(", ")}."
                        all.isNotEmpty() -> "Nothing is playing. Available: ${all.joinToString(", ") { it.name }}."
                        else -> "No Spotify devices are available. Open Spotify and press play once."
                    }
                    Bus.setStatus(line)
                    speak(line)
                    Bus.setListing(all.joinToString("\n") { d ->
                        if (d.name == current) "\u25B6 ${d.name}  (playing)" else "  ${d.name}  (${d.type})"
                    })
                }

                CommandParser.Kind.SWITCH_DEVICE -> {
                    val where = cmd.device.orEmpty()
                    Bus.setStatus("Looking for \u201C$where\u201D\u2026")
                    val target = SpotifyClient.findDevice(where)
                    if (target == null) {
                        val all = runCatching { SpotifyClient.devices() }.getOrDefault(emptyList())
                        val msg = if (all.isEmpty())
                            "No Spotify devices are available. Open Spotify on it and press play once."
                        else
                            "I could not find \u201C$where\u201D. Available: " +
                                all.joinToString(", ") { it.name } + "."
                        Bus.setStatus(msg)
                        speak(msg)
                        Bus.setListing(all.joinToString("\n") { "  ${it.name}  (${it.type})" })
                    } else {
                        // carry the music across only if something was actually playing
                        val wasPlaying = runCatching { SpotifyClient.nowPlaying()?.isPlaying }
                            .getOrNull() ?: false
                        SpotifyClient.transferTo(target.id, keepPlaying = wasPlaying)
                        val line = if (wasPlaying) "Moved to ${target.name}"
                                   else "Switched to ${target.name}"
                        Bus.setStatus(line)
                        speak(line)
                        Bus.setListing("")
                    }
                }

                CommandParser.Kind.HELP -> {
                    val examples = listOf(
                        "play my deep house playlist",
                        "shuffle my shed work playlist",
                        "play thunderstruck by acdc",
                        "next, back, pause, resume",
                        "volume 40, louder, turn it down",
                        "whats playing",
                        "what playlists do i have",
                        "whats my favourite songs",
                        "whats it playing on",
                        "play my drive playlist on the kitchen speaker",
                        "switch to the shed speaker",
                        "what devices are there"
                    )
                    speak(
                        "You can say things like: " +
                            examples.take(4).joinToString("; ") +
                            ". The full list is on screen."
                    )
                    Bus.setStatus("${examples.size} things you can say")
                    Bus.setListing(examples.joinToString("\n") { "\u2022 $it" })
                }

                CommandParser.Kind.MORE -> {
                    if (pending.isEmpty()) {
                        Bus.setStatus("Nothing more to read out")
                        speak("That was all of them.")
                    } else {
                        announceList("", pending, firstBatch = false)
                    }
                }

                CommandParser.Kind.PLAY -> {
                    // a fresh play command retires any list still on screen
                    Bus.setListing("")
                    pending = emptyList()
                    Bus.setStatus("Looking for “${cmd.query}”…")

                    val lib = freshLibrary()
                    val found = SpotifyClient.resolve(cmd, lib)
                    if (found == null) {
                        Bus.setStatus("Couldn't find “${cmd.query}”")
                        speak("I couldn't find that")
                        return
                    }

                    if (cmd.shuffle) runCatching { SpotifyClient.shuffle(true) }
                    SpotifyClient.play(found.body, cmd.device)

                    Bus.setStatus("Playing ${found.label}")
                    speak("Playing ${found.label.replace("“", "").replace("”", "")}")
                }
            }
        } catch (e: SpotifyException) {
            val msg = when (e.code) {
                SpotifyException.Code.NO_DEVICE ->
                    "No active Spotify device — open Spotify and press play once"
                SpotifyException.Code.PREMIUM_REQUIRED ->
                    "Spotify Premium is required for remote playback"
                SpotifyException.Code.SIGNED_OUT ->
                    "Signed out — reconnect Spotify"
                else -> e.message ?: "Spotify error"
            }
            Bus.setStatus(msg)
            if (e.code == SpotifyException.Code.NO_DEVICE) speak("Open Spotify first")
        } catch (e: Exception) {
            Bus.setStatus(e.message ?: "Something went wrong")
        }
    }
}
