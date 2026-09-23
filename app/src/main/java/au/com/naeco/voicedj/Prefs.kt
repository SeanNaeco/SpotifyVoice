package au.com.naeco.voicedj

import android.content.Context
import android.content.SharedPreferences

/**
 * All persisted state. Lives in app-private storage, which on a non-rooted
 * device is readable only by this app.
 *
 * Consent is deliberately versioned: if the disclosure text ever changes in a
 * way that alters what someone agreed to, bump [CONSENT_VERSION] and every
 * install is asked again rather than inheriting an old "yes".
 */
object Prefs {

    const val CONSENT_VERSION = 1

    const val DECISION_NONE = "none"
    const val DECISION_ACCEPTED = "accepted"
    const val DECISION_DECLINED = "declined"

    private const val FILE = "voicedj"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ---- consent -------------------------------------------------------

    /** The decision made on THIS device, for the current disclosure version. */
    var consentDecision: String
        get() {
            val v = sp.getInt("consentVersion", -1)
            if (v != CONSENT_VERSION) return DECISION_NONE
            return sp.getString("consentDecision", DECISION_NONE) ?: DECISION_NONE
        }
        set(value) = sp.edit()
            .putString("consentDecision", value)
            .putInt("consentVersion", CONSENT_VERSION)
            .putLong("consentAt", System.currentTimeMillis())
            .apply()

    val consentAt: Long get() = sp.getLong("consentAt", 0L)

    val mayListenContinuously: Boolean get() = consentDecision == DECISION_ACCEPTED

    /** Withdrawing consent must take effect immediately, not at next launch. */
    fun withdrawConsent() {
        consentDecision = DECISION_DECLINED
    }

    // ---- spotify -------------------------------------------------------

    var clientId: String
        get() = sp.getString("clientId", "") ?: ""
        set(v) = sp.edit().putString("clientId", v).apply()

    var accessToken: String?
        get() = sp.getString("accessToken", null)
        set(v) = sp.edit().putString("accessToken", v).apply()

    var refreshToken: String?
        get() = sp.getString("refreshToken", null)
        set(v) = sp.edit().putString("refreshToken", v).apply()

    var expiresAt: Long
        get() = sp.getLong("expiresAt", 0L)
        set(v) = sp.edit().putLong("expiresAt", v).apply()

    var codeVerifier: String?
        get() = sp.getString("codeVerifier", null)
        set(v) = sp.edit().putString("codeVerifier", v).apply()

    var deviceId: String?
        get() = sp.getString("deviceId", null)
        set(v) = sp.edit().putString("deviceId", v).apply()

    val isSignedIn: Boolean get() = !accessToken.isNullOrEmpty() && clientId.isNotEmpty()

    fun signOut() {
        sp.edit()
            .remove("accessToken").remove("refreshToken").remove("expiresAt")
            .remove("deviceId").remove("libraryJson").remove("librarySyncedAt")
            .apply()
    }

    // ---- library cache -------------------------------------------------

    var libraryJson: String?
        get() = sp.getString("libraryJson", null)
        set(v) = sp.edit().putString("libraryJson", v).apply()

    var librarySyncedAt: Long
        get() = sp.getLong("librarySyncedAt", 0L)
        set(v) = sp.edit().putLong("librarySyncedAt", v).apply()

    // ---- wake word -----------------------------------------------------

    /** Free text. Changing it takes effect the next time listening starts. */
    var wakePhrase: String
        get() = sp.getString("wakePhrase", WakeWords.DEFAULT_PHRASE) ?: WakeWords.DEFAULT_PHRASE
        set(v) = sp.edit().putString("wakePhrase", v).apply()

    /**
     * true  = grammar mode: Vosk is told it only knows the wake phrase, which
     *         makes it far more willing to hear it (high recall).
     * false = free-form: Vosk transcribes all of English, which is much
     *         harder and is what made the phrase need shouting.
     */
    var wakeSensitive: Boolean
        get() = sp.getBoolean("wakeSensitive", true)
        set(v) = sp.edit().putBoolean("wakeSensitive", v).apply()

    /** Show every raw recogniser output on screen, for diagnosing misses. */
    var showDiagnostics: Boolean
        get() = sp.getBoolean("diag", true)
        set(v) = sp.edit().putBoolean("diag", v).apply()

    /** Duck Spotify while capturing a command so the music doesn't drown it out. */
    var duckWhileListening: Boolean
        get() = sp.getBoolean("duck", true)
        set(v) = sp.edit().putBoolean("duck", v).apply()

    var speakBack: Boolean
        get() = sp.getBoolean("speakBack", true)
        set(v) = sp.edit().putBoolean("speakBack", v).apply()
}
