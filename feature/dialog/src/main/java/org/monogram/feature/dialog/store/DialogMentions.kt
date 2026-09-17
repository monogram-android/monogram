package org.monogram.feature.dialog.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.InlineBotResult
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canShowMessageViewers
import org.monogram.core.models.canShowOutboxReadDate
import org.monogram.core.models.geoPlace
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.playedMediaKind
import org.monogram.core.models.preferredPeerTitle
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerAtToken
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DraftMention
import org.monogram.feature.dialog.InlineBotQuery
import org.monogram.feature.dialog.MentionCandidate
import org.monogram.feature.dialog.PinnedBarMemory
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerPackMemory
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isTransientSendFailure
import org.monogram.feature.dialog.jumpNeedsFetch
import org.monogram.feature.dialog.localMediaCacheKey
import org.monogram.feature.dialog.mergeSenderTags
import org.monogram.feature.dialog.parseUpdateMessageId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlin.time.Duration.Companion.milliseconds

internal fun DialogExecutor.scheduleComposerAt(draft: String) {
    val token = ComposerAt.mentionToken(draft)
    if (token == null) {
        mentionJob?.cancel()
        inlineJob?.cancel()
        clearMentions()
        clearInline()
        return
    }
    val pendingInline = ComposerAt.inlineQuery(token)
    if (pendingInline != null && snapshot().editing == null) {
        mentionJob?.cancel()
        clearMentions()
        when (val cachedBot = cachedInlineBot(pendingInline.username)) {
            null -> {
                val cachedUser = cachedSenderByUsername(pendingInline.username)
                if (cachedUser != null && !cachedUser.isBot) {
                    inlineJob?.cancel()
                    clearInline()
                    if (!token.hasSpace) startMentionSearch(token, debounce = false)
                    return
                }
                if (snapshot().inlineQuery == pendingInline &&
                    (snapshot().inlineResults != null || snapshot().inlineLoading)
                ) {
                    return
                }
                val inflightUser = snapshot().inlineQuery?.username ?: confirmingInlineUser
                if (inlineJob?.isActive == true && inflightUser == pendingInline.username) {
                    return
                }
                confirmingInlineUser = pendingInline.username
                inlineJob?.cancel()
                inlineJob = work.launch {
                    delay(300)
                    confirmInlineBot(pendingInline)
                }
            }
            else -> startInline(pendingInline, cachedBot)
        }
        return
    }
    inlineJob?.cancel()
    clearInline()
    if (token.hasSpace) {
        mentionJob?.cancel()
        clearMentions()
        return
    }
    startMentionSearch(token, debounce = true)
}

internal fun DialogExecutor.startMentionSearch(token: ComposerAtToken, debounce: Boolean) {
    mentionMemberOffset = 0
    val cached = cachedMentionCandidates(token.handle)
    emit(Msg.MentionQuery(token.handle))
    emit(Msg.MentionPage(candidates = cached, loading = true, hasMore = false))
    mentionJob?.cancel()
    mentionJob = work.launch {
        if (debounce) delay(300)
        loadMentions(token.handle, append = false)
    }
}

internal fun DialogExecutor.loadMoreMentions() {
    val handle = snapshot().mentionToken ?: return
    if (snapshot().mentionLoading || !snapshot().mentionHasMore) return
    if (mentionJob?.isActive == true) return
    mentionJob = work.launch {
        loadMentions(handle, append = true)
    }
}

internal fun DialogExecutor.cachedSenderByUsername(username: String): Profile? =
    snapshot().senders.values.firstOrNull { profile ->
        profile.username.equals(username, ignoreCase = true)
    }

internal fun DialogExecutor.cachedInlineBot(username: String): PeerId? {
    inlineBots[username]?.let { return it }
    val sender = cachedSenderByUsername(username)?.takeIf { it.isBot } ?: return null
    inlineBots[username] = sender.id
    return sender.id
}

