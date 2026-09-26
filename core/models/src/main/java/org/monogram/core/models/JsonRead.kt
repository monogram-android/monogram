package org.monogram.core.models

/**
 * Typed reads over the map/list tree produced by [CompactJson].
 *
 * Each accessor returns `null` for an absent key, JSON `null`, or a value of the wrong JSON
 * type, which is the shape the payload readers need. The `json` prefix keeps these clear of
 * the private same-package `Map<*, *>` readers in sibling files, which stay in scope across
 * the package even though they are not callable.
 */

fun Map<*, *>.jsonMap(name: String): Map<*, *>? = this[name] as? Map<*, *>

fun Map<*, *>.jsonList(name: String): List<*>? = this[name] as? List<*>

fun Map<*, *>.jsonString(name: String): String? = this[name] as? String

/** Numeric-only: a quoted number such as `"12"` is not accepted. */
fun Map<*, *>.jsonLong(name: String): Long? = (this[name] as? Number)?.toLong()

/** As [jsonLong], but `null` when the value does not fit in an [Int]. */
fun Map<*, *>.jsonInt(name: String): Int? {
    val value = jsonLong(name) ?: return null
    return if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
}

/**
 * Numeric, or a string that parses as one. Telegram sends counters both ways
 * (`{"msg_id":9}` and `{"msg_id":"9"}`), so callers that accept either use this.
 *
 * `Number.toLong()` truncates (`12.5` -> `12`); callers that need an exact integer should
 * validate the result themselves.
 */
fun Map<*, *>.jsonLenientLong(name: String): Long? = when (val value = this[name]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

fun Map<*, *>.jsonBool(name: String): Boolean? = this[name] as? Boolean

fun Map<*, *>.jsonStringList(name: String): List<String> =
    jsonList(name)?.filterIsInstance<String>().orEmpty()
