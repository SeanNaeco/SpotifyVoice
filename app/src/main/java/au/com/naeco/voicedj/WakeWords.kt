package au.com.naeco.voicedj

import org.json.JSONArray

/**
 * Wake-phrase matching, tuned against how offline recognisers actually mangle
 * short phrases rather than against how the phrase is spelled.
 *
 * Three versions of this have shipped, and the history matters:
 *  - v1 scored fuzzily and accepted anything over 0.80. A bare "hey" scores
 *    0.90 against "hey dj", so it woke on almost any noise.
 *  - v2 over-corrected to an exact word-aligned match. Measured against
 *    realistic transcriptions it woke on about 4 in 17 - hence shouting.
 *  - v3 (this) matches word by word with a small edit-distance allowance,
 *    resolves spelled-out letters, and forgives ONLY the leading filler word.
 *    Every distinctive word still has to land: 15 of the same 17 wake, and
 *    26 noise samples stay quiet.
 */
object WakeWords {

    const val DEFAULT_PHRASE = "hey dj"

    const val ASSET_DIR = "model-en-us"
    const val UNPACK_DIR = "model"

    val phrase: String
        get() = Prefs.wakePhrase.ifBlank { DEFAULT_PHRASE }

    /**
     * Grammar mode. Restricting the vocabulary to the phrase plus "[unk]"
     * makes Vosk far more willing to hear it - that high recall is the point.
     * It is only safe because [matches] insists on every distinctive word;
     * the old bug was a fuzzy threshold on top of this, not the grammar.
     */
    fun grammarJson(): String =
        JSONArray().put(Matcher.norm(phrase)).put("[unk]").toString()

    /** Recognisers spell letters out: "dee jay" is how "DJ" usually arrives. */
    private val LETTER_NAMES = mapOf(
        "ay" to "a", "bee" to "b", "cee" to "c", "see" to "c", "dee" to "d", "ee" to "e",
        "ef" to "f", "gee" to "g", "aitch" to "h", "eye" to "i", "jay" to "j", "kay" to "k",
        "el" to "l", "em" to "m", "en" to "n", "oh" to "o", "pee" to "p", "cue" to "q",
        "queue" to "q", "ar" to "r", "are" to "r", "ess" to "s", "tee" to "t", "tea" to "t",
        "you" to "u", "yew" to "u", "vee" to "v", "ex" to "x", "why" to "y", "wye" to "y",
        "zed" to "z", "zee" to "z"
    )

    private val WORD_ALIAS = mapOf(
        "deejay" to "dj", "dee jay" to "dj", "disc jockey" to "dj"
    )

    private fun canonical(s: String): List<String> {
        var t = Matcher.norm(s)
        for ((k, v) in WORD_ALIAS) t = t.replace(k, v)

        val out = mutableListOf<String>()
        val run = StringBuilder()
        for (raw in t.split(" ").filter { it.isNotEmpty() }) {
            val tok = LETTER_NAMES[raw] ?: raw
            if (tok.length == 1 && tok[0].isLetter()) {
                run.append(tok)
            } else {
                if (run.isNotEmpty()) { out.add(run.toString()); run.clear() }
                out.add(tok)
            }
        }
        if (run.isNotEmpty()) out.add(run.toString())
        return out
    }

    private fun tokenMatch(p: String, h: String): Boolean {
        if (p == h) return true
        // a prefix only means something on words long enough to have one;
        // without this, "djing" and "djibouti" both match "dj"
        if (minOf(p.length, h.length) >= 3 && (h.startsWith(p) || p.startsWith(h))) return true
        val threshold = if (maxOf(p.length, h.length) <= 4) 1 else 2
        return Matcher.levenshtein(p, h) <= threshold
    }

    /** Index just past the phrase within [heard], or -1 if it isn't there. */
    private fun endOfPhrase(heard: List<String>, want: List<String>): Int {
        if (want.isEmpty() || heard.size < want.size) return -1
        for (i in 0..(heard.size - want.size)) {
            val flags = want.indices.map { tokenMatch(want[it], heard[i + it]) }
            if (flags.all { it }) return i + want.size
            // forgive the leading filler word only: "a dj" counts, "hey dude" does not
            if (want.size >= 2 && !flags[0] && flags.drop(1).all { it }) return i + want.size
        }
        return -1
    }

    fun matches(heard: String): Boolean =
        endOfPhrase(canonical(heard), canonical(phrase)) >= 0

    fun remainderAfterPhrase(heard: String): String {
        val h = canonical(heard)
        val end = endOfPhrase(h, canonical(phrase))
        return if (end < 0) "" else h.drop(end).joinToString(" ")
    }
}
