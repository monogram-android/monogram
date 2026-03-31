package org.monogram.presentation.core.util

import org.monogram.presentation.core.util.Country.Companion.FALLBACK_LENGTH


/**
 * Object representation of selected country
 *
 * @property name country name
 * @property code country numeric code (for example, 7 or 380)
 * @property iso country ISO code (for example, RU, UA)
 * @property flagEmoji Unicode emoji symbol matching this country
 * @property mask optional mask for this number (without country code), can be `null`
 **/
data class Country(
    val name: String,
    val code: String,
    val iso: String,
    val flagEmoji: String,
    val mask: String? = null
) {
    companion object {
        private const val FALLBACK_LENGTH = 5
        private const val MASK_CHAR = 'X'
    }

    /**
     * Get minimum number of digits based on country mask
     *
     * @return number of digits according to the mask, or [FALLBACK_LENGTH], when mask is too low or empty
     **/
    fun getMobileNumberLength(): Int {
        val maskChars = mask?.filter { it == MASK_CHAR }?.takeIf { it.length >= FALLBACK_LENGTH }
        return maskChars?.length ?: FALLBACK_LENGTH
    }
}

/**
 * An object to get and manage countries
 **/
object CountryManager {
    private var countries: List<Country> = emptyList()

    /**
     * @return list of available countries, or empty list, if no valid data present
     **/
    fun getCountries(): List<Country> {
        if (countries.isNotEmpty()) return countries

        try {
            val inputStream = javaClass.getResourceAsStream("/countries.txt") ?: return emptyList()

            countries = inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(";")
                    if (parts.size >= 3) {
                        val code = parts[0]
                        val iso = parts[1]
                        val name = when (iso) {
                            "YL" -> "Telegram"
                            "FT" -> "Fragment Number"
                            else -> parts[2]
                        }
                        val mask = parts.getOrNull(3)
                        Country(
                            name = name,
                            code = code,
                            iso = iso,
                            flagEmoji = countryCodeToEmoji(iso),
                            mask = mask
                        )
                    } else null
                }.toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return countries
    }

    /**
     * Get country based on phone number
     *
     * @param phone phone to check
     * @return [Country] object associated with this phone
     **/
    fun getCountryForPhone(phone: String): Country? {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val allCountries = getCountries()

        val matches = allCountries.filter { digits.startsWith(it.code) }
        if (matches.isEmpty()) return null

        if (matches.size == 1) return matches[0]


        // 1. +7 Zone (Russia / Kazakhstan)
        if (digits.startsWith("7")) {
            val nextDigit = digits.getOrNull(1)
            return if (nextDigit == '7' || nextDigit == '6' || nextDigit == '0') {
                matches.find { it.iso == "KZ" } ?: matches.find { it.iso == "RU" } ?: matches[0]
            } else {
                matches.find { it.iso == "RU" } ?: matches.find { it.iso == "KZ" } ?: matches[0]
            }
        }

        // 2. +1 Zone (North America - USA, Canada, Caribbean, etc.)
        if (digits.startsWith("1")) {
            // Check for longer codes first (e.g., +1 876 for Jamaica)
            val specificMatch = matches.filter { it.code.length > 1 }
                .maxByOrNull { it.code.length }
            if (specificMatch != null) return specificMatch

            // Default to US if no specific Caribbean/territory code matches
            return matches.find { it.iso == "US" } ?: matches.find { it.iso == "CA" } ?: matches[0]
        }

        // 3. +44 Zone (UK / Guernsey / Jersey / Isle of Man)
        if (digits.startsWith("44")) {
            // Usually GB is the main one, others are territories
            return matches.find { it.iso == "GB" } ?: matches[0]
        }

        // 4. +33 Zone (France / French territories)
        if (digits.startsWith("33")) {
            return matches.find { it.iso == "FR" } ?: matches[0]
        }

        // 5. +358 Zone (Finland / Aland Islands)
        if (digits.startsWith("358")) {
            return matches.find { it.iso == "FI" } ?: matches[0]
        }

        return matches.maxByOrNull { it.code.length }
    }

    private fun countryCodeToEmoji(countryCode: String): String {
        if (countryCode == "FT") return "⭐"
        if (countryCode == "YL") return "✈️"

        if (countryCode.length != 2) return "🌐"
        val firstLetter = Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    }
}