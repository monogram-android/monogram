package org.monogram.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {
    @After
    fun tearDown() {
        DebugLog.resetForTests(enabled = false)
    }

    @Test
    fun capsAndKeepsNewest() {
        DebugLog.resetForTests(enabled = true)
        repeat(DebugLog.MAX_EVENTS + 5) { index ->
            DebugLog.ingestApi("rpc", "n=$index")
        }
        val rows = DebugLog.query(null, "")
        assertEquals(DebugLog.MAX_EVENTS, rows.size)
        assertTrue(rows.first().summary.contains("n=${DebugLog.MAX_EVENTS + 4}"))
        assertFalse(rows.any { it.summary.contains("n=0 ") || it.summary.endsWith("n=0") })
        assertTrue(rows.zipWithNext().all { (newer, older) -> newer.id > older.id })
    }

    @Test
    fun filtersByKindAndSearch() {
        DebugLog.resetForTests(enabled = true)
        DebugLog.ingestApi("messages.getHistory", "chat=1")
        DebugLog.ingestPerf("recomp", "ChatRow", null)
        DebugLog.ingestPerf("cache_hit", "photo", null)
        DebugLog.ingestPerf("bridge:connect", "dc=2", 12L)
        DebugLog.ingestWarn("updates", "gap")
        assertEquals(1, DebugLog.query(DebugLogKind.API, "").size)
        assertEquals(1, DebugLog.query(DebugLogKind.RECOMPOSITION, "chatrow").size)
        assertEquals(1, DebugLog.query(DebugLogKind.MEDIA, "").size)
        assertEquals(1, DebugLog.query(DebugLogKind.CONNECTION, "").size)
        assertEquals(1, DebugLog.query(DebugLogKind.ERROR, "GAP").size)
        assertTrue(DebugLog.query(DebugLogKind.API, "photo").isEmpty())
    }

    @Test
    fun redactsSecretsSessionPathsAndTokens() {
        DebugLog.resetForTests(enabled = true)
        DebugLog.ingestApi(
            "auth",
            "api_hash=deadbeef password=secret files/session.json token=123456789:abcdefghijKLMNOPQRST_uv",
        )
        val exported = DebugLog.exportText()
        assertFalse(exported.contains("deadbeef"))
        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("session.json"))
        assertFalse(exported.contains("abcdefghijKLMNOPQRST_uv"))
        assertTrue(exported.contains("[redacted]"))
        assertTrue(exported.contains("[session-path]"))
    }

    @Test
    fun collectsCategoriesOnlyWhenEnabled() {
        DebugLog.resetForTests(enabled = false)
        AppLog.api("messages.getHistory", "start")
        PerfLog.event("recomp", "Dialog")
        PerfLog.event("cache_hit", "photo")
        PerfLog.mark("connect", 4, "result=ok")
        PerfLog.mark("download:file", 9, "result=err")
        AppLog.warn("updates", "gap")
        assertTrue(DebugLog.query(null, "").isEmpty())

        DebugLog.resetForTests(enabled = true)
        AppLog.api("messages.getHistory", "start")
        PerfLog.event("recomp", "Dialog")
        PerfLog.event("cache_hit", "photo")
        PerfLog.mark("connect", 4, "result=ok")
        PerfLog.mark("download:file", 9, "result=err")
        AppLog.warn("updates", "gap")
        val rows = DebugLog.query(null, "")
        assertEquals(DebugLogKind.ERROR, rows[0].kind)
        assertEquals(DebugLogKind.ERROR, rows[1].kind)
        assertEquals(DebugLogKind.CONNECTION, rows[2].kind)
        assertEquals(DebugLogKind.MEDIA, rows[3].kind)
        assertEquals(DebugLogKind.RECOMPOSITION, rows[4].kind)
        assertEquals(DebugLogKind.API, rows[5].kind)
    }

    @Test
    fun clearDropsEvents() {
        DebugLog.resetForTests(enabled = true)
        DebugLog.ingestApi("rpc", "one")
        DebugLog.clear()
        assertTrue(DebugLog.query(null, "").isEmpty())
        assertEquals("", DebugLog.exportText())
    }
}
