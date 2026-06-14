package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.AuthStep

class AuthMapperTest {

    @Test
    fun `closed authorization states map to closing`() {
        val states = listOf(
            TdApi.AuthorizationStateLoggingOut(),
            TdApi.AuthorizationStateClosing(),
            TdApi.AuthorizationStateClosed()
        )

        states.forEach { state ->
            assertTrue(state.toDomain() is AuthStep.Closing)
        }
    }
}
