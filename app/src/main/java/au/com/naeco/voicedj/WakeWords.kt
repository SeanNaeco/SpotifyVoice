package au.com.naeco.voicedj

/**
 * The wake phrase is plain text and can be changed at any time - no training,
 * no account, no per-keyword licence.
 *
 * Matching here is deliberately STRICT. An earlier version scored the heard
 * text fuzzily and accepted anything over 0.80, which meant a bare "hey"
 * scored 0.90 against "hey dj" and fired the wake word on almost any noise.
 * The whole phrase now has to be present, word-aligned.
 */
object WakeWords {

    const val DEFAULT_PHRASE = "hey dj"

    /** Model folder inside assets, unpacked to files dir on first run. */
    const val ASSET_DIR = "model-en-us"
    const val UNPACK_DIR = "model"

    val phrase: String
        get() = Prefs.wakePhrase.ifBlank { DEFAULT_PHRASE }

    /**
     * Speech recognisers often spell short tokens out as separate letters -
     * "dj" comes back as "d j". Joining runs of single letters makes the
     * comparison survive that without loosening anything else.
     */
    private fun canonical(s: String): String {
        val out = StringBuilder()
        val run = StringBuilder()
        for (tok in Matcher.norm(s).split(" ").filter { it.isNotEmpty() }) {
            if (tok.length == 1 && tok[0].isLetter()) {
                run.append(tok)
            } else {
                if (run.isNotEmpty()) { out.append(run).append(' '); run.clear() }
                out.append(tok).append(' ')
            }
        }
        if (run.isNotEmpty()) out.append(run).append(' ')
        return out.toString().trim()
    }

    /** True only when the complete phrase appears, aligned to word boundaries. */
    fun matches(heard: String): Boolean {
        val h = canonical(heard)
        val p = canonical(phrase)
        if (h.isEmpty() || p.isEmpty()) return false
        return " $h ".contains(" $p ")
    }

    /** Anything said after the wake phrase in the same breath. */
    fun remainderAfterPhrase(heard: String): String {
        val h = canonical(heard)
        val p = canonical(phrase)
        val i = " $h ".indexOf(" $p ")
        if (i < 0) return ""
        // +1 for the leading space we added, then past the phrase itself
        return h.substring((i + p.length).coerceAtMost(h.length)).trim()
    }
}
