package org.monogram.mtproto.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportLoginToken
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportLoginToken
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginTokenMigrateTo
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginTokenSuccess
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginToken_1f26fafac9
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoQrLoginClientTest {
    private fun authorization() = org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3(false, null, null, null, UserEmpty(1L))

    @Test
    fun `exports waiting tokens with api credentials`() = runBlocking {
        val requests = mutableListOf<TlMethod<*>>()
        val client = MtProtoQrLoginClient(
            executor = { method ->
                requests += method
                LoginToken_1f26fafac9(expires = 1234, token = TlBytes.copyOf(byteArrayOf(1, 2)))
            },
            apiId = 42,
            apiHash = "secret",
            exceptAuthorizationIds = listOf(7L),
        )

        val state = client.export()

        state as MtProtoQrLoginState.Waiting
        assertEquals(listOf(1, 2), state.token.map { it.toInt() })
        assertEquals(1234, state.expiresAtSeconds)
        val request = requests.single() as ExportLoginToken
        assertEquals(42, request.apiId)
        assertEquals("secret", request.apiHash)
        assertEquals(listOf(7L), request.exceptIds)
    }

    @Test
    fun `surfaces migration and continues through import`() = runBlocking {
        val responses = ArrayDeque<TlObject>().apply {
            add(LoginTokenMigrateTo(dcId = 4, token = TlBytes.copyOf(byteArrayOf(5))))
            add(LoginToken_1f26fafac9(expires = 99, token = TlBytes.copyOf(byteArrayOf(6))))
        }
        val requests = mutableListOf<TlMethod<*>>()
        val client = MtProtoQrLoginClient(
            executor = { method ->
                requests += method
                responses.removeFirst()
            },
            apiId = 1,
            apiHash = "h",
        )

        val migration = client.export()
        assertTrue(migration is MtProtoQrLoginState.MigrationNeeded)
        assertEquals(4, (migration as MtProtoQrLoginState.MigrationNeeded).dcId)

        val waiting = client.import(migration.token)
        waiting as MtProtoQrLoginState.Waiting
        // The redirected DC issued a fresh live token.
        assertEquals(listOf(6), waiting.token.map { it.toInt() })

        val import = requests[1] as ImportLoginToken
        assertEquals(listOf(5), import.token.toByteArray().map { it.toInt() })
    }

    @Test
    fun `surfaces authorization when the other device approves`() = runBlocking {
        val client = MtProtoQrLoginClient(
            executor = { LoginTokenSuccess(authorization()) },
            apiId = 1,
            apiHash = "h",
        )

        val state = client.export()

        state as MtProtoQrLoginState.Authorized
        assertEquals(authorization(), state.authorization)
    }

    @Test
    fun `fails closed on unsupported variants`() {
        val client = MtProtoQrLoginClient(
            executor = { object : TlObject { override val constructorId: UInt = 0u } },
            apiId = 1,
            apiHash = "h",
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { client.export() } }
    }
}
