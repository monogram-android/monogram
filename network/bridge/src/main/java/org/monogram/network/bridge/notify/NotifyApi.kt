package org.monogram.network.bridge.notify

import kotlinx.coroutines.CancellationException
import org.monogram.core.common.Outcome
import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.PushTokenType
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.session.SessionCore

internal class NotifyApi(private val core: SessionCore) : NotifyOps {
    override suspend fun registerDevice(
        tokenType: PushTokenType,
        token: String,
        secret: ByteArray,
        noMuted: Boolean,
        appSandbox: Boolean,
        otherUids: List<Long>,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("account.registerDevice failed") { activeHandle ->
            core.native.registerDevice(
                activeHandle,
                tokenType.code,
                token,
                secret,
                noMuted,
                appSandbox,
                otherUids,
            )
        }
    }

    override suspend fun unregisterDevice(
        tokenType: PushTokenType,
        token: String,
        otherUids: List<Long>,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("account.unregisterDevice failed") { activeHandle ->
            core.native.unregisterDevice(activeHandle, tokenType.code, token, otherUids)
        }
    }

    override suspend fun getNotifySettings(
        peerKind: String,
        chatId: PeerId
    ): Outcome<NotifySettings> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("account.getNotifySettings failed") { activeHandle ->
            core.native.getNotifySettings(activeHandle, peerKind, chatId.value).toModel()
        }
    }

    override suspend fun updateNotifySettings(
        peerKind: String,
        settings: NotifySettings,
        chatId: PeerId,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("account.updateNotifySettings failed") { activeHandle ->
            core.native.updateNotifySettings(
                activeHandle,
                peerKind,
                chatId.value,
                settings.showPreviews,
                settings.silent,
                settings.muteUntil,
                settings.storiesMuted,
                settings.sound,
            )
        }.also { result ->
            // Chats inherit the per-type defaults, and a peer dialog owns its own mute: both are
            // re-evaluated from this one update instead of waiting for the next dialog page.
            if (result is Outcome.Ok) {
                core.localUpdates.emit(
                    MtprotoUpdate.NotifySettingsChanged(
                        peerKind = peerKind,
                        chatId = chatId,
                        muteUntil = settings.muteUntil,
                    ),
                )
            }
        }
    }

    override suspend fun resetNotifySettings(): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("account.resetNotifySettings failed") { activeHandle ->
            core.native.resetNotifySettings(activeHandle)
        }
    }

    override suspend fun setContactJoinedSilent(silent: Boolean): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("account.setContactSignUpNotification failed") { activeHandle ->
            core.native.setContactJoinedSilent(activeHandle, silent)
        }
    }

    override suspend fun getNotifyExceptions(compareSound: Boolean): Outcome<List<NotifyException>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("account.getNotifyExceptions failed") { activeHandle ->
            core.native.getNotifyExceptions(activeHandle, compareSound).map { it.toModel() }
        }
    }

    override fun decryptPushPayload(secret: ByteArray, payload: String): Outcome<String> =
        try {
            Outcome.Ok(core.native.decryptPushPayload(secret, payload))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            core.fail(e, "decrypt push failed", degradeHome = false)
        }
}
