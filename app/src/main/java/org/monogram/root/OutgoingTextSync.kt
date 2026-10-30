package org.monogram.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.StoredPayloadException
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.decodeContactSend
import org.monogram.core.database.decodeLocationSend
import org.monogram.core.database.entity.OutgoingMediaSend
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.database.entity.OutgoingTextSendEntity
import org.monogram.core.models.ChecklistSendSpec
import org.monogram.core.models.Checklists
import org.monogram.core.models.Dice
import org.monogram.core.models.OutgoingMessageResult
import org.monogram.core.models.ServiceMedia
import org.monogram.network.bridge.message.MessageOps

internal suspend fun sendRetainedMessage(
    client: MessageOps, row: OutgoingTextSendEntity,
): Outcome<OutgoingMessageResult> {
    val message = row.message
    val options = org.monogram.core.models.MessageSendOptions(
        silent = row.silent, scheduleDate = row.scheduleDate, noWebpage = row.noWebpage
    )
    return if (message.mediaKind == "geo") {
        val place = prepareOutgoing {
            decodeLocationSend(message.fileName).also {
                if (!message.text.isNullOrBlank() || row.webpageUrl != null || row.noWebpage || (it.live && row.scheduleDate != null)) throw StoredPayloadException()
            }
        }
        client.sendLocationWithOptions(
            org.monogram.core.models.PeerId(message.chatId),
            place,
            row.randomId,
            options,
            replyToMsgId = message.replyToMsgId ?: 0,
            topMsgId = message.replyToTopId ?: 0
        )
    } else if (message.mediaKind == "contact") {
        val spec = prepareOutgoing {
            decodeContactSend(message.fileName).also {
                if (message.text != it.displayName || row.webpageUrl != null || row.noWebpage) throw StoredPayloadException()
            }
        }
        client.sendContactWithOptions(
            org.monogram.core.models.PeerId(message.chatId),
            spec,
            row.randomId,
            options,
            replyToMsgId = message.replyToMsgId ?: 0,
            topMsgId = message.replyToTopId ?: 0
        )
    } else if (message.mediaKind == "todo") {
        val spec = prepareOutgoing {
            ChecklistSendSpec.fromPending(
                Checklists.parse(message.checklistJson) ?: throw StoredPayloadException()
            ).also {
                if (message.text != it.title || row.webpageUrl != null || row.noWebpage) throw StoredPayloadException()
            }
        }
        client.sendChecklistWithOptions(
            org.monogram.core.models.PeerId(message.chatId),
            spec,
            row.randomId,
            options,
            replyToMsgId = message.replyToMsgId ?: 0,
            topMsgId = message.replyToTopId ?: 0
        )
    } else if (message.mediaKind == "poll") {
        val spec = prepareOutgoing {
            org.monogram.core.database.decodePollSend(message.fileName).also {
                if (message.text != it.question || row.webpageUrl != null || row.noWebpage) throw StoredPayloadException()
            }
        }
        client.sendPollWithOptions(
            org.monogram.core.models.PeerId(message.chatId),
            spec,
            row.randomId,
            options,
            replyToMsgId = message.replyToMsgId ?: 0,
            topMsgId = message.replyToTopId ?: 0
        )
    } else if (message.mediaKind == "dice") {
        val dice = prepareOutgoing {
            (ServiceMedia.dice(message.fileName) ?: throw StoredPayloadException()).also {
                if (it.value != 0 || it.emoticon !in Dice.supportedEmoticons) throw StoredPayloadException()
            }
        }
        client.sendDiceWithOptions(
            org.monogram.core.models.PeerId(message.chatId),
            dice.emoticon,
            row.randomId,
            options,
            replyToMsgId = message.replyToMsgId ?: 0,
            topMsgId = message.replyToTopId ?: 0
        )
    } else client.sendTextWithOptions(
        org.monogram.core.models.PeerId(message.chatId),
        message.text.orEmpty(),
        options,
        row.randomId,
        replyToMsgId = message.replyToMsgId ?: 0,
        entitiesJson = message.entitiesJson,
        topMsgId = message.replyToTopId ?: 0,
        webpageUrl = row.webpageUrl
    )
}

