package org.monogram.core.common

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil

object CountryManager {
    private const val COUNTRIES_RESOURCE = "/countries.txt"
    private const val FALLBACK_ISO = "US"
    private const val SETTLED_DIGITS = 4
    private const val SHARED_CODES_MARKER = '7'

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    private val customRules = listOf(
        CustomCountryRule("42", format = false, mask = false),
        CustomCountryRule("888", format = true, mask = true),
        CustomCountryRule("881", format = false, mask = true),
        CustomCountryRule("882", format = false, mask = true),
        CustomCountryRule("883", format = false, mask = true),
    )

    private val fallbackIso: String by lazy {
        if (countryForIso(FALLBACK_ISO) != null) {
            FALLBACK_ISO
        } else {
            countries().firstOrNull()?.iso ?: FALLBACK_ISO
        }
    }

    fun countries(): List<Country> = countryTable

    private val countryTable: List<Country> by lazy { loadCountries() }

    fun countryForIso(iso: String?): Country? =
        iso?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { target -> countryTable.firstOrNull { it.iso.equals(target, ignoreCase = true) } }

    fun countryForPhone(phone: String): Country? {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val matches = countryTable.filter { digits.startsWith(it.code) }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches[0]

        if (digits.startsWith(SHARED_CODES_MARKER)) {
            val next = digits.getOrNull(1)
            return if (next == '7' || next == '6' || next == '0') {
                matches.find { it.iso == "KZ" } ?: matches.find { it.iso == "RU" } ?: matches[0]
            } else {
                matches.find { it.iso == "RU" } ?: matches.find { it.iso == "KZ" } ?: matches[0]
            }
        }

        if (digits.startsWith("1")) {
            matches.filter { it.code.length > 1 }
                .maxByOrNull { it.code.length }
                ?.let { return it }
            return matches.find { it.iso == "US" } ?: matches.find { it.iso == "CA" } ?: matches[0]
        }

        if (digits.startsWith("44")) return matches.find { it.iso == "GB" } ?: matches[0]
        if (digits.startsWith("33")) return matches.find { it.iso == "FR" } ?: matches[0]
        if (digits.startsWith("358")) return matches.find { it.iso == "FI" } ?: matches[0]

        return matches.maxByOrNull { it.code.length }
    }

    fun countryForTypedPhone(phone: String, fallback: Country?): Country? {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return fallback
        if (fallback != null && (digits.startsWith(fallback.code) || isNationalNumber(digits, fallback))) {
            return fallback
        }
        return countryForPhone(digits) ?: fallback
    }

    fun phoneValueForTypedDigits(digits: String, fallback: Country?): String {
        if (digits.isEmpty()) return ""
        if (fallback != null && !digits.startsWith(fallback.code) && isNationalNumber(digits, fallback)) {
            return "+${fallback.code}$digits"
        }
        return "+$digits"
    }

    private fun isNationalNumber(digits: String, country: Country): Boolean =
        isValidPhoneNumber("+${country.code}$digits", country.iso)

    fun formatPhoneNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val rule = customRules.firstOrNull { digits.startsWith(it.code) }

        if (rule != null) {
            if (!rule.format) return raw
            if (rule.code == "888") return format888(digits)
        }

        return runCatching {
            phoneUtil.format(phoneUtil.parse(raw, null), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        }.getOrDefault(raw)
    }

    fun maskPhoneNumber(formatted: String): String {
        val digits = formatted.filter { it.isDigit() }
        val rule = customRules.firstOrNull { digits.startsWith(it.code) }

        if (rule != null) {
            if (!rule.mask) return formatted

            var index = 0
            val masked = buildString {
                formatted.forEach { char ->
                    if (char.isDigit()) {
                        val visible = index < rule.code.length || index >= digits.length - SETTLED_DIGITS
                        append(if (visible) char else '*')
                        index++
                    } else {
                        append(char)
                    }
                }
            }
            return if (masked.startsWith("+")) masked else "+$masked"
        }

        if (digits.length < SETTLED_DIGITS + 1) return "****"

        var index = 0
        val masked = formatted.map { char ->
            if (char.isDigit()) {
                val position = index++
                if (position == 0 || position >= digits.length - SETTLED_DIGITS) char else '*'
            } else {
                char
            }
        }.joinToString("")

        return if (masked.startsWith("+")) masked else "+$masked"
    }

    fun isValidPhoneNumber(phone: String, iso: String): Boolean {
        val digits = phone.filter { it.isDigit() }

        if (customRules.any { digits.startsWith(it.code) && digits.length - it.code.length >= 3 }) {
            return true
        }

        return runCatching {
            phoneUtil.isValidNumber(phoneUtil.parse(phone, iso))
        }.getOrDefault(false)
    }

    fun formatPartialPhoneNumber(iso: String, raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val country = countryForIso(iso) ?: return raw
        val rule = customRules.firstOrNull { it.code == country.code }

        if (rule != null) {
            if (!rule.format) return digits
            if (rule.code == "888") {
                return format888("888$digits").removePrefix("+888").trimStart()
            }
            return digits
        }

        val formatter = phoneUtil.getAsYouTypeFormatter(country.iso)
        var result = ""
        for (digit in digits) {
            result = formatter.inputDigit(digit)
        }
        return result
    }

    fun formatAsYouType(raw: String, country: Country): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        val international = formatPhoneNumber("+$digits")
        if (international.filter { it.isDigit() } == digits) return international

        if (!digits.startsWith(country.code) && isPartialCountryCode(digits)) return "+$digits"

        val local = digits.removePrefix(country.code)
        return "+${country.code} ${formatPartialPhoneNumber(country.iso, local)}".trimEnd()
    }

    private fun isPartialCountryCode(digits: String): Boolean =
        countryTable.any { it.code.length > digits.length && it.code.startsWith(digits) }

    fun exampleNumber(iso: String): String? = runCatching {
        val example = phoneUtil.getExampleNumberForType(iso, PhoneNumberUtil.PhoneNumberType.MOBILE)
        phoneUtil.format(example, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL).let { formatted ->
            formatted.substringBefore(" ") + " " +
                formatted.substringAfter(" ").replace(Regex("\\d"), "0")
        }
    }.getOrNull()

    fun simIso(context: Context): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephony?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
    }

    fun resolveIso(simIso: String?, deviceIso: String?): String {
        val candidate = simIso?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?: deviceIso?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        return if (candidate != null && countryForIso(candidate) != null) candidate else fallbackIso
    }

    private fun format888(digits: String): String {
        val rest = digits.removePrefix("888")
        val first = rest.take(4)
        val second = rest.drop(4).take(4)

        return buildString {
            append("+888")
            if (first.isNotEmpty()) append(" $first")
            if (second.isNotEmpty()) append(" $second")
        }
    }

    private fun loadCountries(): List<Country> {
        val stream = javaClass.getResourceAsStream(COUNTRIES_RESOURCE) ?: return emptyList()
        return stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(";")
                if (parts.size < 3) {
                    null
                } else {
                    val iso = parts[1]
                    Country(
                        name = when (iso) {
                            "YL" -> "Telegram"
                            "FT" -> "Fragment Number"
                            else -> parts[2]
                        },
                        code = parts[0],
                        iso = iso,
                        flagEmoji = flagFor(iso),
                        mask = parts.getOrNull(3),
                    )
                }
            }.toList()
        }
    }

    fun flagFor(iso: String): String {
        if (iso == "FT") return "⭐"
        if (iso == "YL") return "✈️"
        if (iso.length != 2) return "🌐"
        val first = Character.codePointAt(iso, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(iso, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
