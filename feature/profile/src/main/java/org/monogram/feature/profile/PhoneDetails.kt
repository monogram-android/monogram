package org.monogram.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.monogram.core.common.PhoneOriginLookup

data class PhoneDetail(val country: String?, val operator: String?)

fun phoneDetail(phone: String): PhoneDetail {
    val digits = phone.filter { it.isDigit() }
    if (digits.isEmpty()) return PhoneDetail(country = null, operator = null)
    val origin = PhoneOriginLookup.resolve(digits)
    val country = origin.country
    val name = country?.name
    val label = when {
        name == null -> null
        country.flagEmoji.isBlank() -> name
        else -> "${country.flagEmoji} $name"
    }
    return PhoneDetail(country = label, operator = origin.operator)
}

@Composable
fun profilePhoneDetailLabel(phone: String): String? {
    val detected = remember(phone) { phoneDetail(phone) }
    return when {
        detected.country != null && detected.operator != null ->
            stringResource(R.string.profile_phone_detail, detected.country, detected.operator)
        else -> detected.country ?: detected.operator
    }
}
