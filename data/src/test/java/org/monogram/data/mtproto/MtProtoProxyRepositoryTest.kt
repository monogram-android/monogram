package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.models.ProxyType

class MtProtoProxyRepositoryTest {
    @Test
    fun addProxyEnablesOnlyRequestedProxyAndPersistsDnsPreferences() = runTest {
        val repository = MtProtoProxyRepository(FakeKeyValueStore())

        val first = repository.addProxy(
            ProxyInput("first.example", 1080, type = ProxyType.Socks5("user", "password")),
            enable = true,
        )
        val second = repository.addProxy(
            ProxyInput("second.example", 443, type = ProxyType.Mtproto("abcdef")),
            enable = true,
        )
        repository.setDnsType("custom")
        repository.setCustomDnsUrl("https://dns.example/dns-query")
        repository.setCustomDnsHeaders("Authorization: Bearer token")

        val proxies = repository.getProxies()
        assertEquals(2, proxies.size)
        assertFalse(proxies.single { it.id == first.id }.isEnabled)
        assertTrue(proxies.single { it.id == second.id }.isEnabled)
        assertEquals("custom", repository.getDnsType())
        assertEquals("https://dns.example/dns-query", repository.getCustomDnsUrl())
        assertEquals("Authorization: Bearer token", repository.getCustomDnsHeaders())
    }

    @Test
    fun disableAndRemoveProxyReturnWhetherStateChanged() = runTest {
        val repository = MtProtoProxyRepository(FakeKeyValueStore())
        val proxy = repository.addProxy(
            ProxyInput("proxy.example", 8080, type = ProxyType.Http("", "", httpOnly = false)),
            enable = true,
        )

        assertTrue(repository.disableProxy())
        assertFalse(repository.disableProxy())
        assertTrue(repository.removeProxy(proxy.id))
        assertFalse(repository.removeProxy(proxy.id))
        assertTrue(repository.getProxies().isEmpty())
    }
}