internal fun DialogExecutor.cachedMentionCandidates(handle: String): List<MentionCandidate> {
    val merged = LinkedHashMap<Long, MentionCandidate>()
    snapshot().senders.values.forEach { profile ->
        if (profile.kind == "group" || profile.kind == "chat" || profile.kind == "channel") {
            return@forEach
        }
        if (!matchesMention(profile.title, profile.username, handle)) return@forEach
        val username = profile.username
        if (profile.isBot && !username.isNullOrBlank()) {
            inlineBots[username.lowercase()] = profile.id
        }
        merged[profile.id.value] = mentionCandidate(
            id = profile.id,
            title = profile.title,
            username = profile.username,
            avatar = profile.avatarCacheKey,
            isBot = profile.isBot,
        )
    }
    inlineBots.forEach { (username, peerId) ->
        if (handle.isNotEmpty() && !username.contains(handle, ignoreCase = true)) return@forEach
        val sender = snapshot().senders[peerId]
        merged.putIfAbsent(
            peerId.value,
            mentionCandidate(
                id = peerId,
                title = sender?.title?.ifBlank { username } ?: username,
                username = sender?.username ?: username,
                avatar = sender?.avatarCacheKey,
                isBot = true,
            ),
        )
    }
    return merged.values.toList()
}

internal fun DialogExecutor.startInline(parsed: InlineBotQuery, knownBotId: PeerId? = null) {
    if (parsed == snapshot().inlineQuery && (snapshot().inlineResults != null || snapshot().inlineLoading)) {
        return
    }
    emit(Msg.InlineQuery(parsed))
    emit(Msg.InlinePage(results = null, loading = true, error = false))
    inlineJob?.cancel()
    inlineJob = work.launch {
        delay(300)
        fetchInlineResults(parsed, knownBotId)
    }
}

internal fun DialogExecutor.currentInlineQuery(): InlineBotQuery? =
    ComposerAt.mentionToken(snapshot().draft)?.let(ComposerAt::inlineQuery)

internal fun DialogExecutor.fallbackMentionsIfTypingHandle() {
    val token = ComposerAt.mentionToken(snapshot().draft) ?: return
    if (!token.hasSpace) startMentionSearch(token, debounce = false)
}

internal suspend fun DialogExecutor.confirmInlineBot(parsed: InlineBotQuery) {
    cachedInlineBot(parsed.username)?.let { botId ->
        val current = currentInlineQuery() ?: return
        if (current.username != parsed.username) return
        emit(Msg.InlineQuery(current))
        emit(Msg.InlinePage(results = null, loading = true, error = false))
        fetchInlineResults(current, botId)
        return
    }
    val cachedUser = cachedSenderByUsername(parsed.username)
    if (cachedUser != null && !cachedUser.isBot) {
        if (currentInlineQuery()?.username == parsed.username) {
            clearInline()
            fallbackMentionsIfTypingHandle()
        }
        return
    }
    when (val resolved = client.resolveUsername(parsed.username)) {
        is Outcome.Ok -> {
            if (!resolved.value.isBot) {
                if (currentInlineQuery()?.username == parsed.username) {
                    clearInline()
                    fallbackMentionsIfTypingHandle()
                }
                return
            }
            inlineBots[parsed.username] = resolved.value.peerId
            val current = currentInlineQuery() ?: return
            if (current.username != parsed.username) return
            emit(Msg.InlineQuery(current))
            emit(Msg.InlinePage(results = null, loading = true, error = false))
            fetchInlineResults(current, resolved.value.peerId)
        }
        is Outcome.Err -> {
            if (currentInlineQuery()?.username == parsed.username) {
                clearInline()
                fallbackMentionsIfTypingHandle()
            }
        }
    }
}

