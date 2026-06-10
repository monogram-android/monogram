package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.mapper.user.toDomain

class FlavorCompatTelemtTest {

    @Test
    fun `telemt chat permissions fall back canReactToMessages to false`() {
        val permissions = TdApi.ChatPermissions(
            true, true, true, true, true, true, true, true, true, true,
            false, false, false, false, false
        )

        assertFalse(permissions.toDomainChatPermissions().canReactToMessages)
    }

    @Test
    fun `telemt bot type falls back supportsGuestQueries to false`() {
        val user = TdApi.User().apply {
            id = 1L
            firstName = "Bot"
            lastName = ""
            type = TdApi.UserTypeBot(
                false, false, false, false, false, false, false, false, "",
                false, false, false, 0
            )
        }

        assertFalse(user.toDomain().botTypeInfo?.supportsGuestQueries == true)
    }

    @Test
    fun `telemt proxy mapping falls back comment to null`() {
        val proxy = TdApi.AddedProxy(
            1,
            0,
            true,
            TdApi.Proxy("host", 443, TdApi.ProxyTypeMtproto("abcdef"))
        )

        assertNull(proxy.toDomain().comment)
    }
}
