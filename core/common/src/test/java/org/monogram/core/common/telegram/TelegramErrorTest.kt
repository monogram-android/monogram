package org.monogram.core.common.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramErrorTest {
    @Test
    fun catalogIngestsOfficialDatabase() {
        assertEquals(227, TelegramError.CATALOG_LAYER)
        assertEquals(TelegramErrorCatalog.SIZE, TelegramError.CATALOG_SIZE)
        assertTrue(TelegramError.CATALOG_SIZE >= 800)
        val types = TelegramErrorCatalog.rows.map { it.pattern }.toSet()
        assertTrue(types.contains("FLOOD_WAIT_%d"))
        assertTrue(types.contains("AUTH_KEY_UNREGISTERED"))
        assertTrue(types.contains("SESSION_REVOKED"))
        assertTrue(types.contains("FILE_REFERENCE_EXPIRED"))
        assertTrue(types.contains("PHONE_CODE_INVALID"))
        assertTrue(types.contains("CHAT_FORWARDS_RESTRICTED"))
        assertTrue(types.contains("SLOWMODE_MULTI_MSGS_DISABLED"))
    }

    @Test
    fun forwardRestrictionsAreMappedAndNotAutoRetried() {
        val protectedChat = TelegramError.parse("RPC 400: CHAT_FORWARDS_RESTRICTED")
        assertTrue(protectedChat.recognized)
        assertFalse(protectedChat.canAutoRetry)
        assertTrue(protectedChat.message.isNotBlank())

        val slowmode = TelegramError.parse("RPC 400: SLOWMODE_MULTI_MSGS_DISABLED")
        assertTrue(slowmode.recognized)
        assertFalse(slowmode.canAutoRetry)
        assertEquals(400, slowmode.httpCode)
    }

    @Test
    fun floodWaitExtractsSecondsAndKind() {
        val error = TelegramError.parse("RPC 420: FLOOD_WAIT_5")
        assertEquals(TelegramError.Kind.Flood, error.kind)
        assertEquals(420, error.httpCode)
        assertEquals("FLOOD_WAIT_5", error.type)
        assertEquals("FLOOD_WAIT_%d", error.pattern)
        assertEquals(5, error.argument)
        assertEquals(5, error.retryAfterSeconds)
        assertTrue(error.recognized)
        assertTrue(error.canAutoRetry)
        assertTrue(error.message.contains("5"))
    }

    @Test
    fun authAndSessionCodesMapToKinds() {
        val unregistered = TelegramError.parse("RPC 401: AUTH_KEY_UNREGISTERED")
        assertEquals(TelegramError.Kind.Session, unregistered.kind)
        assertTrue(unregistered.recognized)
        assertTrue(unregistered.requiresReauth)

        val invalid = TelegramError.parse("AUTH_KEY_INVALID")
        assertEquals(TelegramError.Kind.Session, invalid.kind)
        assertTrue(invalid.requiresReauth)

        val permEmpty = TelegramError.parse("AUTH_KEY_PERM_EMPTY")
        assertEquals(TelegramError.Kind.Session, permEmpty.kind)
        assertFalse(permEmpty.requiresReauth)

        val unsync = TelegramError.parse("AUTH_KEY_UNSYNCHRONIZED")
        assertEquals(TelegramError.Kind.Session, unsync.kind)
        assertFalse(unsync.requiresReauth)

        for (code in listOf("SESSION_REVOKED", "SESSION_EXPIRED", "USER_DEACTIVATED")) {
            val error = TelegramError.parse("RPC 401: $code")
            assertEquals(code, TelegramError.Kind.Session, error.kind)
            assertTrue(code, error.requiresReauth)
        }

        val password = TelegramError.parse("SESSION_PASSWORD_NEEDED")
        assertEquals(TelegramError.Kind.Auth, password.kind)

        val phone = TelegramError.parse("PHONE_CODE_INVALID")
        assertEquals(TelegramError.Kind.Auth, phone.kind)
        assertTrue(phone.recognized)
    }

    @Test
    fun fileReferenceAndFallback() {
        val expired = TelegramError.parse("FILE_REFERENCE_EXPIRED")
        assertEquals(TelegramError.Kind.FileReference, expired.kind)
        assertTrue(expired.recognized)

        val indexed = TelegramError.parse("FILE_REFERENCE_3_EXPIRED")
        assertEquals(TelegramError.Kind.FileReference, indexed.kind)
        assertEquals(3, indexed.argument)
        assertTrue(indexed.recognized)

        val noThumb = TelegramError.parse("no downloadable thumb")
        assertEquals(TelegramError.Kind.Media, noThumb.kind)
        assertEquals(0, noThumb.httpCode)
        assertEquals("NO_THUMB", noThumb.type)
        assertFalse(noThumb.recognized)

        val unknown = TelegramError.parse("RPC 400: TOTALLY_MADE_UP_ERROR")
        assertEquals(TelegramError.Kind.Generic, unknown.kind)
        assertFalse(unknown.recognized)
        assertEquals("TOTALLY_MADE_UP_ERROR", unknown.type)
        assertEquals("TOTALLY_MADE_UP_ERROR", unknown.message)
    }

    @Test
    fun migrateAndNetwork() {
        val migrate = TelegramError.parse("RPC 303: PHONE_MIGRATE_2")
        assertEquals(TelegramError.Kind.SeeOther, migrate.kind)
        assertEquals(2, migrate.migrateDcId)
        assertTrue(migrate.recognized)

        val timeout = TelegramError.parse("RPC timeout")
        assertEquals(TelegramError.Kind.Network, timeout.kind)
        assertNotNull(timeout.message)

        val conn = TelegramError.parse("connection error: try again (os error 11)")
        assertEquals(TelegramError.Kind.Network, conn.kind)
    }

    @Test
    fun protocolFailureIsNotPresentedAsAnHttp400RpcError() {
        val error = TelegramError.parse(
            "unknown MTProto constructor 0x276d3ec6; updates recovery required",
        )

        assertEquals(TelegramError.Kind.Network, error.kind)
        assertEquals(-503, error.httpCode)
        assertEquals("Timeout", error.type)
        assertTrue(error.recognized)
    }

    @Test
    fun updatesQueueOverflowIsNetworkRecoveryFailure() {
        val error = TelegramError.parse("updates queue overflow; recovery required")

        assertEquals(TelegramError.Kind.Network, error.kind)
        assertEquals(-503, error.httpCode)
        assertEquals("Timeout", error.type)
        assertTrue(error.recognized)
    }

    @Test
    fun unknownPeerIsPeerKind() {
        val error = TelegramError.parse("unknown peer -100123; refresh chats first")
        assertEquals(TelegramError.Kind.Peer, error.kind)
        assertTrue(error.message.contains("unknown peer"))
    }

    @Test
    fun nativeSignOutKeepsTheInvalidatingReason() {
        val duplicated = TelegramError.parse("session invalidated: AUTH_KEY_DUPLICATED")
        assertEquals(TelegramError.Kind.Session, duplicated.kind)
        assertEquals("AUTH_KEY_DUPLICATED", duplicated.type)
        assertEquals(406, duplicated.httpCode)
        assertTrue(duplicated.recognized)
        assertTrue(duplicated.requiresReauth)

        val revoked = TelegramError.sessionInvalidated("SESSION_REVOKED")
        assertEquals("SESSION_REVOKED", revoked.type)
        assertTrue(revoked.requiresReauth)

        // No usable reason: still a session loss, but never a fake server code.
        val unknown = TelegramError.sessionInvalidated()
        assertEquals(TelegramError.Kind.Session, unknown.kind)
        assertEquals("SESSION_INVALIDATED", unknown.type)
        assertFalse(unknown.recognized)
        assertTrue(unknown.requiresReauth)

        val payload = TelegramError.sessionInvalidated("bot +1 555 0100 note")
        assertEquals("SESSION_INVALIDATED", payload.type)
    }
}
