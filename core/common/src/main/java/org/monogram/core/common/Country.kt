package org.monogram.core.common

data class Country(
    val name: String,
    val code: String,
    val iso: String,
    val flagEmoji: String,
    val mask: String? = null,
) {
    val pickerKey: String get() = "$code:$iso"

    fun mobileNumberLength(): Int {
        val maskDigits = mask
            ?.filter { it == MASK_CHAR }
            ?.takeIf { it.length >= FALLBACK_LENGTH }
        return maskDigits?.length ?: FALLBACK_LENGTH
    }

    private companion object {
        const val FALLBACK_LENGTH = 5
        const val MASK_CHAR = 'X'
    }
}

data class CustomCountryRule(val code: String, val format: Boolean, val mask: Boolean)
