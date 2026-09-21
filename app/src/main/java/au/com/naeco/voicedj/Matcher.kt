package au.com.naeco.voicedj

import java.text.Normalizer

/**
 * The reason this app exists. Assistant throws your spoken words at Spotify's
 * public catalogue; this scores them against the playlists you actually own,
 * after stripping the things speech recognition never reproduces — emoji,
 * punctuation, accents, and filler words.
 *
 * "play my deep house playlist" has to land on "🔥 Deep House Vibes".
 */
object Matcher {

    private val STOP = setOf("the", "a", "an", "my", "me", "some", "of", "on", "to", "please", "mix", "playlist", "list")

    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var t = s.lowercase()
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")          // strip accents
        t = t.replace("&", " and ")
        // keep letters/digits/space; this also removes emoji and punctuation
        t = t.replace(Regex("[^a-z0-9\\s]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun tokens(s: String?): List<String> =
        norm(s).split(" ").filter { it.isNotEmpty() && it !in STOP }

    /** 0.0 = no relation, 1.0 = identical. */
    fun score(query: String?, candidate: String?): Double {
        val q = norm(query)
        val c = norm(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0

        val qt = tokens(query)
        val ct = tokens(candidate)
        if (qt.isEmpty() || ct.isEmpty()) return 0.0

        val qKey = qt.joinToString(" ")
        val cKey = ct.joinToString(" ")
        if (qKey == cKey) return 0.97
        if (cKey.startsWith(qKey) || qKey.startsWith(cKey)) return 0.90
        if (c.contains(q) || cKey.contains(qKey)) return 0.84

        // token overlap, with partial credit for recognition slips
        var hits = 0.0
        for (t in qt) {
            if (ct.contains(t)) { hits += 1.0; continue }
            if (ct.any { x -> x.length > 3 && t.length > 3 && (x.startsWith(t) || t.startsWith(x)) }) {
                hits += 0.7
            }
        }
        val recall = hits / qt.size          // how much of what he said was found
        val precision = hits / ct.size       // how much of the name was covered
        if (recall == 0.0) return 0.0
        return 0.8 * recall + 0.2 * precision
    }

    data class Hit<T>(val item: T, val score: Double)

    /**
     * Best match above [floor]. The floor is the whole safety mechanism:
     * too low and "play taylor swift" grabs a random playlist, too high and
     * "deep house" misses "🔥 Deep House Vibes".
     */
    fun <T> best(query: String?, list: List<T>, floor: Double = 0.52, name: (T) -> String): Hit<T>? {
        var top: T? = null
        var topScore = floor
        for (item in list) {
            val s = score(query, name(item))
            if (s > topScore) { topScore = s; top = item }
        }
        return top?.let { Hit(it, topScore) }
    }
}
