package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.core.coRunCatching
import org.monogram.data.db.dao.SponsorDao
import org.monogram.data.db.model.SponsorEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.updateSponsorIds
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.SponsorState
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SponsorSync"
private const val SPONSOR_CHANNEL_ID = -1003640797855L
private const val SPONSOR_CHANNEL_USERNAME = "ahhfjfbdnejjfbfjdjdj"
private const val HISTORY_LIMIT = 100
private const val HISTORY_BATCHES_LIMIT = 20
private const val AUTH_CHECK_INTERVAL_MS = 60L * 1000L
private const val POST_LOGIN_SYNC_DELAY_MS = 15L * 1000L
private const val PERIODIC_SYNC_INTERVAL_MS = 60L * 60L * 1000L
private const val EMPTY_CACHE_RETRY_INTERVAL_MS = 10L * 60L * 1000L
private const val EVENT_DEBOUNCE_MS = 5L * 1000L
private const val RETRY_WEAK_RESULT_DELAY_MS = 1500L
private const val WEAK_RESULT_MIN_OLD_IDS = 3

class SponsorSyncManager(
    private val scope: CoroutineScope,
    private val gateway: TelegramGateway,
    private val sponsorDao: SponsorDao,
    private val authRepository: AuthRepository,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    private val started = AtomicBoolean(false)
    private val syncInProgress = AtomicBoolean(false)
    private val _sponsorState = MutableStateFlow(SponsorState())
    val sponsorState: StateFlow<SponsorState> = _sponsorState.asStateFlow()

    @Volatile
    private var sponsorChatId = SPONSOR_CHANNEL_ID
    private var eventSyncJob: Job? = null
    private var failureCount = 0

    init {
        start()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch(ioDispatcher) {
            loadFromDatabase()
            watchTelegramUpdates()

            var wasAuthorized = authRepository.authState.value is AuthStep.Ready
            if (wasAuthorized) {
                runScheduledSync(force = true, reason = "startup")
            }

            while (isActive) {
                val isAuthorized = authRepository.authState.value is AuthStep.Ready
                if (!isAuthorized) {
                    wasAuthorized = false
                    failureCount = 0
                    delay(AUTH_CHECK_INTERVAL_MS)
                    continue
                }

                if (!wasAuthorized) {
                    wasAuthorized = true
                    Log.d(TAG, "User authorized, delaying sponsor sync for app init")
                    delay(POST_LOGIN_SYNC_DELAY_MS)
                    runScheduledSync(force = true, reason = "auth_ready")
                    continue
                }

                val hasCachedSponsors = sponsorDao.getAllIds().isNotEmpty()
                delay(nextPeriodicDelayMs(hasCachedSponsors))
                runScheduledSync(force = !hasCachedSponsors, reason = "periodic")
            }
        }
    }

    fun forceSync() {
        scope.launch(ioDispatcher) {
            syncOnce(force = true, reason = "manual")
        }
    }

    private suspend fun loadFromDatabase() {
        val cachedIds = sponsorDao.getAllIds().toSet()
        updateSponsorIds(cachedIds)
        _sponsorState.value = _sponsorState.value.copy(
            supporterIds = cachedIds,
            supportersCount = cachedIds.size,
            isLoaded = cachedIds.isNotEmpty()
        )
        Log.d(TAG, "Loaded ${cachedIds.size} sponsor ids from DB")
    }

    private suspend fun runScheduledSync(force: Boolean, reason: String) {
        when (syncOnce(force = force, reason = reason)) {
            SyncOutcome.SUCCESS,
            SyncOutcome.SKIPPED -> failureCount = 0

            SyncOutcome.FAILED -> {
                failureCount++
                val delayMs = failureBackoffMs(failureCount)
                Log.w(TAG, "Sponsor sync failed ($reason), retrying in ${delayMs}ms")
                delay(delayMs)
                when (syncOnce(force = force, reason = "${reason}_retry")) {
                    SyncOutcome.SUCCESS,
                    SyncOutcome.SKIPPED -> failureCount = 0

                    SyncOutcome.FAILED -> failureCount++
                    SyncOutcome.BUSY -> Unit
                }
            }

            SyncOutcome.BUSY -> Unit
        }
    }

    private fun nextPeriodicDelayMs(hasCachedSponsors: Boolean): Long {
        return if (hasCachedSponsors) PERIODIC_SYNC_INTERVAL_MS else EMPTY_CACHE_RETRY_INTERVAL_MS
    }

    private fun failureBackoffMs(failures: Int): Long {
        val minutes = when (failures.coerceAtMost(4)) {
            1 -> 2L
            2 -> 5L
            3 -> 15L
            else -> 30L
        }
        return minutes * 60L * 1000L
    }

    private fun watchTelegramUpdates() {
        scope.launch(ioDispatcher) {
            gateway.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateConnectionState -> {
                        if (update.state is TdApi.ConnectionStateReady) {
                            requestEventSync(reason = "connection_ready", force = false)
                        }
                    }

                    is TdApi.UpdateNewMessage -> {
                        if (update.message.chatId == sponsorChatId) {
                            requestEventSync(reason = "new_message", force = true)
                        }
                    }

                    is TdApi.UpdateMessageEdited -> {
                        if (update.chatId == sponsorChatId) {
                            requestEventSync(reason = "message_edited", force = true)
                        }
                    }

                    is TdApi.UpdateMessageContent -> {
                        if (update.chatId == sponsorChatId) {
                            requestEventSync(reason = "message_content", force = true)
                        }
                    }

                    is TdApi.UpdateDeleteMessages -> {
                        if (update.chatId == sponsorChatId) {
                            requestEventSync(reason = "messages_deleted", force = true)
                        }
                    }
                }
            }
        }
    }

    private fun requestEventSync(reason: String, force: Boolean) {
        eventSyncJob?.cancel()
        eventSyncJob = scope.launch(ioDispatcher) {
            delay(EVENT_DEBOUNCE_MS)
            syncOnce(force = force, reason = reason)
        }
    }

    private suspend fun syncOnce(force: Boolean, reason: String): SyncOutcome {
        if (!syncInProgress.compareAndSet(false, true)) return SyncOutcome.BUSY

        try {
            _sponsorState.value = _sponsorState.value.copy(isSyncInProgress = true)
            if (authRepository.authState.value !is AuthStep.Ready) {
                Log.d(TAG, "Skipping sponsor sync: user is not authorized")
                return SyncOutcome.SKIPPED
            }

            val latestUpdatedAt = sponsorDao.getLatestUpdatedAt() ?: 0L
            val age = System.currentTimeMillis() - latestUpdatedAt
            if (!force && latestUpdatedAt > 0L && age < PERIODIC_SYNC_INTERVAL_MS) {
                Log.d(TAG, "Skipping sync: last sync ${age}ms ago")
                return SyncOutcome.SKIPPED
            }

            Log.d(TAG, "Sponsor sync started (reason=$reason, force=$force)")
            sponsorChatId = resolveSponsorChatId()
            var historyMessages = when (val history = loadSponsorHistoryMessages(sponsorChatId)) {
                is HistoryLoadResult.Success -> history.messages
                is HistoryLoadResult.Failure -> {
                    Log.e(TAG, "Sponsor sync failed: unable to load sponsor history", history.error)
                    return SyncOutcome.FAILED
                }
            }

            val oldIds = sponsorDao.getAllIds().toSet()
            var parsedIds = parseSponsorIds(historyMessages)
            if (isWeakResult(parsedIds, oldIds)) {
                Log.w(
                    TAG,
                    "Sponsor history result looks weak: parsed=${parsedIds.size}, cached=${oldIds.size}, retrying"
                )
                delay(RETRY_WEAK_RESULT_DELAY_MS)
                when (val retry = loadSponsorHistoryMessages(sponsorChatId)) {
                    is HistoryLoadResult.Success -> {
                        val retryParsedIds = parseSponsorIds(retry.messages)
                        if (retryParsedIds.size > parsedIds.size) {
                            historyMessages = retry.messages
                            parsedIds = retryParsedIds
                            Log.d(TAG, "Retry loaded more sponsor ids: ${parsedIds.size}")
                        } else {
                            Log.w(TAG, "Retry didn't improve sponsor ids: ${retryParsedIds.size}")
                        }
                    }

                    is HistoryLoadResult.Failure -> Log.e(TAG, "Sponsor retry failed", retry.error)
                }
            }

            if (parsedIds.isEmpty()) {
                Log.w(TAG, "Parsed empty sponsor list, keeping existing ${oldIds.size} ids")
                updateSponsorIds(oldIds)
                _sponsorState.value = _sponsorState.value.copy(
                    supporterIds = oldIds,
                    supportersCount = oldIds.size,
                    isLoaded = true,
                    lastSyncAt = System.currentTimeMillis()
                )
                return SyncOutcome.SUCCESS
            }

            val actualIds = oldIds + parsedIds
            val now = System.currentTimeMillis()
            sponsorDao.insertAll(actualIds.map { userId ->
                SponsorEntity(
                    userId = userId,
                    sourceChannelId = sponsorChatId,
                    updatedAt = now
                )
            })

            updateSponsorIds(actualIds)
            _sponsorState.value = _sponsorState.value.copy(
                supporterIds = actualIds,
                supportersCount = actualIds.size,
                isLoaded = true,
                lastSyncAt = now
            )

            val added = actualIds - oldIds
            Log.d(
                TAG,
                "Sponsor sync finished: messages=${historyMessages.size}, parsed=${parsedIds.size}, ids=${actualIds.size}, added=${added.size}"
            )
            return SyncOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Sponsor sync failed", t)
            return SyncOutcome.FAILED
        } finally {
            _sponsorState.value = _sponsorState.value.copy(isSyncInProgress = false)
            syncInProgress.set(false)
        }
    }

    private fun isWeakResult(parsedIds: Set<Long>, oldIds: Set<Long>): Boolean {
        return parsedIds.isNotEmpty() &&
                oldIds.size >= WEAK_RESULT_MIN_OLD_IDS &&
                parsedIds.size < oldIds.size / 2
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

    private suspend fun loadSponsorHistoryMessages(chatId: Long): HistoryLoadResult {
        val result = mutableListOf<TdApi.Message>()
        val seenIds = HashSet<Long>()
        var fromMessageId = 0L

        repeat(HISTORY_BATCHES_LIMIT) {
            val batch = coRunCatching {
                gateway.execute(
                    TdApi.GetChatHistory(chatId, fromMessageId, 0, HISTORY_LIMIT, false)
                ) as? TdApi.Messages
            }.getOrElse { error ->
                return HistoryLoadResult.Failure(error)
            }
                ?: return HistoryLoadResult.Failure(IllegalStateException("Unexpected GetChatHistory result"))

            if (batch.messages.isEmpty()) {
                return HistoryLoadResult.Success(result)
            }

            val oldestInBatch =
                batch.messages.minOfOrNull { it.id } ?: return HistoryLoadResult.Success(result)
            batch.messages.forEach { message ->
                if (seenIds.add(message.id)) {
                    result.add(message)
                }
            }

            if (batch.messages.size < HISTORY_LIMIT || oldestInBatch <= 0L || oldestInBatch == fromMessageId) {
                return HistoryLoadResult.Success(result)
            }

            fromMessageId = oldestInBatch
        }

        return HistoryLoadResult.Success(result)
    }

    private fun parseSponsorIds(messages: List<TdApi.Message>): Set<Long> {
        var invalidTokens = 0
        val ids = messages.asSequence()
            .mapNotNull { message -> extractText(message.content) }
            .flatMap { text -> text.splitToSequence(",") }
            .mapNotNull { token ->
                val value = token.trim()
                if (value.isEmpty()) return@mapNotNull null
                val id = value.toLongOrNull()
                if (id == null || id <= 0L) {
                    invalidTokens++
                    null
                } else {
                    id
                }
            }
            .toSet()

        if (invalidTokens > 0) {
            Log.w(TAG, "Skipped $invalidTokens invalid sponsor id tokens")
        }

        return ids
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

    private enum class SyncOutcome {
        SUCCESS,
        FAILED,
        SKIPPED,
        BUSY
    }

    private sealed class HistoryLoadResult {
        data class Success(val messages: List<TdApi.Message>) : HistoryLoadResult()
        data class Failure(val error: Throwable) : HistoryLoadResult()
    }
}