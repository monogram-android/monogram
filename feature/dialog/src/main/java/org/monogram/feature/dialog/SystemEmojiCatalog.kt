package org.monogram.feature.dialog

import android.graphics.Paint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import java.util.Locale

internal enum class SystemEmojiCategoryKind {
    Smileys,
    People,
    Nature,
    Food,
    Activities,
    Travel,
    Objects,
    Symbols,
    Flags,
}

internal data class SystemEmojiCategory(
    val kind: SystemEmojiCategoryKind,
    val icon: String,
    val glyphs: List<String>,
)

/** Builds the picker from Unicode emoji properties and glyphs available on this device. */
internal object SystemEmojiCatalog {
    private val cached by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        buildCategories { glyph -> paint.hasGlyph(glyph) }
    }

    fun categories(): List<SystemEmojiCategory> = cached

    internal fun keycapGlyphs(): List<String> = KEYCAP_BASES.map { "$it\uFE0F\u20E3" }

    internal fun skinToneCount(): Int = SKIN_TONES.size

    internal fun buildCategories(
        isoCountries: Array<String> = Locale.getISOCountries(),
        hasGlyph: (String) -> Boolean,
    ): List<SystemEmojiCategory> {
        val grouped = SystemEmojiCategoryKind.entries.associateWith { LinkedHashSet<String>() }
        val seen = LinkedHashSet<String>()
        fun add(kind: SystemEmojiCategoryKind, glyph: String) {
            if (glyph.isEmpty() || !seen.add(glyph) || !hasGlyph(glyph)) return
            grouped.getValue(kind).add(glyph)
        }

        val candidateRanges = listOf(
            0x00A9..0x00AE,
            0x203C..0x3299,
            0x1F000..0x1FAFF,
        )
        candidateRanges.forEach { range ->
            range.forEach { codePoint ->
                if (!isStandaloneEmoji(codePoint)) return@forEach
                val kind = categoryFor(codePoint)
                val base = emojiGlyph(codePoint)
                add(kind, base)
                if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_MODIFIER_BASE)) {
                    SKIN_TONES.forEach { tone -> add(kind, base + tone) }
                }
            }
        }

        KEYCAP_BASES.forEach { base ->
            add(SystemEmojiCategoryKind.Symbols, "$base\uFE0F\u20E3")
        }

        zwjPeople().forEach { add(SystemEmojiCategoryKind.People, it) }
        zwjFamilies().forEach { add(SystemEmojiCategoryKind.People, it) }
        zwjCouples().forEach { add(SystemEmojiCategoryKind.People, it) }

        isoCountries.sorted().forEach { country ->
            if (country.length != 2) return@forEach
            val flag = country.map { letter ->
                String(Character.toChars(0x1F1E6 + (letter.code - 'A'.code)))
            }.joinToString("")
            add(SystemEmojiCategoryKind.Flags, flag)
        }
        SUBDIVISION_FLAGS.forEach { add(SystemEmojiCategoryKind.Flags, it) }

        return listOf(
            SystemEmojiCategoryKind.Smileys to "😀",
            SystemEmojiCategoryKind.People to "👋",
            SystemEmojiCategoryKind.Nature to "🐻",
            SystemEmojiCategoryKind.Food to "🍔",
            SystemEmojiCategoryKind.Activities to "⚽",
            SystemEmojiCategoryKind.Travel to "🚗",
            SystemEmojiCategoryKind.Objects to "💡",
            SystemEmojiCategoryKind.Symbols to "❤️",
            SystemEmojiCategoryKind.Flags to "🏳️",
        ).mapNotNull { (kind, icon) ->
            grouped.getValue(kind).takeIf { it.isNotEmpty() }?.let {
                SystemEmojiCategory(kind, icon, it.toList())
            }
        }
    }

    private val SKIN_TONES = arrayOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")
    private val KEYCAP_BASES = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '#', '*')
    private const val ZWJ = "\u200D"
    private const val VS16 = "\uFE0F"
    private const val FEMALE = "\u2640$VS16"
    private const val MALE = "\u2642$VS16"
    private const val HEART = "\u2764$VS16"
    private const val KISS = "\uD83D\uDC8B"

    private val PROFESSIONS = listOf(
        "\u2695$VS16", "\uD83C\uDF93", "\uD83C\uDFEB", "\u2696$VS16", "\uD83C\uDF3E",
        "\uD83C\uDF73", "\uD83D\uDD27", "\uD83C\uDFED", "\uD83D\uDCBC", "\uD83D\uDD2C",
        "\uD83D\uDCBB", "\uD83C\uDFA4", "\uD83C\uDFA8", "\u2708$VS16", "\uD83D\uDE80",
        "\uD83D\uDE92",
    )

    private val SUBDIVISION_FLAGS = listOf(
        "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
        "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F",
        "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F",
    )

    private fun zwjPeople(): List<String> {
        val people = listOf("\uD83E\uDDD1", "\uD83D\uDC68", "\uD83D\uDC69")
        val gendered = listOf("", "${ZWJ}$FEMALE", "${ZWJ}$MALE")
        val out = ArrayList<String>()
        people.forEach { person ->
            gendered.forEach { gender ->
                val base = person + gender
                out += base
                SKIN_TONES.forEach { tone -> out += person + tone + gender }
                PROFESSIONS.forEach { job ->
                    out += base + ZWJ + job
                    SKIN_TONES.forEach { tone -> out += person + tone + gender + ZWJ + job }
                }
            }
        }
        return out
    }

    private fun zwjFamilies(): List<String> = listOf(
        "\uD83D\uDC68${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC67",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC67${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC66${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC67${ZWJ}\uD83D\uDC67",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC68${ZWJ}\uD83D\uDC67",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC68${ZWJ}\uD83D\uDC67${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC68${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC69${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC67",
        "\uD83D\uDC69${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC67${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC69${ZWJ}\uD83D\uDC69${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC68${ZWJ}\uD83D\uDC67",
        "\uD83D\uDC69${ZWJ}\uD83D\uDC66",
        "\uD83D\uDC69${ZWJ}\uD83D\uDC67",
    )

    private fun zwjCouples(): List<String> = listOf(
        "\uD83D\uDC69${ZWJ}$HEART${ZWJ}\uD83D\uDC68",
        "\uD83D\uDC69${ZWJ}$HEART${ZWJ}\uD83D\uDC69",
        "\uD83D\uDC68${ZWJ}$HEART${ZWJ}\uD83D\uDC68",
        "\uD83D\uDC69${ZWJ}$HEART${ZWJ}$KISS${ZWJ}\uD83D\uDC68",
        "\uD83D\uDC69${ZWJ}$HEART${ZWJ}$KISS${ZWJ}\uD83D\uDC69",
        "\uD83D\uDC68${ZWJ}$HEART${ZWJ}$KISS${ZWJ}\uD83D\uDC68",
        "\uD83D\uDC6B",
        "\uD83D\uDC6C",
        "\uD83D\uDC6D",
        "\uD83E\uDDD1${ZWJ}$HEART${ZWJ}\uD83E\uDDD1",
        "\uD83E\uDDD1${ZWJ}$HEART${ZWJ}$KISS${ZWJ}\uD83E\uDDD1",
        "\uD83E\uDD1D",
    )

    private fun isStandaloneEmoji(codePoint: Int): Boolean {
        if (!UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)) return false
        if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_MODIFIER)) return false
        if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_COMPONENT)) return false
        if (codePoint in 0x1F1E6..0x1F1FF) return false
        return codePoint !in '0'.code..'9'.code && codePoint != '#'.code && codePoint != '*'.code
    }

    private fun emojiGlyph(codePoint: Int): String {
        val value = String(Character.toChars(codePoint))
        return if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION)) {
            value
        } else {
            "$value\uFE0F"
        }
    }

    private fun categoryFor(codePoint: Int): SystemEmojiCategoryKind = when {
        codePoint in 0x1F600..0x1F644 ||
            codePoint in 0x1F910..0x1F92F ||
            codePoint in 0x1F970..0x1F97F ||
            codePoint in 0x1FAE0..0x1FAEF -> SystemEmojiCategoryKind.Smileys

        codePoint in 0x1F32D..0x1F37F -> SystemEmojiCategoryKind.Food
        codePoint in 0x1F380..0x1F3FA || codePoint in 0x1F93A..0x1F94F ->
            SystemEmojiCategoryKind.Activities

        codePoint in 0x1F44A..0x1F4A0 ||
            codePoint in 0x1F645..0x1F64F ||
            codePoint in 0x1F90C..0x1F90F ||
            codePoint in 0x1F9B0..0x1F9FF ||
            codePoint in 0x1FAC0..0x1FAFF -> SystemEmojiCategoryKind.People

        codePoint in 0x1F300..0x1F43E -> SystemEmojiCategoryKind.Nature
        codePoint in 0x1F680..0x1F6FF -> SystemEmojiCategoryKind.Travel
        codePoint in 0x1F4A1..0x1F5FF -> SystemEmojiCategoryKind.Objects
        codePoint in setOf(0x1F38C, 0x1F3C1, 0x1F3F3, 0x1F3F4, 0x1F6A9) ->
            SystemEmojiCategoryKind.Flags

        else -> SystemEmojiCategoryKind.Symbols
    }
}
