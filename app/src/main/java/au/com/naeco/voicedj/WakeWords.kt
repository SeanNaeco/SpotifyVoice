package au.com.naeco.voicedj

import org.json.JSONArray

/**
 * The wake phrase is plain text you can change at any time — no training, no
 * account, no per-keyword licence. Vosk is given a tiny grammar containing
 * only this phrase plus "[unk]" (anything else), which makes spotting it both
 * cheap and accurate compared with transcribing everything that is said.
 */
object WakeWords {

    const val DEFAULT_PHRASE = "hey dj"

    /** Model folder inside assets, unpacked to files dir on first run. */
    const val ASSET_DIR = "model-en-us"
    const val UNPACK_DIR = "model"

    val phrase: String
        get() = Prefs.wakePhrase.ifBlank { DEFAULT_PHRASE }

    /**
     * Vosk grammar. Restricting the vocabulary this way is what lets a 40 MB
     * model run continuously without flattening the battery.
     */
    fun grammarJson(): String =
        JSONArray().put(Matcher.norm(phrase)).put("[unk]").toString()

    /**
     * Recognition is never exact, so accept a near miss rather than demanding
     * the phrase back verbatim — "hey deejay" should still count.
     */
    fun matches(heard: String): Boolean {
        val h = Matcher.norm(heard)
        if (h.isEmpty()) return false
        val p = Matcher.norm(phrase)
        return h == p || h.contains(p) || Matcher.score(p, h) >= 0.80
    }

    /** Anything said after the wake phrase in the same breath. */
    fun remainderAfterPhrase(heard: String): String {
        val h = Matcher.norm(heard)
        val p = Matcher.norm(phrase)
        val i = h.indexOf(p)
        return if (i >= 0) h.substring(i + p.length).trim() else ""
    }
}
