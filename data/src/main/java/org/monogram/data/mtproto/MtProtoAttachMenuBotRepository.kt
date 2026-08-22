package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.repository.AttachMenuBotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBots_41a96079b0
import org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBotsNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBots_b638cd6bb9
import org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBot_4414eda380
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAttachMenuBots

internal class MtProtoAttachMenuBotRepository(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val scope: CoroutineScope,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : AttachMenuBotRepository {
    private val bots = MutableStateFlow<List<AttachMenuBotModel>>(emptyList())
    private var hash = 0L

    override fun getAttachMenuBots(): Flow<List<AttachMenuBotModel>> = bots.onStart {
        refresh()
    }

    private suspend fun refresh() {
        val transport = transportFactory.open(accountSlot)
        try {
            when (val result = transport.execute(GetAttachMenuBots(hash)) as AttachMenuBots_b638cd6bb9) {
                is AttachMenuBots_41a96079b0 -> {
                    hash = result.hash
                    bots.value = result.bots.mapNotNull { it.toDomain() }
                }
                AttachMenuBotsNotModified -> Unit
                else -> error("Unsupported MTProto attach-menu response")
            }
        } finally {
            transport.close()
        }
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBot_fa3139d5ce.toDomain(): AttachMenuBotModel? {
        val bot = this as? AttachMenuBot_4414eda380 ?: return null
        return AttachMenuBotModel(
            botUserId = bot.botId,
            name = bot.shortName,
            icon = null,
            requestWriteAccess = bot.requestWriteAccess,
            isAdded = !bot.inactive,
            showInSideMenu = bot.showInSideMenu,
            showInDefaultMenu = false,
            showInAttachMenu = bot.showInAttachMenu,
        )
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
