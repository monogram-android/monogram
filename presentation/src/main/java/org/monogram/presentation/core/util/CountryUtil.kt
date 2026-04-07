package org.monogram.presentation.core.util

import com.google.i18n.phonenumbers.PhoneNumberUtil
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

data class CustomCountryRule(val code: String, val formatIt: Boolean, val maskIt: Boolean)

/**
 * An object to get and manage countries
 **/
object CountryManager {
    private var countries: List<Country> = emptyList()
    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    private val customRules: List<CustomCountryRule> = listOf(
        CustomCountryRule("42", formatIt = false, maskIt = false),
        CustomCountryRule("888", formatIt = true, maskIt = true),
        CustomCountryRule("881", formatIt = false, maskIt = true),
        CustomCountryRule("882", formatIt = false, maskIt = true),
        CustomCountryRule("883", formatIt = false, maskIt = true),
    )

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

    private fun format888(digits: String): String {
        val rest = digits.removePrefix("888")

        val part1 = rest.take(4)
        val part2 = rest.drop(4).take(4)

        return buildString {
            append("+888")
            if (part1.isNotEmpty()) append(" $part1")
            if (part2.isNotEmpty()) append(" $part2")
        }
    }

    /**
     * Format raw phone number to international format
     *
     * @param raw raw phone number string (for example, +79991234567)
     * @return formatted phone number in international format (for example, +7 999 123-45-67),
     * or [raw] if parsing fails
     **/
    fun formatPhoneNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val rule = customRules.firstOrNull { digits.startsWith(it.code) }

        if (rule != null) {
            if (!rule.formatIt)
                return raw

            if (rule.code == "888")
                return format888(digits)
        }

        return runCatching {
            phoneUtil.format(
                phoneUtil.parse(raw, null),
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
            )
        }.getOrDefault(raw)
    }

    /**
     * Mask formatted phone number, leaving last 4 digits visible
     *
     * @param formatted phone number already formatted via [format]
     * @return masked phone number (for example, +* ***) ***-**-67),
     * or "****" if number has less than 5 digits
     **/
    fun maskPhoneNumber(formatted: String): String {
        val digits = formatted.filter { it.isDigit() }
        val rule = customRules.firstOrNull { digits.startsWith(it.code) }

        if (rule != null) {
            if (!rule.maskIt) return formatted

            var digitIndex = 0
            val result = buildString {
                formatted.forEach { c ->
                    if (c.isDigit()) {
                        append(
                            if (digitIndex < rule.code.length || digitIndex >= digits.length - 4) c else '*'
                        )
                        digitIndex++
                    } else append(c)
                }
            }
            return if (!result.startsWith("+")) "+$result" else result
        }

        if (digits.length < 5) return "****"

        var digitIndex = 0
        val masked = formatted.map { c ->
            if (c.isDigit()) {
                val idx = digitIndex++
                if (idx == 0 || idx >= digits.length - 4) c else '*'
            } else c
        }.joinToString("")

        return if (!masked.startsWith("+")) "+$masked" else masked
    }

    /**
     * Check if phone number is valid for given country
     *
     * @param phone phone number to validate
     * @param iso country ISO code (for example, RU, UA)
     * @return `true` if number is valid, `false` otherwise or if parsing fails
     **/
    fun isValidPhoneNumber(phone: String, iso: String): Boolean {
        val digits = phone.filter { it.isDigit() }

        if (customRules.any { digits.startsWith(it.code) && digits.length - it.code.length >= 3 }) {
            return true
        }

        return runCatching {
            phoneUtil.isValidNumber(phoneUtil.parse(phone, iso))
        }.getOrDefault(false)
    }

    /**
     * Format incomplete phone number body to local format as you type
     *
     * @param iso country ISO code (for example, RU, UA)
     * @param raw phone number body without country code (for example, 9991234567)
     * @return formatted phone number body (for example, 999 123-45-67)
     **/
    fun formatPartialPhoneNumber(iso: String, raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val country = countries.firstOrNull { it.iso == iso }

        if (country == null)
            return raw

        val rule = customRules.firstOrNull { it.code == country.code }

        if (rule != null) {
            if (!rule.formatIt) return digits

            if (rule.code == "888") {
                val formatted = format888("888$digits")
                return formatted.removePrefix("+888").trimStart()
            }

            return digits
        }

        val formatter = phoneUtil.getAsYouTypeFormatter(iso)
        var result = ""
        for (char in digits) {
            result = formatter.inputDigit(char)
        }
        return result
    }

    /**
     * Get example phone number for a country with digits masked as zeros
     *
     * @param iso country ISO code
     * @return formatted example number with body digits replaced by zeros,
     * or throws if no example number is available for the given ISO
     **/
    fun getExampleNumber(iso: String): String =
        phoneUtil.format(
            phoneUtil.getExampleNumberForType(iso, PhoneNumberUtil.PhoneNumberType.MOBILE),
            PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
        ).let { it.substringBefore(" ") + " " + it.substringAfter(" ").replace(Regex("\\d"), "0") }

    /**
     * Get country ISO code from the device's SIM card
     *
     * @param context Android context
     * @return uppercase ISO code of the SIM card's country,
     * or 'null' if no SIM is present or the country cannot be determined
     **/
    fun getSimIso(context: android.content.Context): String? {
        val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE)
                as android.telephony.TelephonyManager
        return tm.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
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