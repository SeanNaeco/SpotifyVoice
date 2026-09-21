package au.com.naeco.voicedj

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Catches au.com.naeco.voicedj://callback?code=... from the Custom Tab,
 * swaps the code for tokens, then bounces back into the app.
 */
class AuthRedirectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data
        val error = uri?.getQueryParameter("error")
        val code = uri?.getQueryParameter("code")

        when {
            error != null -> {
                toast("Spotify said: $error")
                finishToMain()
            }
            code != null -> lifecycleScope.launch {
                SpotifyAuth.exchangeCode(code)
                    .onFailure { toast("Sign-in failed: ${it.message}") }
                    .onSuccess { toast("Spotify connected") }
                finishToMain()
            }
            else -> finishToMain()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun finishToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
