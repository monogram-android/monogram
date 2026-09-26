package org.monogram.network.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.mtproto.MtprotoNative
import org.monogram.mtproto.MtprotoNativeLoader
import org.monogram.network.bridge.chat.ChatOps
import org.monogram.network.bridge.media.MediaOps
import org.monogram.network.bridge.message.MessageOps
import org.monogram.network.bridge.notify.NotifyOps
import org.monogram.network.bridge.profile.ProfileOps
import org.monogram.network.bridge.session.DcTxtBootstrap
import org.monogram.network.bridge.session.MonotonicClock
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.bridge.session.SessionOps
import org.monogram.network.bridge.updates.UpdatesOps
import org.monogram.network.bridge.web.WebOps

class BridgedMtprotoClient internal constructor(
    private val apis: ClientApis,
) : MtprotoClient,
    SessionOps by apis.core,
    ChatOps by apis.chat,
    MessageOps by apis.message,
    ProfileOps by apis.profile,
    MediaOps by apis.media,
    WebOps by apis.web,
    NotifyOps by apis.notify,
    UpdatesOps by apis.updates {
    constructor(
        credentials: TelegramCredentials,
        sessionPath: String,
        native: MtprotoNative = MtprotoNativeLoader.loadOrStub(),
        historyTimeoutMs: Long = 20_000,
        nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
        refreshDcSidecar: (String) -> Unit = DcTxtBootstrap::refreshSidecar,
    ) : this(
        ClientApis(
            SessionCore(
                credentials,
                sessionPath,
                native,
                historyTimeoutMs,
                nativeDispatcher,
                refreshDcSidecar,
            )
        ),
    )

    internal constructor(
        credentials: TelegramCredentials,
        sessionPath: String,
        native: MtprotoNative,
        nativeDispatcher: CoroutineDispatcher,
        refreshDcSidecar: (String) -> Unit,
        clock: MonotonicClock,
        historyTimeoutMs: Long = 20_000,
    ) : this(
        ClientApis(
            SessionCore(
                credentials,
                sessionPath,
                native,
                historyTimeoutMs,
                nativeDispatcher,
                refreshDcSidecar,
                clock,
            )
        ),
    )

    override suspend fun isLocallyAuthorized(): Outcome<Boolean> = apis.core.isLocallyAuthorized()

    override suspend fun isAuthorized(): Outcome<Boolean> = apis.core.isAuthorized()
}