internal class OutgoingTextSync(
    private val cache: OfflineWarmup,
    private val send: suspend (OutgoingTextSendEntity) -> Outcome<OutgoingMessageResult>,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sendMedia: suspend (OutgoingMediaSend) -> Outcome<Unit> = { Outcome.Err("unsupported") },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var paused = false
    private var blockedUntil = 0L

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        paused = false
        job = scope.launch {
            while (isActive) {
                try {
                    cache.recoverInterruptedTextSends()
                    cache.recoverInterruptedMediaSends()
                    cache.recoverOutgoingMediaFiles(cache.outgoingAccountEpoch())
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    cache.outgoingSendSync.fail(TelegramError.parse("INTERNAL"))
                    delay(60_000)
                }
            }
            coroutineScope {
                launch { cache.outgoingSendSync.retries.collect { requestResume() } }
                launch {
                    combine(
                        cache.observeOutgoingTextSends(), cache.observeOutgoingMediaSends()
                    ) { text, media ->
                        text.map { it.randomId }.toSet() + media.map { it.batch.batchId }
                    }.retryWhen { error, _ ->
                        if (error is CancellationException) throw error
                        cache.outgoingSendSync.fail(TelegramError.parse("INTERNAL"))
                        delay(60_000)
                        true
                    }.collect { ids ->
                        cache.outgoingSendSync.retain(ids)
                        wake.trySend(Unit)
                    }
                }
                requestResume()
                while (isActive) {
                    wake.receive()
                    if (paused) continue
                    try {
                        val wait = syncOnce()
                        if (wait > 0) {
                            delay(wait); wake.trySend(Unit)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        paused = true
                        cache.outgoingSendSync.fail(TelegramError.parse("INTERNAL"))
                    }
                }
            }
        }
    }

    fun requestResume() {
        paused = false; wake.trySend(Unit)
    }

    suspend fun stop() {
        val running = job
        job = null
        running?.cancelAndJoin()
        cache.outgoingSendSync.reset()
    }

    internal suspend fun syncOnce(): Long {
        val persistedWait = (cache.outgoingSendBlockedUntil() - wallMillis()).coerceAtLeast(0)
        val wait = maxOf(persistedWait, (blockedUntil - elapsedMillis()).coerceAtLeast(0))
        if (wait > 0) return wait
        if (paused) return 0
        cache.outgoingSendSync.fail(null)
        for (row in pendingSends()) {
            currentCoroutineContext().ensureActive()
            if (row.phase != OutgoingSendPhase.READY || !row.claim()) continue
            cache.outgoingSendSync.begin(row.id)
            var error: TelegramError? = null
            try {
                when (val result = row.send()) {
                    is Outcome.Ok -> Unit // Native durable receipts settle both kinds of outbox row.
                    is Outcome.Err -> {
                        if (!row.exists()) continue
                        error = result.telegramError
                        row.markUncertain()
                        val retry =
                            (error.retryAfterSeconds?.toLong() ?: 0).coerceAtLeast(0) * 1_000L
                        if (retry > 0) {
                            blockedUntil = maxOf(blockedUntil, elapsedMillis() + retry)
                            cache.blockOutgoingSendsUntil(wallMillis() + retry)
                            cache.outgoingSendSync.fail(error)
                            return retry
                        }
                        if (error.kind in setOf(
                                TelegramError.Kind.Network,
                                TelegramError.Kind.Auth,
                                TelegramError.Kind.Session,
                                TelegramError.Kind.Internal
                            ) || error.httpCode in 500..599
                        ) {
                            paused = true
                            cache.outgoingSendSync.fail(error)
                            return 0
                        }
                    }
                }
            } catch (_: OutgoingPreparationException) {
                row.markFailed()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { row.markUncertain() }
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                row.markUncertain()
                error = TelegramError.parse("INTERNAL")
                paused = true
                cache.outgoingSendSync.fail(error)
                return 0
            } finally {
                cache.outgoingSendSync.finish(row.id, error)
            }
        }
        return 0
    }

    private data class PendingSend(
        val id: Long, val phase: OutgoingSendPhase, val date: Long,
        val claim: suspend () -> Boolean, val markUncertain: suspend () -> Unit,
        val markFailed: suspend () -> Unit,
        val exists: suspend () -> Boolean, val send: suspend () -> Outcome<Unit>,
    )

    private suspend fun pendingSends(): List<PendingSend> {
        val text = cache.outgoingTextSends().map { row ->
            PendingSend(
                row.randomId,
                row.phase,
                row.message.date,
                claim = { cache.claimOutgoingTextSend(row.randomId) },
                markUncertain = { cache.markOutgoingTextSendUncertain(row.randomId) },
                markFailed = { cache.markOutgoingTextSendFailed(row.randomId) },
                exists = { cache.outgoingTextSends().any { it.randomId == row.randomId } },
                send = {
                    when (val result = send(row)) {
                        is Outcome.Ok -> Outcome.Ok(Unit)
                        is Outcome.Err -> result
                    }
                })
        }
        val media = cache.outgoingMediaSends().map { row ->
            PendingSend(
                row.batch.batchId,
                row.batch.phase,
                row.items.minOfOrNull { it.message.date } ?: row.batch.createdAt,
                claim = { cache.claimOutgoingMediaSend(row.batch.batchId) },
                markUncertain = { cache.markOutgoingMediaSendUncertain(row.batch.batchId) },
                markFailed = { cache.markOutgoingMediaSendFailed(row.batch.batchId) },
                exists = {
                    cache.outgoingMediaSends().any { it.batch.batchId == row.batch.batchId }
                },
                send = { sendMedia(row) })
        }
        return (text + media).sortedWith(compareBy({ it.date }, { it.id }))
    }
}
