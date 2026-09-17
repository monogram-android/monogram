package org.monogram.core.models

/**
 * Service media that carries no file: polls and quizzes, geo and live locations, contacts, venues
 * and dice.
 *
 * The native side sends a compact JSON payload in `MessageDto.file_name` (the same channel
 * checklists and webpage previews use), which survives room caching because `fileName` is a column;
 * no schema change is needed. [Message] exposes typed accessors below; the payload is parsed on
 * demand, so a cached message renders after a restart.
 *
 * JSON keys, as written by `native/mtproto-rs/src/media_rpc.rs`:
 * - poll: `q` question, `a` answers (`t` text, `c` chosen, `k` correct, `v` voters), `n` total
 *   voters, `z` quiz, `m` multiple choice, `x` closed, `o` public voters, `s` solution
 * - geo: `lat`, `long`, plus `live`, `p` period seconds and `h` heading for live locations
 * - venue: `lat`, `long`, `t` title, `a` address, `p` provider, `i` provider id, `y` venue type
 * - contact: `p` phone, `f` first name, `l` last name, `c` vcard, `u` user id
 * - dice: `v` value, `e` emoticon
 */
data class PollAnswer(
    val text: String,
    val chosen: Boolean = false,
    val correct: Boolean = false,
    val voters: Int = 0,
    /** Raw option bytes from the server, sent back verbatim when voting. */
    val option: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is PollAnswer &&
        text == other.text && chosen == other.chosen && correct == other.correct &&
        voters == other.voters && option.contentEquals(other.option)

    override fun hashCode(): Int =
        ((text.hashCode() * 31 + voters) * 31 + option.contentHashCode())
    val hasVotes: Boolean get() = voters > 0
}

data class Poll(
    val question: String,
    val answers: List<PollAnswer> = emptyList(),
    val totalVoters: Int = 0,
    val isQuiz: Boolean = false,
    val multipleChoice: Boolean = false,
    val closed: Boolean = false,
    val publicVoters: Boolean = false,
    val solution: String = "",
) {
    private val maxVoters: Int get() = answers.maxOfOrNull { it.voters } ?: 0

    /** Share of voters that picked [answer], in 0..1; zero while nothing is known yet. */
    fun share(answer: PollAnswer): Float {
        val base = if (publicVoters || closed || isQuiz) totalVoters else maxVoters
        if (base <= 0) return 0f
        return (answer.voters.toFloat() / base).coerceIn(0f, 1f)
    }

    /** A quiz shows correctness, a poll shows shares. */
    val showsShares: Boolean get() = !isQuiz

    val isAnswered: Boolean get() = answers.any { it.chosen }
}

data class GeoPlace(
    val latitude: Double,
    val longitude: Double,
    val live: Boolean = false,
    val periodSeconds: Int = 0,
    val heading: Int? = null,
) {
    /** "55.7512, 37.6184" with the sign kept, as the location card shows it. */
    val coordinates: String
        get() = String.format(java.util.Locale.US, "%.4f, %.4f", latitude, longitude)
}

data class VenueCard(
    val place: GeoPlace,
    val title: String,
    val address: String = "",
    val provider: String = "",
    val venueId: String = "",
    val venueType: String = "",
)

data class ContactCard(
    val phone: String,
    val firstName: String = "",
    val lastName: String = "",
    val vcard: String = "",
    val userId: Long = 0,
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { phone }
}

/** Dice/emoji game result: [value] is the roll, [emoticon] identifies the game (🎲, 🎯, 🏀, ...). */
data class Dice(
    val value: Int,
    val emoticon: String,
)

object ServiceMedia {
    fun poll(raw: String?): Poll? = parse(raw) { root ->
        val question = root["q"] as? String ?: return@parse null
        val answers = (root["a"] as? List<*>).orEmpty().mapNotNull { item ->
            val answer = item as? Map<*, *> ?: return@mapNotNull null
            val text = answer["t"] as? String ?: return@mapNotNull null
            PollAnswer(
                text = text,
                chosen = flag(answer["c"]),
                correct = flag(answer["k"]),
                voters = (answer["v"] as? Number)?.toInt() ?: 0,
                option = hexToBytes(answer["o"] as? String),
            )
        }
        Poll(
            question = question,
            answers = answers,
            totalVoters = (root["n"] as? Number)?.toInt() ?: 0,
            isQuiz = flag(root["z"]),
            multipleChoice = flag(root["m"]),
            closed = flag(root["x"]),
            publicVoters = flag(root["o"]),
            solution = root["s"] as? String ?: "",
        )
    }

    fun geo(raw: String?): GeoPlace? = parse(raw) { root -> place(root) }

    fun venue(raw: String?): VenueCard? = parse(raw) { root ->
        val place = place(root) ?: return@parse null
        VenueCard(
            place = place,
            title = root["t"] as? String ?: return@parse null,
            address = root["a"] as? String ?: "",
            provider = root["p"] as? String ?: "",
            venueId = root["i"] as? String ?: "",
            venueType = root["y"] as? String ?: "",
        )
    }

    fun contact(raw: String?): ContactCard? = parse(raw) { root ->
        val phone = root["p"] as? String ?: return@parse null
        ContactCard(
            phone = phone,
            firstName = root["f"] as? String ?: "",
            lastName = root["l"] as? String ?: "",
            vcard = root["c"] as? String ?: "",
            userId = (root["u"] as? Number)?.toLong() ?: 0,
        )
    }

    fun dice(raw: String?): Dice? = parse(raw) { root ->
        val value = (root["v"] as? Number)?.toInt() ?: return@parse null
        Dice(value = value, emoticon = root["e"] as? String ?: "")
    }

    private fun place(root: Map<*, *>): GeoPlace? {
        val lat = (root["lat"] as? Number)?.toDouble() ?: return null
        val long = (root["long"] as? Number)?.toDouble() ?: return null
        return GeoPlace(
            latitude = lat,
            longitude = long,
            live = flag(root["live"]),
            periodSeconds = (root["p"] as? Number)?.toInt() ?: 0,
            heading = (root["h"] as? Number)?.toInt(),
        )
    }

    private inline fun <T> parse(raw: String?, build: (Map<*, *>) -> T?): T? {
        if (raw.isNullOrBlank()) return null
        val root = CompactJson.parse(raw) as? Map<*, *> ?: return null
        return runCatching { build(root) }.getOrNull()
    }

    private fun hexToBytes(value: String?): ByteArray {
        val text = value?.takeIf { it.length % 2 == 0 } ?: return ByteArray(0)
        return runCatching {
            ByteArray(text.length / 2) { index ->
                text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrDefault(ByteArray(0))
    }

    private fun flag(value: Any?): Boolean = when (value) {
        is Number -> value.toInt() != 0
        is Boolean -> value
        else -> false
    }
}

/** Typed payloads for [mediaKind]; null when the message is not that kind. */
val Message.poll: Poll?
    get() = if (mediaKind == "poll") ServiceMedia.poll(fileName) else null

val Message.geoPlace: GeoPlace?
    get() = if (mediaKind == "geo") ServiceMedia.geo(fileName) else null

val Message.venueCard: VenueCard?
    get() = if (mediaKind == "venue") ServiceMedia.venue(fileName) else null

val Message.contactCard: ContactCard?
    get() = if (mediaKind == "contact") ServiceMedia.contact(fileName) else null

val Message.dice: Dice?
    get() = if (mediaKind == "dice") ServiceMedia.dice(fileName) else null
