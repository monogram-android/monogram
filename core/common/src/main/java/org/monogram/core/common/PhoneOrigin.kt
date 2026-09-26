package org.monogram.core.common

import java.util.Collections
import java.util.LinkedHashMap

data class PhoneOrigin(
    val country: Country?,
    val operator: String?,
)

private const val ORIGIN_CACHE_CAPACITY = 256

private class PhoneOriginStore : LinkedHashMap<String, PhoneOrigin>(ORIGIN_CACHE_CAPACITY, 0.75f, true) {
    protected override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhoneOrigin>?): Boolean =
        size > ORIGIN_CACHE_CAPACITY
}

object PhoneOriginLookup {
    private val cache = Collections.synchronizedMap(PhoneOriginStore())

    fun resolve(digits: String): PhoneOrigin {
        cache[digits]?.let { return it }
        val country = CountryManager.countryForPhone(digits)
        val origin = PhoneOrigin(
            country = country,
            operator = OperatorManager.operatorFor(digits, country?.iso),
        )
        cache[digits] = origin
        return origin
    }
}
