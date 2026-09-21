package au.com.naeco.voicedj

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Holds the microphone so the phone can stay in a pocket.
 *
 * Two stages, and only the second one can ever leave the device:
 *
 *   1. Vosk runs a small speech model entirely on this phone, with its
 *      vocabulary restricted to the wake phrase. Nothing is written to disk
 *      and nothing is transmitted. This stage runs continuously.
 *   2. Once the phrase is heard, the mic is handed to Android's recogniser
 *      for a few seconds to transcribe the actual command.
 *
 * The two engines cannot both hold the microphone, so the handover is
 * explicit — stop one, start the other, and always come back to stage 1.
 */
class WakeWordService : LifecycleService() {

    private var model: Model? = null
    private var vosk: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null

    private val main = Handler(Looper.getMainLooper())
    private var duckedFrom: Int? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        // Belt and braces: the service refuses to run without a live consent
        // record, even if something else tried to start it.
        if (!Prefs.mayListenContinuously) {
            Bus.setStatus("Listening is off because consent was withdrawn")
            stopEverything()
            return START_NOT_STICKY
        }

        loadModelThenListen()
        return START_STICKY
    }

    // ---- model ----------------------------------------------------------

    private fun loadModelThenListen() {
        if (model != null) { startWakeStage(); return }

        Bus.setStatus("Preparing the offline model…")
        StorageService.unpack(
            this, WakeWords.ASSET_DIR, WakeWords.UNPACK_DIR,
            { m ->
                model = m
                startWakeStage()
            },
            { e ->
                Bus.setStatus("Could not load the speech model: ${e.message}")
                stopEverything()
            }
        )
    }

    // ---- stage 1: on-device wake phrase ---------------------------------

    private fun startWakeStage() {
        if (stopping) return
        releaseCommandRecognizer()
        stopVosk()

        val m = model ?: return
        try {
            // Grammar-constrained: the recogniser will only ever return the
            // wake phrase or "[unk]", which keeps it fast and battery-light.
            val rec = Recognizer(m, SAMPLE_RATE, WakeWords.grammarJson())
            val svc = SpeechService(rec, SAMPLE_RATE)
            vosk = svc

            svc.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = JSONObject(hypothesis ?: "{}").optString("partial")
                    if (text.isNotBlank() && WakeWords.matches(text)) onWake(text)
                }

                override fun onResult(hypothesis: String?) {
                    val text = JSONObject(hypothesis ?: "{}").optString("text")
                    if (text.isNotBlank() && WakeWords.matches(text)) onWake(text)
                }

                override fun onFinalResult(hypothesis: String?) {}

                override fun onError(e: Exception?) {
                    Bus.setStatus("Wake word error: ${e?.message}")
                }

                override fun onTimeout() {
                    // Restart so listening never quietly dies.
                    main.post { if (!stopping) startWakeStage() }
                }
            })

            Bus.setRunning(true)
            Bus.setPhase(Bus.Phase.WAITING_FOR_WAKE)
            Bus.setStatus("Listening for “${WakeWords.phrase}”")
        } catch (e: Exception) {
            Bus.setStatus("Wake word failed to start: ${e.message}")
            stopEverything()
        }
    }

    private fun onWake(heard: String) {
        main.post {
            if (stopping || Bus.phase.value == Bus.Phase.CAPTURING) return@post

            // If the whole command came in one breath — "hey dj play my shed
            // work playlist" — skip the second stage entirely.
            val rest = WakeWords.remainderAfterPhrase(heard)
            stopVosk()

            if (rest.isNotBlank() && rest.split(" ").size > 1) {
                Bus.setHeard(heard)
                Bus.setPhase(Bus.Phase.WORKING)
                lifecycleScope.launch {
                    CommandRunner.run(this@WakeWordService, rest)
                    resumeWakeStage()
                }
            } else {
                duckSpotify()
                startCommandCapture()
            }
        }
    }

    private fun stopVosk() {
        vosk?.let { s ->
            runCatching { s.stop() }
            runCatching { s.shutdown() }
        }
        vosk = null
    }

    // ---- stage 2: transcribe the command --------------------------------

    private fun startCommandCapture() {
        Bus.setPhase(Bus.Phase.CAPTURING)
        Bus.setHeard("")
        Bus.setStatus("Go ahead")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Bus.setStatus("No speech recogniser on this device")
            resumeWakeStage()
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Bus.setPhase(Bus.Phase.WORKING) }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { Bus.setHeard(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()

                if (text.isBlank()) {
                    Bus.setStatus("Didn't catch that")
                    resumeWakeStage()
                    return
                }

                Bus.setHeard(text)
                lifecycleScope.launch {
                    CommandRunner.run(this@WakeWordService, text)
                    resumeWakeStage()
                }
            }

            override fun onError(error: Int) {
                Bus.setStatus(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Heard nothing"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was revoked"
                        else -> ""
                    }
                )
                resumeWakeStage()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        runCatching { sr.startListening(intent) }
            .onFailure {
                Bus.setStatus("Could not start the recogniser")
                resumeWakeStage()
            }
    }

    /** Back to stage 1, whatever happened in stage 2. */
    private fun resumeWakeStage() {
        main.postDelayed({
            releaseCommandRecognizer()
            unduckSpotify()
            if (!stopping) startWakeStage()
        }, 300)
    }

    private fun releaseCommandRecognizer() {
        recognizer?.let { r ->
            runCatching { r.stopListening() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    // ---- ducking --------------------------------------------------------

    private fun duckSpotify() {
        if (!Prefs.duckWhileListening) return
        lifecycleScope.launch {
            runCatching {
                val current = SpotifyClient.currentVolume()
                duckedFrom = current
                SpotifyClient.setVolume((current * 0.25).toInt())
            }
        }
    }

    private fun unduckSpotify() {
        val from = duckedFrom ?: return
        duckedFrom = null
        lifecycleScope.launch { runCatching { SpotifyClient.setVolume(from) } }
    }

    // ---- lifecycle ------------------------------------------------------

    private fun startForegroundNotice() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n: Notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("Microphone is open. Tap to open VoiceDJ.")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)          // cannot be swiped away while listening
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(App.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(App.NOTIF_ID, n)
        }
    }

    private fun stopEverything() {
        stopping = true
        releaseCommandRecognizer()
        stopVosk()
        unduckSpotify()
        Bus.setRunning(false)
        Bus.setPhase(Bus.Phase.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        releaseCommandRecognizer()
        stopVosk()
        runCatching { model?.close() }
        model = null
        Bus.setRunning(false)
        Bus.setPhase(Bus.Phase.IDLE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "au.com.naeco.voicedj.STOP"
        private const val SAMPLE_RATE = 16000.0f

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_STOP))
        }
    }
}
