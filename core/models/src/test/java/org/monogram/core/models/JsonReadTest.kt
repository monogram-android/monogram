package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Equivalence tests for the [JsonRead] accessors.
 *
 * The oracles below are the regex readers each accessor superseded, so a case fails if an
 * accessor's observable behaviour drifts from what those readers produced.
 *
 * Two accessors exist for numbers because the sources disagree: `ProfileExtras` and
 * `TextEntities` reject quoted numbers, while `PushPayload` accepts them.
 */
class JsonReadTest {

    private fun oldString(obj: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(obj)?.groupValues?.get(1)

    /** `ProfileExtras.intField`/`longField`, `TextEntities.intField`: bare digits only. */
    private fun oldBareLong(obj: String, name: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(obj)?.groupValues?.get(1)?.toLongOrNull()

    /** `ProfileExtras.intField`, `TextEntities.intField`: bare digits, [Int]-range only. */
    private fun oldBareInt(obj: String, name: String): Int? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull()

    /** `PushPayload.jsonLong`: quoted number first, then a bare digit match. */
    private fun oldLenientLong(obj: String, name: String): Long? {
        oldString(obj, name)?.toLongOrNull()?.let { return it }
        return oldBareLong(obj, name)
    }

    private fun oldBool(obj: String, name: String): Boolean =
        Regex(""""$name"\s*:\s*true""").containsMatchIn(obj)

    /** `ProfileExtras.nullableBoolField`. */
    private fun oldNullableBool(obj: String, name: String): Boolean? {
        if (Regex(""""$name"\s*:\s*null""").containsMatchIn(obj)) return null
        val match = Regex(""""$name"\s*:\s*(true|false)""").find(obj) ?: return null
        return match.groupValues[1] == "true"
    }

    private fun root(json: String): Map<*, *> =
        CompactJson.parse(json) as? Map<*, *> ?: error("test JSON must be an object: $json")

    @Test
    fun jsonStringMatchesOldReaderOnUnescapedValues() {
        val cases = listOf(
            """{"v":"+1"}""",
            """{"v":""}""",
            """{"v":null}""",
            """{"v":7}""",
            """{"v":true}""",
            """{"other":"x"}""",
            """{"v":"https://t.me/x"}""",
        )
        for (json in cases) {
            assertEquals("jsonString mismatch for $json", oldString(json, "v"), root(json).jsonString("v"))
        }
    }

    @Test
    fun jsonStringUnescapesWhereTheOldProfileExtrasReaderReturnedRawText() {
        // Escapes are decoded, so serialize() -> parse() round-trips values containing
        // quotes or backslashes.
        assertEquals("""say \"hi\"""", oldString("""{"v":"say \"hi\""}""", "v"))
        assertEquals("""say "hi"""", root("""{"v":"say \"hi\""}""").jsonString("v"))
        assertEquals("""line\nbreak""", oldString("""{"v":"line\nbreak"}""", "v"))
        assertEquals("line\nbreak", root("""{"v":"line\nbreak"}""").jsonString("v"))
    }

    @Test
    fun jsonLongMatchesOldBareDigitReaders() {
        val cases = listOf(
            """{"v":12}""",
            """{"v":-12}""",
            """{"v":0}""",
            """{"v":12.5}""",
            """{"v":-12.5}""",
            """{"v":null}""",
            """{"v":true}""",
            """{"other":1}""",
            """{"v":"12"}""",
            """{"v": ""}""",
        )
        for (json in cases) {
            assertEquals("jsonLong mismatch for $json", oldBareLong(json, "v"), root(json).jsonLong("v"))
        }
    }

    @Test
    fun jsonLenientLongMatchesOldPushPayloadReader() {
        val cases = listOf(
            """{"v":9}""",
            """{"v":"9"}""",
            """{"v":-9}""",
            """{"v":"-9"}""",
            """{"v":"007"}""",
            """{"v":12.5}""",
            """{"v":"12.5"}""",
            """{"v":0}""",
            """{"v":"0"}""",
            """{"v":null}""",
            """{"v":true}""",
            """{"v":"true"}""",
            """{"v":"abc"}""",
            """{"v":""}""",
            """{"other":1}""",
        )
        for (json in cases) {
            assertEquals(
                "jsonLenientLong mismatch for $json",
                oldLenientLong(json, "v"),
                root(json).jsonLenientLong("v"),
            )
        }
    }

    @Test
    fun jsonIntMatchesOldIntReadersIncludingOutOfRangeValues() {
        // The old readers parsed through `toIntOrNull()`, which yields null outside the Int
        // range. A bare `Long.toInt()` would wrap instead, so these cases pin the range guard.
        val cases = listOf(
            """{"v":12}""",
            """{"v":-12}""",
            """{"v":0}""",
            """{"v":2147483647}""",
            """{"v":-2147483648}""",
            """{"v":2147483648}""",
            """{"v":3000000000}""",
            """{"v":-3000000000}""",
            """{"v":12.5}""",
            """{"v":null}""",
            """{"v":true}""",
            """{"other":1}""",
            """{"v":"12"}""",
        )
        for (json in cases) {
            assertEquals("jsonInt mismatch for $json", oldBareInt(json, "v"), root(json).jsonInt("v"))
        }
    }

    @Test
    fun jsonListReadsArraysAndRejectsOtherTypes() {
        assertEquals(listOf<Any?>(1L, "a"), root("""{"v":[1,"a"]}""").jsonList("v"))
        assertEquals(emptyList<Any?>(), root("""{"v":[]}""").jsonList("v"))
        assertEquals(null, root("""{"v":null}""").jsonList("v"))
        assertEquals(null, root("""{"v":"text"}""").jsonList("v"))
        assertEquals(null, root("""{"v":{}}""").jsonList("v"))
        assertEquals(null, root("""{"other":[]}""").jsonList("v"))
    }

    /** Quoted numeric fields must keep resolving. */
    @Test
    fun quotedNumericFieldsResolveToTheSameValueAsBefore() {
        assertEquals(9L, root("""{"v":"9"}""").jsonLenientLong("v"))
        assertEquals(9L, root("""{"v":9}""").jsonLenientLong("v"))
        // The strict reader must stay strict; ProfileExtras rejects quoted numbers.
        assertEquals(null, root("""{"v":"9"}""").jsonLong("v"))
    }

    @Test
    fun jsonBoolMatchesOldReaders() {
        val cases = listOf(
            """{"v":true}""",
            """{"v":false}""",
            """{"v":null}""",
            """{"v":"true"}""",
            """{"v":1}""",
            """{"other":true}""",
        )
        for (json in cases) {
            assertEquals("jsonBool mismatch for $json", oldBool(json, "v"), root(json).jsonBool("v") == true)
            assertEquals(
                "jsonBool tri-state mismatch for $json",
                oldNullableBool(json, "v"),
                root(json).jsonBool("v"),
            )
        }
    }

    @Test
    fun jsonMapMatchesOldObjectReaderForWellFormedNesting() {
        val json = """{"data":{"loc_key":"MESSAGE_TEXT","custom":{"chat_id":5}}}"""
        val data = root(json).jsonMap("data")!!
        assertEquals("MESSAGE_TEXT", data.jsonString("loc_key"))
        assertEquals(5L, data.jsonMap("custom")!!.jsonLong("chat_id"))
        assertEquals(null, root(json).jsonMap("absent"))
        assertEquals(null, root("""{"data":"text"}""").jsonMap("data"))
    }

    @Test
    fun jsonMapHandlesBracesInsideStringsWhereTheOldScannerDidNot() {
        // Braces inside string values must not truncate the object.
        val json = """{"custom":{"name":"a{b","chat_id":5}}"""
        assertEquals(5L, root(json).jsonMap("custom")!!.jsonLong("chat_id"))
    }

    @Test
    fun jsonStringListMatchesOldArrayReaderForFlatStringArrays() {
        assertEquals(emptyList<String>(), root("""{"v":[]}""").jsonStringList("v"))
        assertEquals(listOf("a", "b"), root("""{"v":["a","b"]}""").jsonStringList("v"))
        assertEquals(listOf("a"), root("""{"v":["a",1]}""").jsonStringList("v"))
        assertEquals(listOf("say \"hi\""), root("""{"v":["say \"hi\""]}""").jsonStringList("v"))
        assertEquals(listOf("a/b"), root("""{"v":["a\/b"]}""").jsonStringList("v"))
        assertEquals(emptyList<String>(), root("""{"v":null}""").jsonStringList("v"))
        assertEquals(emptyList<String>(), root("""{"other":[1]}""").jsonStringList("v"))
    }

    @Test
    fun jsonStringListIgnoresNestedStringsWhereTheOldArrayReaderGrabbedThem() {
        // Nested container strings must not leak into a string-array read.
        assertEquals(listOf("a"), root("""{"v":[["nested"],"a"]}""").jsonStringList("v"))
    }
}
