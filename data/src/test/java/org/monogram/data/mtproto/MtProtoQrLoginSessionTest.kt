package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ExportLoginToken
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportLoginToken
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginTokenMigrateTo
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginTokenSuccess
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.LoginToken_1f26fafac9
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.auth.MtProtoQrLoginState
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoQrLoginSessionTest {
    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("k")),
        cloud = CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )

    private fun authorization(): Authorization_d8660c55a3 = Authorization_d8660c55a3(
            setupPasswordRequired = false,
            otherwiseReloginDays = null,
            tmpSessions = null,
            futureAuthToken = null,
            user = org.monogram.mtproto.tl.generated.cloud.layer223.User_1990f29d1e(
                self = false, contact = false, mutualContact = false, deleted = false,
                bot = false, botChatHistory = false, botNochats = false, verified = false,
                restricted = false, min = false, botInlineGeo = false, support = false,
                scam = false, applyMinPhoto = false, fake = false, botAttachMenu = false,
                premium = false, attachMenuEnabled = false, botCanEdit = false,
                closeFriend = false, storiesHidden = false, storiesUnavailable = false,
                contactRequirePremium = false, botBusiness = false, botHasMainApp = false,
                botForumView = false, botForumCanManageTopics = false,
                id = 1L, accessHash = null, firstName = "U", lastName = null,
                username = null, phone = null, photo = null, status = null,
                botInfoVersion = null, restrictionReason = null,
                botInlinePlaceholder = null, langCode = null, emojiStatus = null,
                usernames = null, storiesMaxId = null, color = null,
                profileColor = null, botActiveUsers = null, botVerificationIcon = null,
                sendPaidMessagesStars = null,
            ),
        )

    private fun authKey(): MtProtoAuthKey {
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        return try {
            MtProtoAuthKey.restore(material, StoredMtProtoAuthKey.calculateId(material), 73L, 1_783_001_185)
        } finally {
            material.fill(0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inner class QrTransport(
        private val respond: suspend (TlMethod<*>) -> Any,
    ) : MtProtoRpcTransport {
        val executed = mutableListOf<TlMethod<*>>()
        var closed = false

        override suspend fun <R> execute(method: TlMethod<R>): R {
            executed += method
            return respond(method) as R
        }

        override fun close() { closed = true }
    }

    @Test
    fun `polls waiting tokens on the home transport`() = runBlocking {
        val home = QrTransport { LoginToken_1f26fafac9(expires = 99, token = TlBytes.copyOf(byteArrayOf(1))) }
        val factory = buildFactory(home)

        val state = MtProtoQrLoginSession(factory, apiId = 42, apiHash = "h").poll()

        state as MtProtoQrLoginState.Waiting
        assertEquals(listOf(1), state.token.map { it.toInt() })
        assertTrue(home.executed.single() is ExportLoginToken)
        assertEquals(true, home.closed)
    }

    @Test
    fun `follows migrations by re-importing on a fresh transport`() = runBlocking {
        var openCount = 0
        val openedDcs = mutableListOf<Int>()
        val transports = mutableListOf<QrTransport>()
        val factory = TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ ->
                BootstrappedMtProtoAuthKey(authKey(), MtProtoAuthKeySource.STORED)
            },
            handshakeConnectionFactory = { FakeHandshake() },
            encryptedTransportFactory = { scope, endpoint, _, _ ->
                openCount++
                openedDcs += endpoint.dcId
                QrTransport { method ->
                    when (method) {
                        is ExportLoginToken ->
                            LoginTokenMigrateTo(dcId = 2, token = TlBytes.copyOf(byteArrayOf(5)))
                        is ImportLoginToken ->
                            LoginToken_1f26fafac9(expires = 10, token = TlBytes.copyOf(byteArrayOf(6)))
                        else -> error("unexpected ${method::class.simpleName}")
                    }
                }.also { transports += it }
            },
        )

        val state = MtProtoQrLoginSession(factory, apiId = 42, apiHash = "h").poll()

        // Migration to the home DC id re-opens a transport there and imports.
        state as MtProtoQrLoginState.Waiting
        assertEquals(listOf(6), state.token.map { it.toInt() })
        assertTrue(openedDcs.contains(2))
        val importExecuted = transports.any { t -> t.executed.any { it is ImportLoginToken } }
        assertTrue(importExecuted)
    }

    @Test
    fun `surfaces authorization on success`() = runBlocking {
        val home = QrTransport { LoginTokenSuccess(authorization()) }
        val factory = buildFactory(home)

        val state = MtProtoQrLoginSession(factory, apiId = 42, apiHash = "h").poll()

        state as MtProtoQrLoginState.Authorized
        assertEquals(authorization(), state.authorization)
    }

    private fun buildFactory(home: QrTransport): TelegramMtProtoSessionFactory =
        TelegramMtProtoSessionFactory(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            keyLoader = MtProtoAuthKeyLoader { _, _, _ ->
                BootstrappedMtProtoAuthKey(authKey(), MtProtoAuthKeySource.STORED)
            },
            handshakeConnectionFactory = { FakeHandshake() },
            encryptedTransportFactory = { _, _, _, _ -> home },
        )

    private class FakeHandshake : org.monogram.mtproto.transport.MtProtoHandshakeConnection {
        override suspend fun <R : org.monogram.mtproto.tl.runtime.TlObject> execute(method: org.monogram.mtproto.tl.runtime.TlMethod<R>): R =
            error("not used")

        override fun close() = Unit
    }
}
