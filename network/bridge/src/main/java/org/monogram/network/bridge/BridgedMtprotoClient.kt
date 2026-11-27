package org.monogram.network.bridge

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import org.monogram.network.bridge.text.TextToolsOps
import org.monogram.network.bridge.updates.UpdatesOps
import org.monogram.network.bridge.web.WebOps

class BridgedMtprotoClient internal constructor(
    private val apis: ClientApis,
) : MtprotoClient, SessionOps by apis.core, ChatOps by apis.chat, MessageOps by apis.message,
    ProfileOps by apis.profile, MediaOps by apis.media, WebOps by apis.web,
    NotifyOps by apis.notify, TextToolsOps by apis.textTools, UpdatesOps by apis.updates {
    constructor(
        credentials: TelegramCredentials,
        sessionPath: String,
        native: MtprotoNative = MtprotoNativeLoader.loadOrStub(),
        historyTimeoutMs: Long = 20_000,
        nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
        refreshDcSidecar: (String) -> Unit = DcTxtBootstrap::refreshSidecar,
        initConnection: ClientInitInfo? = null,
        providerAccountId: Long? = null,
    ) : this(
        ClientApis(
            SessionCore(
                credentials,
                sessionPath,
                native,
                historyTimeoutMs,
                nativeDispatcher,
                refreshDcSidecar,
                initConnection = initConnection,
                providerAccountId = providerAccountId,
            ),
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
        initConnection: ClientInitInfo? = null,
        providerAccountId: Long? = null,
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
                initConnection,
                providerAccountId,
            ),
        ),
    )

    override fun observeConnectionStatus(context: Context): Flow<ConnectionStatus> =
        apis.core.connectionStatus.withNetworkAvailability(defaultNetworkAvailable(context.applicationContext))

    override suspend fun isLocallyAuthorized(): Outcome<Boolean> = apis.core.isLocallyAuthorized()

    override suspend fun isAuthorized(): Outcome<Boolean> = apis.core.isAuthorized()
}