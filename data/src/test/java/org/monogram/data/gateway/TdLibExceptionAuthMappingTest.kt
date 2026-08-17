package org.monogram.data.gateway

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.AuthError
import org.monogram.mtproto.transport.MtProtoRpcException

class TdLibExceptionAuthMappingTest {

    @Test
    fun `maps invalid code error`() {
        val error = TdLibException(TdApi.Error(400, "PHONE_CODE_INVALID"))

        assertEquals(AuthError.InvalidCode, error.toAuthError())
    }

    @Test
    fun `maps invalid email code error`() {
        val error = TdLibException(TdApi.Error(400, "EMAIL_CODE_INVALID"))

        assertEquals(AuthError.InvalidCode, error.toAuthError())
    }

    @Test
    fun `maps invalid password error`() {
        val error = TdLibException(TdApi.Error(400, "PASSWORD_HASH_INVALID"))

        assertEquals(AuthError.InvalidPassword, error.toAuthError())
    }

    @Test
    fun `maps expired code error`() {
        val error = TdLibException(TdApi.Error(400, "PHONE_CODE_EXPIRED"))

        assertEquals(AuthError.CodeExpired, error.toAuthError())
    }

    @Test
    fun `maps flood wait with retry timeout`() {
        val error = TdLibException(TdApi.Error(429, "FLOOD_WAIT_42"))

        assertEquals(AuthError.RateLimited(42), error.toAuthError())
    }

    @Test
    fun `maps unknown tdlib error to unexpected`() {
        val error = TdLibException(TdApi.Error(400, "SOMETHING_ELSE"))

        assertEquals(AuthError.Unexpected, error.toAuthError())
    }

    @Test
    fun `maps mtproto auth errors using the same domain categories`() {
        assertEquals(
            AuthError.InvalidCode,
            MtProtoRpcException(400, "PHONE_CODE_INVALID").toAuthError()
        )
        assertEquals(
            AuthError.InvalidPassword,
            MtProtoRpcException(400, "PASSWORD_HASH_INVALID").toAuthError()
        )
        assertEquals(
            AuthError.CodeExpired,
            MtProtoRpcException(400, "EMAIL_CODE_EXPIRED").toAuthError()
        )
        assertEquals(
            AuthError.RateLimited(42),
            MtProtoRpcException(420, "FLOOD_WAIT_42").toAuthError()
        )
    }

    @Test
    fun `maps unknown mtproto error to unexpected`() {
        assertEquals(
            AuthError.Unexpected,
            MtProtoRpcException(400, "PHONE_NUMBER_INVALID").toAuthError()
        )
    }

    @Test
    fun `detects unexpected auth state error for stale checkAuthenticationCode call`() {
        val error = TdLibException(TdApi.Error(400, "Call to checkAuthenticationCode unexpected"))

        assertTrue(error.isUnexpectedAuthStateError("checkAuthenticationCode"))
    }

    @Test
    fun `does not match unexpected auth state error for different tdlib call`() {
        val error =
            TdLibException(TdApi.Error(400, "Call to checkAuthenticationPassword unexpected"))

        assertFalse(error.isUnexpectedAuthStateError("checkAuthenticationCode"))
    }
}
