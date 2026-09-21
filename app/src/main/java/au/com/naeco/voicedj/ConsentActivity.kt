package au.com.naeco.voicedj

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity

/**
 * The launcher. Nobody reaches the app without passing through here once,
 * and anyone the APK is passed on to makes their own decision on their own
 * device — consent is stored per install and never travels with the file.
 *
 * Declining is a first-class outcome, not a dead end: the app still works,
 * it just never opens the microphone on its own.
 */
class ConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already decided on this device, for this version of the disclosure.
        if (Prefs.consentDecision != Prefs.DECISION_NONE && !forcedReview()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_consent)

        val check1 = findViewById<CheckBox>(R.id.checkUnderstand)
        val check2 = findViewById<CheckBox>(R.id.checkResponsible)
        val accept = findViewById<Button>(R.id.btnAccept)
        val decline = findViewById<Button>(R.id.btnDecline)

        // Both boxes must be ticked deliberately; no pre-ticked defaults,
        // and the accept button stays dead until they are.
        val gate = {
            accept.isEnabled = check1.isChecked && check2.isChecked
            accept.alpha = if (accept.isEnabled) 1f else 0.45f
        }
        gate()
        check1.setOnCheckedChangeListener { _, _ -> gate() }
        check2.setOnCheckedChangeListener { _, _ -> gate() }

        accept.setOnClickListener {
            Prefs.consentDecision = Prefs.DECISION_ACCEPTED
            goToMain()
        }

        decline.setOnClickListener {
            Prefs.consentDecision = Prefs.DECISION_DECLINED
            goToMain()
        }
    }

    /** Settings can send the user back here to read it again and change their mind. */
    private fun forcedReview(): Boolean = intent?.getBooleanExtra(EXTRA_REVIEW, false) == true

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_REVIEW = "review"
    }
}
