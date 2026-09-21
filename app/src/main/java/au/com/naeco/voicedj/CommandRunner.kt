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

                CommandParser.Kind.PLAY -> {
                    Bus.setStatus("Looking for “${cmd.query}”…")

                    var lib = SpotifyClient.cachedLibrary()
                    if (lib.playlists.isEmpty() ||
                        System.currentTimeMillis() - Prefs.librarySyncedAt > 6 * 3600_000L
                    ) {
                        lib = runCatching { SpotifyClient.syncLibrary() }.getOrDefault(lib)
                    }

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
