package au.com.naeco.voicedj

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import au.com.naeco.voicedj.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var tapRecognizer: SpeechRecognizer? = null
    private var lastHandledCode: String? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast(getString(R.string.perm_mic_rationale))
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the service still runs without it; the notice is just less visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        CommandRunner.initTts(this)

        b.txtRedirect.text = "Redirect URI to register:\n${SpotifyAuth.REDIRECT_URI}"
        b.inClientId.setText(Prefs.clientId)

        b.btnConnect.setOnClickListener {
            val id = b.inClientId.text.toString().trim()
            if (id.isEmpty()) { toast("Paste your Client ID first"); return@setOnClickListener }
            SpotifyAuth.beginLogin(this, id)
        }

        b.btnListen.setOnClickListener { toggleListening() }
        b.btnTalk.setOnClickListener { tapToTalk() }
        b.btnSettings.setOnClickListener { showSettings() }

        b.btnNext.setOnClickListener { fire("next") }
        b.btnPrev.setOnClickListener { fire("previous") }
        b.btnToggle.setOnClickListener {
            lifecycleScope.launch {
                val np = runCatching { SpotifyClient.nowPlaying() }.getOrNull()
                fire(if (np?.isPlaying == true) "pause" else "resume")
            }
        }

        observeBus()
        handleAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        renderAuthState()
        refreshNowPlaying()
    }

    // ---- state ----------------------------------------------------------

    private fun renderAuthState() {
        val signedIn = Prefs.isSignedIn
        b.cardConnect.visibility = if (signedIn) View.GONE else View.VISIBLE
        b.cardListen.visibility = if (signedIn) View.VISIBLE else View.GONE

        if (signedIn && Prefs.libraryJson == null) {
            lifecycleScope.launch {
                Bus.setStatus("Syncing your library…")
                runCatching { SpotifyClient.syncLibrary() }
                    .onSuccess { Bus.setStatus("Ready — ${it.playlists.size} playlists") }
                    .onFailure { Bus.setStatus("Library sync failed: ${it.message}") }
            }
        }

        if (!Prefs.mayListenContinuously) {
            b.btnListen.isEnabled = false
            b.btnListen.alpha = 0.45f
            b.btnListen.text = "Always-listening not enabled"
        } else {
            b.btnListen.isEnabled = true
            b.btnListen.alpha = 1f
        }
    }

    private data class UiState(
        val phase: Bus.Phase,
        val heard: String,
        val status: String,
        val running: Boolean
    )

    private fun observeBus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    Bus.phase, Bus.heard, Bus.status, Bus.serviceRunning
                ) { phase, heard, status, running ->
                    UiState(phase, heard, status, running)
                }.collect { s ->
                    b.txtHeard.text = when {
                        s.heard.isNotEmpty() -> s.heard
                        s.phase == Bus.Phase.CAPTURING -> "Go ahead…"
                        s.phase == Bus.Phase.WAITING_FOR_WAKE ->
                            "Say “${WakeWords.phrase}”"
                        else -> "Not listening"
                    }
                    b.txtStatus.text = s.status

                    if (Prefs.mayListenContinuously) {
                        b.btnListen.text = if (s.running) "Stop listening" else "Start listening"
                    }
                    b.imgMic.alpha = if (s.phase == Bus.Phase.IDLE) 0.4f else 1f
                }
            }
        }
    }

    private fun refreshNowPlaying() {
        if (!Prefs.isSignedIn) return
        lifecycleScope.launch {
            val np = runCatching { SpotifyClient.nowPlaying() }.getOrNull()
            if (np == null) {
                b.cardNow.visibility = View.GONE
            } else {
                b.cardNow.visibility = View.VISIBLE
                b.txtNowTitle.text = np.title
                b.txtNowSub.text = listOf(np.artists, np.device).filter { it.isNotEmpty() }.joinToString("  ·  ")
                b.btnToggle.text = if (np.isPlaying) "Pause" else "Play"
            }
        }
    }

    // ---- actions --------------------------------------------------------

    private fun fire(phrase: String) {
        lifecycleScope.launch {
            CommandRunner.run(this@MainActivity, phrase)
            refreshNowPlaying()
        }
    }

    private fun toggleListening() {
        if (!Prefs.mayListenContinuously) {
            reviewConsent()
            return
        }
        if (Bus.serviceRunning.value) {
            WakeWordService.stop(this)
            return
        }
        if (!hasMic()) { askForMic(); return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        WakeWordService.start(this)
    }

    /** One-shot capture that needs no wake word and no consent to always-listen. */
    private fun tapToTalk() {
        if (!hasMic()) { askForMic(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recogniser on this device"); return
        }

        tapRecognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        tapRecognizer = sr

        Bus.setHeard("")
        Bus.setPhase(Bus.Phase.CAPTURING)
        Bus.setStatus("Listening…")

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Bus.setPhase(Bus.Phase.WORKING) }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { Bus.setHeard(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                Bus.setPhase(if (Bus.serviceRunning.value) Bus.Phase.WAITING_FOR_WAKE else Bus.Phase.IDLE)
                if (text.isBlank()) { Bus.setStatus("Didn't catch that"); return }
                Bus.setHeard(text)
                fire(text)
            }

            override fun onError(error: Int) {
                Bus.setPhase(if (Bus.serviceRunning.value) Bus.Phase.WAITING_FOR_WAKE else Bus.Phase.IDLE)
                Bus.setStatus(if (error == SpeechRecognizer.ERROR_NO_MATCH) "Didn't catch that" else "")
            }
        })

        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        })
    }

    // ---- permissions and dialogs ---------------------------------------

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun askForMic() {
        AlertDialog.Builder(this)
            .setTitle("Microphone")
            .setMessage(R.string.perm_mic_rationale)
            .setPositiveButton("Allow") { _, _ -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun promptForWakePhrase() {
        val input = android.widget.EditText(this).apply {
            hint = WakeWords.DEFAULT_PHRASE
            setText(Prefs.wakePhrase)
        }
        AlertDialog.Builder(this)
            .setTitle("Wake phrase")
            .setMessage("Two or three distinct syllables work best. Avoid words that come up in normal conversation.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                Prefs.wakePhrase = v.ifBlank { WakeWords.DEFAULT_PHRASE }
                if (Bus.serviceRunning.value) {
                    WakeWordService.stop(this)
                    WakeWordService.start(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reviewConsent() {
        startActivity(Intent(this, ConsentActivity::class.java)
            .putExtra(ConsentActivity.EXTRA_REVIEW, true))
    }

    private fun showSettings() {
        val consentLine = when (Prefs.consentDecision) {
            Prefs.DECISION_ACCEPTED ->
                "Always-listening: accepted on " +
                        DateFormat.getDateTimeInstance().format(Date(Prefs.consentAt))
            Prefs.DECISION_DECLINED -> "Always-listening: declined"
            else -> "Always-listening: not decided"
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("$consentLine\nWake phrase: \u201C${Prefs.wakePhrase}\u201D")
            .setPositiveButton("Change wake phrase") { _, _ -> promptForWakePhrase() }
            .setNeutralButton(
                if (Prefs.mayListenContinuously) "Withdraw consent" else "Review consent"
            ) { _, _ ->
                if (Prefs.mayListenContinuously) {
                    Prefs.withdrawConsent()
                    WakeWordService.stop(this)
                    toast("Always-listening withdrawn. Listening stopped.")
                    renderAuthState()
                } else {
                    reviewConsent()
                }
            }
            .setNegativeButton("Sign out of Spotify") { _, _ ->
                WakeWordService.stop(this)
                Prefs.signOut()
                renderAuthState()
            }
            .show()
    }

    // ---- finishing Spotify sign-in --------------------------------------

    /**
     * AuthRedirectActivity hands the authorization code here rather than
     * exchanging it itself. This activity sticks around for the whole network
     * call, so the coroutine can't be cancelled halfway -- which is what made
     * sign-in loop before.
     */
    private fun handleAuthIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ERROR)?.let { err ->
            intent.removeExtra(EXTRA_ERROR)
            Bus.setStatus("Spotify refused sign-in: $err")
            toast("Spotify refused sign-in: $err")
            return
        }

        val code = intent?.getStringExtra(EXTRA_CODE) ?: return
        intent.removeExtra(EXTRA_CODE)

        // An authorization code is single-use; spending it twice fails.
        if (code == lastHandledCode) return
        lastHandledCode = code

        Bus.setStatus("Finishing sign-in\u2026")
        lifecycleScope.launch {
            SpotifyAuth.exchangeCode(code)
                .onSuccess {
                    Bus.setStatus("Spotify connected")
                    renderAuthState()
                    refreshNowPlaying()
                }
                .onFailure { e ->
                    Bus.setStatus("Sign-in failed: ${e.message}")
                    toast("Sign-in failed: ${e.message}")
                }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_CODE = "spotify_code"
        const val EXTRA_ERROR = "spotify_error"
    }

    override fun onDestroy() {
        tapRecognizer?.destroy()
        tapRecognizer = null
        super.onDestroy()
    }
}