internal suspend fun DialogExecutor.loadMentions(handle: String, append: Boolean) {
    if (snapshot().mentionToken != handle) return
    val merged = LinkedHashMap<Long, MentionCandidate>()
    if (append) {
        snapshot().mentionCandidates.forEach { merged[it.peerId.value] = it }
        emit(
            Msg.MentionPage(
                candidates = snapshot().mentionCandidates,
                loading = true,
                hasMore = snapshot().mentionHasMore,
            ),
        )
    } else {
        cachedMentionCandidates(handle).forEach { merged[it.peerId.value] = it }
    }
    val loadMembers = snapshot().isGroup || snapshot().isChannel
    val loadContacts = !append && handle.isNotEmpty()
    val loadResolve = !append && ComposerAt.isUsername(handle)
    val memberOffset = if (append) mentionMemberOffset else 0
    var hasMore = false
    coroutineScope {
        val membersJob = if (loadMembers) {
            async { client.getProfileMembers(chatId, "recent", handle, memberOffset, 50) }
        } else {
            null
        }
        val contactsJob = if (loadContacts) {
            async { client.contactsSearch(handle, 20) }
        } else {
            null
        }
        val resolveJob = if (loadResolve) {
            async { client.resolveUsername(handle) }
        } else {
            null
        }
        when (val page = membersJob?.await()) {
            is Outcome.Ok -> {
                val batch = page.value.members
                batch.forEach { member ->
                    if (matchesMention(member.title, member.username, handle)) {
                        merged[member.id.value] = mentionCandidate(
                            id = member.id,
                            title = member.title,
                            username = member.username,
                            avatar = member.avatarCacheKey,
                        )
                    }
                }
                mentionMemberOffset = memberOffset + batch.size
                hasMore = batch.isNotEmpty() && mentionMemberOffset < page.value.count
            }
            else -> if (append) hasMore = false
        }
        when (val search = contactsJob?.await()) {
            is Outcome.Ok -> {
                (search.value.people + search.value.chats).forEach { peer ->
                    if (peer.isGroup || peer.isChannel) return@forEach
                    merged.putIfAbsent(
                        peer.id.value,
                        mentionCandidate(
                            id = peer.id,
                            title = peer.title,
                            username = peer.username,
                            isBot = peer.isBot,
                        ),
                    )
                }
            }
            else -> Unit
        }
        when (val resolved = resolveJob?.await()) {
            is Outcome.Ok -> {
                if (resolved.value.isBot) {
                    inlineBots[handle.lowercase()] = resolved.value.peerId
                }
                merged.putIfAbsent(
                    resolved.value.peerId.value,
                    mentionCandidate(
                        id = resolved.value.peerId,
                        title = resolved.value.title,
                        username = resolved.value.username ?: handle,
                        isBot = resolved.value.isBot,
                    ),
                )
            }
            else -> Unit
        }
        val hydrate = merged.values.filter { it.isBot }.toList().takeLast(8).map { candidate ->
            async {
                when (val profile = client.getProfile(candidate.peerId)) {
                    is Outcome.Ok -> candidate.peerId to profile.value
                    is Outcome.Err -> null
                }
            }
        }
        hydrate.forEach { job ->
            val row = job.await() ?: return@forEach
            val profile = row.second
            sessionStore?.upsertProfile(profile)
            merged[row.first.value] = mentionCandidate(
                id = profile.id,
                title = profile.title.ifBlank { merged[row.first.value]?.title.orEmpty() },
                username = profile.username ?: merged[row.first.value]?.username,
                avatar = profile.avatarCacheKey,
                isBot = profile.isBot,
            )
        }
    }
    if (snapshot().mentionToken != handle) return
    emit(
        Msg.MentionPage(
            candidates = merged.values.toList(),
            loading = false,
            hasMore = hasMore,
        ),
    )
}

internal fun DialogExecutor.matchesMention(title: String, username: String?, handle: String): Boolean {
    if (handle.isEmpty()) return true
    return title.contains(handle, ignoreCase = true) ||
        username.orEmpty().contains(handle, ignoreCase = true)
}

internal fun DialogExecutor.mentionCandidate(
    id: PeerId,
    title: String,
    username: String?,
    avatar: String? = null,
    isBot: Boolean = false,
): MentionCandidate {
    val sender = snapshot().senders[id]
    return MentionCandidate(
        peerId = id,
        title = title,
        username = username,
        avatarCacheKey = peerAvatarCacheKey(id, avatar ?: sender?.avatarCacheKey),
        isBot = isBot || sender?.isBot == true,
    )
}

internal fun DialogExecutor.selectMention(candidate: MentionCandidate) {
    val token = ComposerAt.mentionToken(snapshot().draft) ?: return
    val username = candidate.username?.trim()?.trimStart('@').orEmpty()
    val insertion: String
    val mentions: List<DraftMention>
    if (username.isNotEmpty()) {
        insertion = "@$username "
        mentions = ComposerAt.remapMentions(
            snapshot().draft,
            ComposerAt.replaceHandle(snapshot().draft, token, insertion),
            snapshot().draftMentions,
        )
    } else {
        val name = candidate.title.trim().ifEmpty { return }
        insertion = "$name "
        val next = ComposerAt.replaceHandle(snapshot().draft, token, insertion)
        val kept = ComposerAt.remapMentions(snapshot().draft, next, snapshot().draftMentions)
        mentions = kept + DraftMention(
            peerId = candidate.peerId,
            start = token.atIndex,
            length = name.length,
        )
    }
    val nextDraft = ComposerAt.replaceHandle(snapshot().draft, token, insertion)
    applyDraft(nextDraft, mentions)
    scheduleComposerAt(nextDraft)
}

