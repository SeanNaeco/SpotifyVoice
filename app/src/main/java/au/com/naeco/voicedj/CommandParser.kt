package au.com.naeco.voicedj

/**
 * Turns a spoken phrase into an intent. Everything here runs on text that
 * [Matcher.norm] has already flattened, so apostrophes are gone by the time
 * the patterns see it ("what's" arrives as "what s").
 */
object CommandParser {

    enum class Kind {
        NOOP, PLAY, NEXT, PREVIOUS, PAUSE, RESUME, WHATS_PLAYING,
        SHUFFLE_ON, SHUFFLE_OFF, REPEAT, VOLUME, VOLUME_DELTA,
        LIST_PLAYLISTS, TOP_TRACKS, WHAT_DEVICE, HELP, MORE, SWITCH_DEVICE
    }

    enum class TargetType { PLAYLIST, ALBUM, ARTIST, TRACK }

    data class Command(
        val kind: Kind,
        val query: String = "",
        val type: TargetType? = null,
        val artist: String? = null,
        val shuffle: Boolean = false,
        val device: String? = null,
        val value: Int = 0,
        val delta: Int = 0,
        val repeatMode: String = "off"
    )

    private val NUMWORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100,
        "half" to 50, "full" to 100, "max" to 100
    )

    fun parse(raw: String): Command {
        var t = Matcher.norm(raw)
        if (t.isEmpty()) return Command(Kind.NOOP)

        t = t.replace(Regex("^(ok|okay|hey|yo|please|can you|could you|i want you to|i want to)\\s+"), "")
            .replace(Regex("\\b(on|in|from|with|using)\\s+spotify\\b"), "")
            .replace(Regex("\\bfor me\\b"), "")
            .replace(Regex("\\s+"), " ").trim()

        // ---- questions -------------------------------------------------
        // Checked before transport on purpose: "what s it playing on" also
        // matches the WHATS_PLAYING pattern, and "more" must not fall through
        // to a play command.
        if (Regex("^(what|which)\\s+(playlists|lists)\\b").containsMatchIn(t) ||
            Regex("^(list|name|read|tell me)\\s+(my\\s+)?(playlists|lists)\\b").containsMatchIn(t) ||
            Regex("^how many playlists\\b").containsMatchIn(t) ||
            Regex("^my playlists$").matches(t))
            return Command(Kind.LIST_PLAYLISTS)

        if (Regex("^(what ?s|whats|what is|what are)\\s+my\\s+(favourite|favorite|top|most played|best)\\b").containsMatchIn(t) ||
            Regex("^my\\s+(favourite|favorite|top)\\s+(song|songs|track|tracks|music|artist|artists)\\b").containsMatchIn(t) ||
            Regex("^what am i (into|listening to|playing a lot)\\b").containsMatchIn(t) ||
            Regex("^(top|favourite|favorite)\\s+(songs|tracks)$").matches(t))
            return Command(Kind.TOP_TRACKS)

        if (Regex("^(what|which)\\s+(device|speaker)").containsMatchIn(t) ||
            Regex("playing on$").containsMatchIn(t) ||
            Regex("^(where ?s|wheres|where is)\\s+(it|this|that|the music)\\s+playing\\b").containsMatchIn(t) ||
            Regex("^(coming from|coming out of)").containsMatchIn(t))
            return Command(Kind.WHAT_DEVICE)

        // "switch to the shed speaker" - a device change with no content named.
        // Checked here, before anything treats "switch" as a song title.
        run {
            val m = Regex("^(?:switch|change|move|transfer|send|cast|throw|shift)\\s+(?:it|this|music|playback|sound|audio|everything)?\\s*(?:to|over to|onto|on to|into)\\s+(.+)$").find(t)
                ?: Regex("^play\\s+(?:on|through)\\s+(.+)$").find(t)
                ?: Regex("^(?:output|audio|sound)\\s+(?:to|through)\\s+(.+)$").find(t)
                ?: Regex("^(?:switch|change|move|send|cast|shift)\\s+(?:it|this|music|playback)?\\s*(here)$").find(t)
            if (m != null) {
                val where = m.groupValues[1].replace(Regex("^(the|my)\\s+"), "").trim()
                // "move to the next song" is a transport command, not a device
                if (where.isNotEmpty() &&
                    !Regex("^(next|previous|last|another|other|shuffle|repeat|random|loop)\\b").containsMatchIn(where)
                ) {
                    return Command(Kind.SWITCH_DEVICE, device = where)
                }
            }
        }

        if (Regex("^(what can i say|what can you do|what do you do|help|commands|what commands)\\b").containsMatchIn(t))
            return Command(Kind.HELP)

        if (Regex("^(more|read more|more please|next few|rest of them|the rest|more of them)$").matches(t))
            return Command(Kind.MORE)

        // ---- transport -------------------------------------------------
        if (Regex("^(next|skip|next (track|song)|skip (this|it|track|song))\\b").containsMatchIn(t))
            return Command(Kind.NEXT)
        if (Regex("^(back|previous|go back|last (track|song)|previous (track|song))\\b").containsMatchIn(t))
            return Command(Kind.PREVIOUS)
        if (Regex("^(pause|stop|shut up|quiet|hold on)\\b").containsMatchIn(t))
            return Command(Kind.PAUSE)
        if (Regex("^(resume|unpause|continue|keep going|carry on|play)$").matches(t))
            return Command(Kind.RESUME)
        if (Regex("^(what ?s|whats|what is|who ?s|whos|who is)\\s+(playing|this|song|on|it)\\b").containsMatchIn(t) ||
            Regex("^what song is (this|playing)\\b").containsMatchIn(t))
            return Command(Kind.WHATS_PLAYING)
        if (Regex("^shuffle$").matches(t) || Regex("^shuffle (on|it|this)$").matches(t))
            return Command(Kind.SHUFFLE_ON)
        if (Regex("^(shuffle off|stop shuffling|no shuffle)$").matches(t))
            return Command(Kind.SHUFFLE_OFF)
        if (Regex("^(repeat|loop)( this| it| track| song)?$").matches(t))
            return Command(Kind.REPEAT, repeatMode = "track")
        if (Regex("^(repeat off|stop repeating|no repeat)$").matches(t))
            return Command(Kind.REPEAT, repeatMode = "off")
        if (Regex("^(louder|turn it up|volume up|crank it|pump it)\\b").containsMatchIn(t))
            return Command(Kind.VOLUME_DELTA, delta = 15)
        if (Regex("^(quieter|turn it down|volume down|softer)\\b").containsMatchIn(t))
            return Command(Kind.VOLUME_DELTA, delta = -15)

        Regex("^(?:set )?volume(?: to)? (.+)$").find(t)?.let { m ->
            val w = m.groupValues[1].trim()
            val n = w.toIntOrNull() ?: NUMWORDS[w]
            if (n != null) return Command(Kind.VOLUME, value = n.coerceIn(0, 100))
        }
        Regex("^turn(?: the)? volume(?: to)? (.+)$").find(t)?.let { m ->
            val w = m.groupValues[1].trim()
            val n = w.toIntOrNull() ?: NUMWORDS[w]
            if (n != null) return Command(Kind.VOLUME, value = n.coerceIn(0, 100))
        }

        // ---- "... on the kitchen speaker" -------------------------------
        var device: String? = null
        Regex("^(.*?)\\s+(?:on|through|to)(?: (?:the|my))? ([a-z0-9 ]+?(?:speaker|tv|kitchen|lounge|shed|office|desktop|laptop|phone|car|pc|mac))$")
            .find(t)?.let { m ->
                t = m.groupValues[1].trim()
                device = m.groupValues[2].trim()
            }

        // ---- shuffle baked into the phrase ------------------------------
        var shuffle = false
        if (Regex("^shuffle\\b").containsMatchIn(t)) {
            shuffle = true
            t = t.replace(Regex("^shuffle\\b"), "").trim()
        }
        if (Regex("\\bon shuffle$").containsMatchIn(t) || Regex("\\bshuffled$").containsMatchIn(t)) {
            shuffle = true
            t = t.replace(Regex("\\b(on shuffle|shuffled)$"), "").trim()
        }

        // ---- explicit type ----------------------------------------------
        var type: TargetType? = null
        val explicit = Regex("^(?:play\\s+)?(playlist|album|artist|song|track)\\s+(.+)$").find(t)
        if (explicit != null) {
            type = when (explicit.groupValues[1]) {
                "playlist" -> TargetType.PLAYLIST
                "album" -> TargetType.ALBUM
                "artist" -> TargetType.ARTIST
                else -> TargetType.TRACK
            }
            t = explicit.groupValues[2]
        } else {
            t = t.replace(Regex("^play\\s+"), "").trim()
            Regex("^(?:my |the )?(.+?)\\s+(playlist|album|mix)$").find(t)?.let { m ->
                type = if (m.groupValues[2] == "album") TargetType.ALBUM else TargetType.PLAYLIST
                t = m.groupValues[1]
            }
        }

        t = t.replace(Regex("^(my|the)\\s+"), "").trim()
        if (t.isEmpty()) return if (shuffle) Command(Kind.SHUFFLE_ON) else Command(Kind.NOOP)

        // ---- "<song> by <artist>" ---------------------------------------
        var byArtist: String? = null
        if (type != TargetType.PLAYLIST) {
            Regex("^(.+?)\\s+by\\s+(.+)$").find(t)?.let { m ->
                byArtist = m.groupValues[2].trim()
                t = m.groupValues[1].trim()
                if (type == null) type = TargetType.TRACK
            }
        }

        return Command(
            kind = Kind.PLAY,
            query = t,
            type = type,
            artist = byArtist,
            shuffle = shuffle,
            device = device
        )
    }
}
