package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStoriesNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStories_75ae93d8cd
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.GetAllStories

/** Refreshes canonical story projections without exposing an incomplete domain mapping. */
internal fun interface MtProtoStoryRefreshRepository {
    suspend fun refreshInitialLists()
}

internal object NoOpMtProtoStoryRefreshRepository : MtProtoStoryRefreshRepository {
    override suspend fun refreshInitialLists() = Unit
}

internal class MtProtoStoryRefreshRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val stories: MtProtoStoryProjectionStore,
    private val resultStager: MtProtoStoryResultStager,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryRefreshRepository {
    override suspend fun refreshInitialLists() {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        transportFactory.open(accountSlot).use { transport ->
            refreshInitialList(transport, scope, LIST_MAIN, hidden = false)
            refreshInitialList(transport, scope, LIST_ARCHIVE, hidden = true)
        }
    }

    private suspend fun refreshInitialList(
        transport: org.monogram.mtproto.transport.MtProtoRpcTransport,
        scope: MtProtoAuthKeyScope,
        listType: String,
        hidden: Boolean,
    ) {
        val previous = stories.cursor(scope, listType)
        var result = transport.execute(GetAllStories(next = false, hidden = hidden, state = previous?.state))
        when (result) {
            is AllStories_75ae93d8cd -> resultStager.stageAllStories(scope, listType, result)
            is AllStoriesNotModified -> {
                val cursor = requireNotNull(previous) {
                    "MTProto story list was not modified before it was initialized: $listType"
                }
                stories.replaceActiveList(scope, listType, stories.activeList(scope, listType), cursor.copy(state = result.state))
                return
            }
        }
        while (result is AllStories_75ae93d8cd && result.hasMore) {
            result = transport.execute(GetAllStories(next = true, hidden = hidden, state = result.state))
            when (result) {
                is AllStories_75ae93d8cd -> resultStager.stageAllStories(scope, listType, result, append = true)
                is AllStoriesNotModified -> return
            }
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val LIST_MAIN = "MAIN"
        const val LIST_ARCHIVE = "ARCHIVE"
    }
}