internal fun DialogExecutor.clearMentions() {
    if (snapshot().mentionToken != null || snapshot().mentionCandidates.isNotEmpty() || snapshot().mentionLoading) {
        emit(Msg.MentionQuery(null))
        mentionMemberOffset = 0
        emit(Msg.MentionPage(candidates = emptyList(), loading = false, hasMore = false))
    }
}

internal fun DialogExecutor.clearInline() {
    confirmingInlineUser = null
    if (snapshot().inlineQuery == null &&
        snapshot().inlineResults == null &&
        !snapshot().inlineLoading &&
        !snapshot().inlineError
    ) {
        return
    }
    emit(Msg.InlineQuery(null))
    emit(Msg.InlinePage(results = null, loading = false, error = false))
}

internal fun DialogExecutor.retryInlineResults() {
    val parsed = snapshot().inlineQuery ?: return
    emit(Msg.InlinePage(results = null, loading = true, error = false))
    inlineJob?.cancel()
    inlineJob = work.launch {
        fetchInlineResults(parsed)
    }
}

internal fun DialogExecutor.loadMoreInlineResults() {
    val current = snapshot()
    val parsed = current.inlineQuery ?: return
    val offset = current.inlineResults?.nextOffset?.takeIf { it.isNotBlank() } ?: return
    if (current.inlineLoading) return
    val botId = inlineBots[parsed.username] ?: return
    emit(Msg.InlinePage(results = current.inlineResults, loading = true, error = false))
    inlineJob?.cancel()
    inlineJob = work.launch {
        fetchInlineResults(parsed, botId, offset, append = true)
    }
}

internal fun DialogExecutor.mergeInlinePage(
    incoming: InlineBotResults,
    existing: InlineBotResults?,
    append: Boolean,
): InlineBotResults {
    if (!append || existing == null) return incoming
    val mergedResults = (existing.results + incoming.results).distinctBy(InlineBotResult::id)
    val progressed = mergedResults.size > existing.results.size
    return existing.copy(
        results = mergedResults,
        nextOffset = incoming.nextOffset.takeIf { progressed && !it.isNullOrBlank() },
        cacheTime = incoming.cacheTime,
    )
}

internal suspend fun DialogExecutor.fetchInlineResults(
    parsed: InlineBotQuery,
    knownBotId: PeerId? = null,
    offset: String = "",
    append: Boolean = false,
) {
    val botId = knownBotId ?: inlineBots[parsed.username] ?: when (val resolved = client.resolveUsername(parsed.username)) {
        is Outcome.Ok -> {
            if (!resolved.value.isBot) {
                if (snapshot().inlineQuery == parsed) {
                    clearInline()
                }
                return
            }
            inlineBots[parsed.username] = resolved.value.peerId
            resolved.value.peerId
        }
        is Outcome.Err -> {
            if (snapshot().inlineQuery == parsed) {
                emit(Msg.InlinePage(results = null, loading = false, error = true))
            }
            return
        }
    }
    when (val page = client.getInlineBotResults(chatId, botId, parsed.query, offset)) {
        is Outcome.Ok -> {
            if (snapshot().inlineQuery == parsed) {
                emit(
                    Msg.InlinePage(
                        results = mergeInlinePage(page.value, snapshot().inlineResults, append),
                        loading = false,
                        error = false,
                    ),
                )
            }
        }
        is Outcome.Err -> {
            if (snapshot().inlineQuery == parsed) {
                val kept = snapshot().inlineResults?.takeIf { it.results.isNotEmpty() }
                emit(
                    Msg.InlinePage(
                        results = kept,
                        loading = false,
                        error = kept == null,
                    ),
                )
                if (kept == null) {
                    clearInline()
                    fallbackMentionsIfTypingHandle()
                }
            }
        }
    }
}

internal fun DialogExecutor.sendInlineResult(resultId: String) {
    val page = snapshot().inlineResults ?: return
    if (snapshot().sending || resultId.isBlank()) return
    emit(Msg.Sending(true))
    val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, snapshot().replyTo?.id?.id)
    work.launch {
        when (val result = client.sendInlineBotResult(
            chatId,
            page.queryId,
            resultId,
            replyId,
            topId,
        )) {
            is Outcome.Ok -> {
                emit(Msg.Append(result.value))
                warmup?.upsertMessages(listOf(result.value))
                applyDraft("")
                emit(Msg.ReplyTo(null))
                clearInline()
            }
            is Outcome.Err -> handleError(result.telegramError, false)
        }
        emit(Msg.Sending(false))
    }
}
