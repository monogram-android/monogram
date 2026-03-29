package org.monogram.data.infra

import org.monogram.data.core.coRunCatching
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.db.dao.SponsorDao
import org.monogram.data.db.model.SponsorEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.updateSponsorIds
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SponsorSync"
private const val SPONSOR_CHANNEL_ID = -1003640797855L
private const val SPONSOR_CHANNEL_USERNAME = "ahhfjfbdnejjfbfjdjdj"
private const val HISTORY_LIMIT = 100
private const val HISTORY_BATCHES_LIMIT = 20
private const val SINGLE_RESULT_RETRY_DELAY_MS = 1500L
private const val AUTH_CHECK_INTERVAL_MS = 5L * 60L * 1000L
private const val POST_LOGIN_SYNC_DELAY_MS = 60L * 1000L
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

class SponsorSyncManager(
    private val scopeProvider: ScopeProvider,
    private val gateway: TelegramGateway,
    private val sponsorDao: SponsorDao,
    private val authRepository: AuthRepository
) {
    private val started = AtomicBoolean(false)
    private val syncInProgress = AtomicBoolean(false)

    init {
        start()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scopeProvider.appScope.launch(Dispatchers.IO) {
            loadFromDatabase()

            var wasAuthorized = authRepository.authState.value is AuthStep.Ready
            if (wasAuthorized) {
                syncOnce(force = true)
            }

            while (true) {
                val isAuthorized = authRepository.authState.value is AuthStep.Ready
                if (!isAuthorized) {
                    wasAuthorized = false
                    delay(AUTH_CHECK_INTERVAL_MS)
                    continue
                }

                if (!wasAuthorized) {
                    wasAuthorized = true
                    Log.d(TAG, "User authorized, delaying sponsor sync for app init")
                    delay(POST_LOGIN_SYNC_DELAY_MS)
                    syncOnce(force = true)
                    continue
                }

                if (sponsorDao.getAllIds().isEmpty()) {
                    Log.d(TAG, "No sponsors in DB, syncing immediately")
                    syncOnce(force = true)
                    continue
                }

                delay(ONE_DAY_MS)
                syncOnce(force = false)
            }
        }
    }

    fun forceSync() {
        scopeProvider.appScope.launch(Dispatchers.IO) {
            syncOnce(force = true)
        }
    }

    private suspend fun loadFromDatabase() {
        val cachedIds = sponsorDao.getAllIds().toSet()
        updateSponsorIds(cachedIds)
        Log.d(TAG, "Loaded ${cachedIds.size} sponsor ids from DB")
    }

    private suspend fun syncOnce(force: Boolean) {
        if (!syncInProgress.compareAndSet(false, true)) return

        try {
            if (authRepository.authState.value !is AuthStep.Ready) {
                Log.d(TAG, "Skipping sponsor sync: user is not authorized")
                return
            }

            val latestUpdatedAt = sponsorDao.getLatestUpdatedAt() ?: 0L
            val age = System.currentTimeMillis() - latestUpdatedAt
            if (!force && latestUpdatedAt > 0L && age < ONE_DAY_MS) {
                Log.d(TAG, "Skipping sync: last sync ${age}ms ago")
                return
            }

            Log.d(TAG, "Sponsor sync started (force=$force)")
            val sponsorChatId = resolveSponsorChatId()
            var historyMessages = loadSponsorHistoryMessages(sponsorChatId) ?: run {
                Log.e(TAG, "Sponsor sync failed: unable to load sponsor history")
                return
            }

            var parsedIds = parseSponsorIds(historyMessages)
            if (parsedIds.size == 1) {
                Log.w(TAG, "Only one sponsor id parsed, retrying history load")
                delay(SINGLE_RESULT_RETRY_DELAY_MS)
                val retryMessages = loadSponsorHistoryMessages(sponsorChatId)
                if (retryMessages != null) {
                    val retryParsedIds = parseSponsorIds(retryMessages)
                    if (retryParsedIds.size > parsedIds.size) {
                        historyMessages = retryMessages
                        parsedIds = retryParsedIds
                        Log.d(TAG, "Retry loaded more sponsor ids: ${parsedIds.size}")
                    } else {
                        Log.w(TAG, "Retry didn't improve sponsor ids: ${retryParsedIds.size}")
                    }
                }
            }

            val oldIds = sponsorDao.getAllIds().toSet()

            val now = System.currentTimeMillis()
            val actualIds = when {
                parsedIds.isEmpty() && oldIds.isNotEmpty() -> {
                    Log.w(TAG, "Parsed empty sponsor list, keeping existing ${oldIds.size} ids")
                    oldIds
                }
                parsedIds.isEmpty() -> {
                    sponsorDao.clearAll()
                    emptySet()
                }
                else -> {
                    sponsorDao.insertAll(parsedIds.map { userId ->
                        SponsorEntity(
                            userId = userId,
                            sourceChannelId = sponsorChatId,
                            updatedAt = now
                        )
                    })
                    sponsorDao.deleteNotIn(parsedIds.toList())
                    parsedIds
                }
            }

            updateSponsorIds(actualIds)

            val added = actualIds - oldIds
            val removed = oldIds - actualIds
            Log.d(
                TAG,
                "Sponsor sync finished: messages=${historyMessages.size}, ids=${actualIds.size}, added=${added.size}, removed=${removed.size}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Sponsor sync failed", t)
        } finally {
            syncInProgress.set(false)
        }
    }

    private suspend fun resolveSponsorChatId(): Long {
        val chat = coRunCatching {
            gateway.execute(TdApi.SearchPublicChat(SPONSOR_CHANNEL_USERNAME)) as? TdApi.Chat
        }.getOrNull()

        return if (chat?.id != null) {
            chat.id
        } else {
            Log.w(TAG, "Unable to resolve @$SPONSOR_CHANNEL_USERNAME, using fallback chatId=$SPONSOR_CHANNEL_ID")
            SPONSOR_CHANNEL_ID
        }
    }

    private suspend fun loadSponsorHistoryMessages(chatId: Long): List<TdApi.Message>? {
        val result = mutableListOf<TdApi.Message>()
        val seenIds = HashSet<Long>()
        var fromMessageId = 0L

        repeat(HISTORY_BATCHES_LIMIT) {
            val batch = gateway.execute(
                TdApi.GetChatHistory(chatId, fromMessageId, 0, HISTORY_LIMIT, false)
            ) as? TdApi.Messages ?: return null

            if (batch.messages.isEmpty()) {
                return result
            }

            var oldestInBatch = Long.MAX_VALUE
            batch.messages.forEach { message ->
                if (seenIds.add(message.id)) {
                    result.add(message)
                }
                if (message.id in 1 until oldestInBatch) {
                    oldestInBatch = message.id
                }
            }

            if (batch.messages.size < HISTORY_LIMIT || oldestInBatch == Long.MAX_VALUE || oldestInBatch == fromMessageId) {
                return result
            }

            fromMessageId = oldestInBatch
        }

        return result
    }

    private fun parseSponsorIds(messages: List<TdApi.Message>): Set<Long> {
        val idRegex = Regex("\\b\\d{5,20}\\b")
        return messages.asSequence()
            .mapNotNull { message -> extractText(message.content) }
            .flatMap { text -> idRegex.findAll(text).map { it.value } }
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    private fun extractText(content: TdApi.MessageContent): String? {
        return when (content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text
            is TdApi.MessageVideo -> content.caption.text
            is TdApi.MessageDocument -> content.caption.text
            is TdApi.MessageAudio -> content.caption.text
            is TdApi.MessageAnimation -> content.caption.text
            is TdApi.MessageVoiceNote -> content.caption.text
            else -> null
        }
    }
}
