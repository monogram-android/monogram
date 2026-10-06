package org.monogram.network.http

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectedFileCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ownersProtectIndependentlyAndEvictedRecordsDoNotSurviveRestart() {
        val root = tmp.newFolder()
        val cache = FileCache(root, maxBytes = 6)
        cache.put("avatar:1", byteArrayOf(1, 2, 3))
        cache.get("avatar:1")!!.setLastModified(1)
        cache.setProtectedKeys("list", listOf("avatar:1"))
        cache.setProtectedKeys("dialog", listOf("avatar:1"))
        cache.setProtectedKeys("dialog", emptyList())
        cache.put("doc:2", byteArrayOf(4, 5, 6, 7))
        assertNotNull(cache.get("avatar:1"))
        assertNull(cache.get("doc:2"))
        val restored = FileCache(root, maxBytes = 6)
        assertNotNull(restored.get("avatar:1"))
        assertNull(restored.get("doc:2"))
        org.junit.Assert.assertEquals(listOf("avatar:1"), restored.records().map { it.key })
    }
}
