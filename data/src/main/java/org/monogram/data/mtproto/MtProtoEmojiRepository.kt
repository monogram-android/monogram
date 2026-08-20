package org.monogram.data.mtproto

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.infra.EmojiLoader
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.EmojiRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.AvailableReaction_bcdc20ef08
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AvailableReactions_a572c1b4d2
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAvailableReactions

internal class MtProtoEmojiRepository(
    context: Context?,
    private val localDataSource: StickerLocalDataSource,
    private val transportFactory: MtProtoSessionTransportFactory? = null,
    private val accountSlot: String = "default",
    private val fallbackEmojis: () -> List<String> = { EmojiLoader.getSupportedEmojis(requireNotNull(context)) },
) : EmojiRepository {
    private val supportedEmojis = lazy(LazyThreadSafetyMode.NONE, fallbackEmojis)

    override val recentEmojis: Flow<List<RecentEmojiModel>> = localDataSource.getRecentEmojis()

    private var remoteEmojis: List<String>? = null

    override suspend fun getDefaultEmojis(): List<String> = emojis()

    override suspend fun searchEmojis(query: String): List<String> {
        val emojis = emojis()
        val normalized = query.trim()
        if (normalized.isEmpty()) return emojis
        return emojis.filter { it.contains(normalized, ignoreCase = true) }
    }

    override suspend fun searchCustomEmojis(query: String): List<StickerModel> = unsupported()

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        localDataSource.addRecentEmoji(recentEmoji)
    }

    override suspend fun clearRecentEmojis() {
        localDataSource.clearRecentEmojis()
    }

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String> = unsupported()

    private suspend fun emojis(): List<String> {
        remoteEmojis?.let { return it }
        val fetched = transportFactory?.open(accountSlot)?.use { transport ->
            when (val response = transport.execute(GetAvailableReactions(0))) {
                is AvailableReactions_a572c1b4d2 -> response.reactions
                    .filterIsInstance<AvailableReaction_bcdc20ef08>()
                    .filterNot(AvailableReaction_bcdc20ef08::inactive)
                    .map(AvailableReaction_bcdc20ef08::reaction)
                    .distinct()
                else -> emptyList()
            }
        }.orEmpty()
        return fetched.takeIf { it.isNotEmpty() }?.also { remoteEmojis = it } ?: supportedEmojis.value
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto custom emoji and message reaction lookup are not available"
    )
}
