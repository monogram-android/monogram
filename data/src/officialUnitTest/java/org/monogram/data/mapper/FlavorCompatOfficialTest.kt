package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.mapper.user.toDomain

class FlavorCompatOfficialTest {

    @Test
    fun `official chat permissions preserve canReactToMessages`() {
        val permissions = TdApi.ChatPermissions(
            true, true, true, true, true, true, true, true, true, true,
            true, false, false, false, false, false
        )

        assertTrue(permissions.toDomainChatPermissions().canReactToMessages)
    }

    @Test
    fun `official bot type preserves supportsGuestQueries`() {
        val user = TdApi.User().apply {
            id = 1L
            firstName = "Bot"
            lastName = ""
            type = TdApi.UserTypeBot(
                false, false, false, false, false, false, false, false, "",
                true, false, false, false, 0
            )
        }

        assertTrue(user.toDomain().botTypeInfo?.supportsGuestQueries == true)
    }

    @Test
    fun `official proxy mapping preserves comment`() {
        val proxy = TdApi.AddedProxy(
            1,
            0,
            true,
            "office",
            TdApi.Proxy("host", 443, TdApi.ProxyTypeMtproto("abcdef"))
        )

        assertEquals("office", proxy.toDomain().comment)
    }
}
