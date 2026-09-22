package au.com.naeco.voicedj

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Catches au.com.naeco.voicedj://callback?code=... from the Custom Tab and
 * hands the code straight to MainActivity, then gets out of the way.
 *
 * Two deliberate choices here, both learned the hard way:
 *
 *  - It does NO network work. An earlier version exchanged the token in this
 *    activity's lifecycleScope while the activity was finishing, so the
 *    coroutine was cancelled mid-flight, no token was ever stored, and the
 *    sign-in appeared to loop forever.
 *  - It is a plain Activity, not an AppCompatActivity. AppCompat demands a
 *    Theme.AppCompat descendant and throws IllegalStateException on anything
 *    else, which is what crashed it on the way back from Spotify.
 */
class AuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        forward(intent)
    }

    private fun forward(from: Intent?) {
        val uri = from?.data
        val next = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            uri?.getQueryParameter("code")?.let { putExtra(MainActivity.EXTRA_CODE, it) }
            uri?.getQueryParameter("error")?.let { putExtra(MainActivity.EXTRA_ERROR, it) }
        }
        startActivity(next)
        finish()
    }
}
