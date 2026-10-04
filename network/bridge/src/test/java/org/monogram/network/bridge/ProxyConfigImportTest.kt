package org.monogram.network.bridge

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class ProxyConfigImportTest {
    @Test
    fun parsesTelegramLinkWithoutRetainingUrl() {
        val config = parseProxyImport("https://t.me/proxy?server=example.com&port=443&secret=0123456789abcdef0123456789abcdef")
        assertNotNull(config)
        assertEquals(ProxyType.MTPROTO, config!!.type)
        assertEquals("example.com", config!!.host)
        assertEquals(16, config!!.secret.size)
    }

    @Test
    fun parsesHttpAndSocksUris() {
        assertEquals(ProxyType.HTTP, parseProxyImport("http://user:pass@example.com:8080")?.type)
        assertEquals(ProxyType.SOCKS5, parseProxyImport("socks5://example.com:1080")?.type)
    }

    @Test
    fun rejectsUnsupportedOrInvalidSecret() {
        assertNull(parseProxyImport("ftp://example.com:21"))
        assertNull(parseProxyImport("mtproto://example.com:443?secret=bad"))
    }
}


