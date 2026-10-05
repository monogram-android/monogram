package org.monogram.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.network.bridge.ProxyType

class ProxyClipboardTest {
    @Test
    fun telegramAndSocksLinksAreImported() {
        val telegram = proxyLinkFromClipboard(
            "https://t.me/proxy?server=example.com&port=443&secret=0123456789abcdef0123456789abcdef",
        )
        assertEquals(ProxyType.MTPROTO, telegram?.type)
        assertEquals("example.com", telegram?.host)

        val socks = proxyLinkFromClipboard("socks5://example.com:1080")
        assertEquals(ProxyType.SOCKS5, socks?.type)
        assertEquals(1080, socks?.port)
    }

    @Test
    fun ordinaryWebLinksAreIgnored() {
        assertNull(proxyLinkFromClipboard("https://example.com"))
        assertNull(proxyLinkFromClipboard("http://user:pass@example.com:8080"))
        assertNull(proxyLinkFromClipboard(""))
    }

    @Test
    fun severalLinksAreSplitOnCommonSeparators() {
        val mtproto = "https://t.me/proxy?server=one.example&port=443&secret=0123456789abcdef0123456789abcdef"
        val socks = "socks5://two.example:1080"
        val mtprotoUri = "mtproto://three.example:443?secret=0123456789abcdef0123456789abcdef"
        val hosts = proxyLinksFromClipboard(
            "$mtproto; $socks\n$mtprotoUri | $mtproto，https://example.com",
        ).map { it.host }

        assertEquals(listOf("one.example", "two.example", "three.example"), hosts)
        assertTrue(proxyLinksFromClipboard("not a proxy").isEmpty())
    }
}
