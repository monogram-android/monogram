package org.monogram.feature.dialog.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.common.TextToolsProviderConfig
import org.monogram.core.common.TextToolsProviderSettings
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import org.monogram.core.models.poll
import org.monogram.core.ui.TextToolsSettings
import org.monogram.feature.dialog.AiComposerState
import org.monogram.feature.dialog.AiComposerUndo
import org.monogram.feature.dialog.AiTextTab
import org.monogram.feature.dialog.DialogTextToolsState
import org.monogram.feature.dialog.DraftMention
import org.monogram.feature.dialog.InlineTranslation
import org.monogram.feature.dialog.TextToolMessageResult
import org.monogram.feature.dialog.TextToolsAction
import org.monogram.feature.dialog.canSummarizeMessage
import org.monogram.feature.dialog.canUseTextTools
import org.monogram.feature.dialog.pollTexts
import org.monogram.feature.dialog.pollTranslation
import org.monogram.feature.dialog.replaceTextToolSelection
import org.monogram.feature.dialog.textToolsText
import org.monogram.feature.dialog.translationBatches

internal class DialogTextTools(
    private val executor: DialogExecutor,
) {
    private val state get() = executor.snapshot().textTools
    private var initialized = false
    private var account: Long? = null
    private var providerConfig: TextToolsProviderConfig? = null
    private var epoch: Long = 0
    private var generation = 0L
    private var availabilityJob: Job? = null
    private var messageJob: Job? = null
    private var messageRequestId = 0L
    private val messageRequests = mutableMapOf<Int, Long>()
    private var chatJob: Job? = null
    private var composerJob: Job? = null
    private var tonesJob: Job? = null
    private var toneJob: Job? = null
    private var peerJob: Job? = null
    private var visible = emptySet<Int>()
    private val dismissedTranslations = mutableSetOf<Int>()

    private fun update(block: (DialogTextToolsState) -> DialogTextToolsState) =
        executor.emit(Msg.TextTools(block(state)))

    private fun current(token: Long) =
        token == generation && (executor.warmup?.outgoingAccountIsCurrent(epoch) != false)

    private fun source(id: Int) = executor.snapshot().messages.firstOrNull { it.id.id == id }

    private fun unchanged(message: Message) =
        source(message.id.id)?.let {
            it.text == message.text && it.entities == message.entities && it.fileName == message.fileName && it.editDate == message.editDate &&
                    it.canUseTextTools()
        } == true

    fun accept(action: TextToolsAction) {
        when (action) {
            TextToolsAction.Initialize -> {
                initialize()
            }

            is TextToolsAction.MessageTool -> {
                message(action.messageId, action.summary, action.tone, action.manual)
            }

            is TextToolsAction.SummaryTone -> {
                state.messageResult
                    ?.takeIf { it.summary }
                    ?.let { message(it.source.id.id, true, action.key) }
            }

            TextToolsAction.CloseResult -> {
                val id =
                    state.messageResult
                        ?.source
                        ?.id
                        ?.id
                messageJob?.cancel()
                if (id != null) messageRequests.remove(id)
                update { it.copy(messageResult = null) }
            }

            TextToolsAction.ProviderChanged -> {
                generation++
                messageJob?.cancel()
                messageRequests.clear()
                dismissedTranslations.clear()
                chatJob?.cancel()
                composerJob?.cancel()
                tonesJob?.cancel()
                toneJob?.cancel()
                peerJob?.cancel()
                update {
                    it.copy(
                        availability = null,
                        availabilityError = null,
                        chatEnabled = false,
                        translations = emptyMap(),
                        messageResult = null,
                        composer = null,
                        undo = null,
                        tones = emptyList(),
                        resolvedTone = null,
                        toneExample = null,
                        tonesLoaded = false,
                        tonesLoading = false,
                        chatLoading = false,
                        toneBusy = false,
                        toneError = null,
                        peerSaving = false,
                    )
                }
                initialize(force = true)
            }

            TextToolsAction.CancelRequest -> {
                messageJob?.cancel()
                messageRequests.clear()
                composerJob?.cancel()
                update {
                    it.copy(
                        translations = it.translations.filterValues { translation -> !translation.loading },
                        messageResult = it.messageResult?.copy(loading = false),
                        composer = it.composer?.copy(loading = false),
                    )
                }
            }

            is TextToolsAction.ComposerOptions -> {
                composerJob?.cancel()
                update {
                    it.copy(
                        composer =
                            it.composer?.copy(
                                tab = action.tab,
                                tone = action.tone,
                                emojify = action.emojify,
                                loading = false,
                                result = null,
                                error = null,
                            ),
                    )
                }
            }

            TextToolsAction.RetryChat -> {
                translateVisible()
            }

            TextToolsAction.RetryAvailability -> {
                initialize(force = true)
            }

            TextToolsAction.Retry -> {
                when {
                    state.messageResult != null -> {
                        state.messageResult?.let {
                            message(
                                it.source.id.id,
                                it.summary,
                                it.tone,
                            )
                        }
                    }

                    state.composer != null -> {
                        state.composer?.let {
                            compose(
                                it.tab,
                                it.tone,
                                it.emojify,
                            )
                        }
                    }

                    state.availabilityError != null -> {
                        initialize(force = true)
                    }

                    else -> {
                        translateVisible()
                    }
                }
            }

            is TextToolsAction.Language -> {
                if (!action.code.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?"))) return
                invalidateTranslations()
                update {
                    it.copy(
                        targetLanguage = action.code,
                        translations = emptyMap(),
                    )
                }
                persist()
                state.messageResult?.let { message(it.source.id.id, it.summary, it.tone) }
                state.composer?.let {
                    if (it.tab == AiTextTab.Translate) {
                        composerJob?.cancel()
                        update { tools ->
                            tools.copy(
                                composer =
                                    it.copy(
                                        loading = false,
                                        result = null,
                                        error = null,
                                    ),
                            )
                        }
                    }
                }
                translateVisible()
            }

            is TextToolsAction.ChatEnabled -> {
                invalidateTranslations()
                update {
                    it.copy(
                        chatEnabled = action.enabled && it.chatAllowed,
                    )
                }
                persist()
                translateVisible()
            }

            is TextToolsAction.PeerDisabled -> {
                togglePeer(action.disabled)
            }

            is TextToolsAction.Visible -> {
                if (visible != action.ids) {
                    visible = action.ids
                    translateVisible()
                }
            }

            is TextToolsAction.DismissTranslation -> {
                dismissedTranslations += action.id
                if (state.translations[action.id]?.loading == true) {
                    messageJob?.cancel()
                }
                messageRequests.remove(action.id)
                update { it.copy(translations = it.translations - action.id) }
            }

            is TextToolsAction.OpenComposer -> {
                openComposer(action.start, action.end, action.source, action.markdown)
            }

            TextToolsAction.CloseComposer -> {
                composerJob?.cancel()
                update { it.copy(composer = null) }
            }

            is TextToolsAction.Compose -> {
                compose(action.tab, action.tone, action.emojify)
            }

            TextToolsAction.Apply -> {
                apply()
            }

            TextToolsAction.Undo -> {
                undo()
            }

            TextToolsAction.LoadTones -> {
                loadTones()
            }

            is TextToolsAction.ResolveTone -> {
                toneRequest {
                    when (
                        val result =
                            executor.client.getAiTextTone(action.key.trim().substringAfterLast('/'))
                    ) {
                        is Outcome.Ok -> {
                            update {
                                it.copy(
                                    resolvedTone = result.value,
                                    toneExample = null,
                                )
                            }
                        }

                        is Outcome.Err -> {
                            update { it.copy(toneError = result.telegramError) }
                        }
                    }
                }
            }

            is TextToolsAction.ToneExample -> {
                toneRequest {
                    when (val result = executor.client.getAiTextToneExample(action.key)) {
                        is Outcome.Ok -> update { it.copy(toneExample = result.value) }
                        is Outcome.Err -> update { it.copy(toneError = result.telegramError) }
                    }
                }
            }

            is TextToolsAction.SaveTone -> {
                toneRequest {
                    when (val result = executor.client.saveAiTextTone(action.key, action.unsave)) {
                        is Outcome.Ok -> {
                            update { it.copy(resolvedTone = null, toneExample = null) }
                            loadTones()
                        }

                        is Outcome.Err -> {
                            update { it.copy(toneError = result.telegramError) }
                        }
                    }
                }
            }

            is TextToolsAction.DeleteTone -> {
                toneRequest {
                    if (state.tones.none { it.key == action.key && it.creator }) return@toneRequest
                    when (val result = executor.client.deleteAiTextTone(action.key)) {
                        is Outcome.Ok -> {
                            update { it.copy(resolvedTone = null, toneExample = null) }
                            loadTones()
                        }

                        is Outcome.Err -> {
                            update { it.copy(toneError = result.telegramError) }
                        }
                    }
                }
            }

            is TextToolsAction.WriteTone -> {
                toneRequest {
                    if (action.title.isBlank() || action.title.length > 12 || action.prompt.isBlank() || action.prompt.length > 1024 ||
                        action.emojiId < 0
                    ) {
                        return@toneRequest
                    }
                    val result =
                        if (action.key == null) {
                            executor.client.createAiTextTone(
                                action.title.trim(),
                                action.prompt.trim(),
                                action.emojiId,
                                action.displayAuthor,
                            )
                        } else {
                            executor.client.updateAiTextTone(
                                action.key,
                                action.title.trim(),
                                action.prompt.trim(),
                                action.emojiId,
                                action.displayAuthor,
                            )
                        }
                    when (result) {
                        is Outcome.Ok -> {
                            update { it.copy(resolvedTone = result.value, toneExample = null) }
                            loadTones()
                        }

                        is Outcome.Err -> {
                            update { it.copy(toneError = result.telegramError) }
                        }
                    }
                }
            }
        }
    }

    private fun initialize(force: Boolean = false) {
        val manual = TextToolsSettings.manualTranslation()
        val chat = TextToolsSettings.chatTranslation()
        val excluded = TextToolsSettings.excludedLanguages()
        if (state.manualEnabled != manual || state.chatEnabledPreference != chat || state.excludedLanguages != excluded) {
            invalidateTranslations()
            update {
                it.copy(
                    manualEnabled = manual,
                    chatEnabledPreference = chat,
                    excludedLanguages = excluded,
                    translations = it.translations.filterValues { result -> result.summary },
                )
            }
            dismissedTranslations.clear()
            translateVisible()
        }
        if (initialized && !force) {
            if (account?.let(TextToolsProviderSettings::load) != providerConfig) {
                accept(
                    TextToolsAction.ProviderChanged,
                )
            }
            return
        }
        initialized = true
        epoch = executor.warmup?.outgoingAccountEpoch() ?: 0
        availabilityJob?.cancel()
        val token = generation
        availabilityJob =
            executor.work.launch {
                val previousAccount = account
                val previousConfig = providerConfig
                account = executor.sessionStore?.readAuthorizedUserId()?.value
                providerConfig = account?.let(TextToolsProviderSettings::load)
                if (previousConfig != null && (previousAccount != account || previousConfig != providerConfig)) {
                    accept(TextToolsAction.ProviderChanged)
                    return@launch
                }
                if (!current(token)) return@launch
                update {
                    it.copy(
                        customProvider = providerConfig?.isCustom == true,
                        providerConfigured =
                            providerConfig?.let { !it.isCustom || (it.apiKey.isNotBlank() && it.model.isNotBlank()) } != false,
                        availabilityLoading = true,
                        availabilityError = null,
                        manualEnabled = TextToolsSettings.manualTranslation(),
                        chatEnabledPreference = TextToolsSettings.chatTranslation(),
                        excludedLanguages = TextToolsSettings.excludedLanguages(),
                        targetLanguage =
                            account?.let { id ->
                                TextToolsSettings.targetLanguage(id, executor.chatId.value)
                            } ?: it.targetLanguage,
                        chatEnabled =
                            account?.let { id ->
                                TextToolsSettings.chatEnabled(
                                    id,
                                    executor.chatId.value,
                                )
                            } ?: false,
                    )
                }
                when (val result = executor.client.getTextToolsAvailability(executor.chatId)) {
                    is Outcome.Ok -> {
                        if (current(token)) {
                            update {
                                it.copy(
                                    availability = result.value,
                                    availabilityLoading = false,
                                    chatAllowed =
                                        it.customProvider ||
                                                if (executor.isChannel) {
                                                    result.value.channelAutoTranslation
                                                } else {
                                                    executor.isPremium()
                                                },
                                )
                            }
                            translateVisible()
                        }
                    }

                    is Outcome.Err -> {
                        if (current(token)) {
                            update {
                                it.copy(
                                    availabilityLoading = false,
                                    availabilityError = result.telegramError,
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun persist() {
        account?.let {
            TextToolsSettings.setDialog(
                it,
                executor.chatId.value,
                state.targetLanguage,
                state.chatEnabled,
            )
        }
    }

    private fun invalidateTranslations() {
        messageJob?.cancel()
        chatJob?.cancel()
        messageRequests.clear()
        update {
            it.copy(
                translations = it.translations.filterValues { translation -> !translation.loading },
                chatLoading = false,
                chatError = null,
                messageResult = it.messageResult?.copy(loading = false),
            )
        }
    }

    private fun message(
        id: Int,
        summary: Boolean,
        tone: String? = null,
        manual: Boolean = false,
    ) {
        val dialog = executor.snapshot()
        val message = dialog.messages.firstOrNull { it.id.id == id } ?: return
        val eligible = if (summary) {
            dialog.canSummarizeMessage(message)
        } else {
            dialog.textTools.canTranslateManually && message.canUseTextTools()
        }
        if (!eligible) return
        dismissedTranslations.remove(id)
        messageJob?.cancel()
        val requestId = ++messageRequestId
        messageRequests[id] = requestId
        val token = generation
        val language = state.targetLanguage
        update {
            it.copy(
                translations =
                    it.translations
                        .filterValues { translation -> !translation.loading }
                        .let { stable ->
                            if (manual || summary) {
                                stable + (
                                        id to
                                                InlineTranslation(
                                                    message,
                                                    summary = summary,
                                                    manual = manual,
                                                    loading = true,
                                                )
                                        )
                            } else {
                                stable
                            }
                        },
                messageResult =
                    if (manual || summary) {
                        it.messageResult
                    } else {
                        TextToolMessageResult(
                            message,
                            summary,
                            tone = tone,
                        )
                    },
            )
        }
        messageJob =
            executor.work.launch {
                message.poll?.let { poll ->
                    val translated = executor.client.translateTexts(pollTexts(poll), language)
                    if (!current(token) || messageRequests[id] != requestId || !unchanged(message)) return@launch
                    val next =
                        when (translated) {
                            is Outcome.Ok -> {
                                pollTranslation(poll, translated.value)?.let {
                                    TextToolMessageResult(
                                        source = message,
                                        summary = false,
                                        loading = false,
                                        poll = it,
                                    )
                                } ?: TextToolMessageResult(
                                    source = message,
                                    summary = false,
                                    loading = false,
                                    error = Outcome.Err("Invalid poll translation").telegramError,
                                )
                            }

                            is Outcome.Err -> {
                                TextToolMessageResult(
                                    source = message,
                                    summary = false,
                                    loading = false,
                                    error = translated.telegramError,
                                )
                            }
                        }
                    if (manual || summary) {
                        update {
                            it.copy(
                                translations =
                                    it.translations.filterKeys { id -> id !in dismissedTranslations } +
                                            (
                                                    id to
                                                            InlineTranslation(
                                                                source = message,
                                                                poll = next.poll,
                                                                summary = summary,
                                                                manual = manual,
                                                                loading = false,
                                                                error = next.error,
                                                            )
                                                    ),
                            )
                        }
                    } else {
                        update { it.copy(messageResult = next) }
                    }
                    return@launch
                }
                val customProvider = state.customProvider
                val sourceText = TextToolText(message.text.orEmpty(), message.entities)
                val result =
                    if (summary) {
                        if (customProvider) {
                            executor.client.summarizeText(sourceText, language, tone)
                        } else {
                            executor.client.summarizeMessageText(
                                executor.chatId,
                                id,
                                language,
                                tone,
                            )
                        }
                    } else {
                        if (customProvider) {
                            executor.client.translateTexts(listOf(sourceText), language)
                                .let { translated ->
                                    when (translated) {
                                        is Outcome.Ok -> {
                                            translated.value.firstOrNull()?.let { Outcome.Ok(it) }
                                                ?: Outcome.Err("Empty translation")
                                        }

                                        is Outcome.Err -> {
                                            translated
                                        }
                                    }
                                }
                        } else {
                            when (
                                val translated =
                                    executor.client.translateMessageTexts(
                                        executor.chatId,
                                        listOf(id),
                                        language,
                                    )
                            ) {
                                is Outcome.Ok -> {
                                    translated.value.firstOrNull()?.let { Outcome.Ok(it) }
                                        ?: Outcome.Err("Empty translation")
                                }

                                is Outcome.Err -> {
                                    translated
                                }
                            }
                        }
                    }
                if (!current(token) || messageRequests[id] != requestId || !unchanged(message)) return@launch
                when (result) {
                    is Outcome.Ok -> {
                        update {
                            it.copy(
                                translations =
                                    if (manual || summary) {
                                        it.translations + (
                                                id to
                                                        InlineTranslation(
                                                            message,
                                                            result =
                                                                result.value.takeIf { value ->
                                                                    value.text.isNotBlank()
                                                                },
                                                            summary = summary,
                                                            manual = manual,
                                                            loading = false,
                                                            error =
                                                                if (result.value.text.isBlank()) {
                                                                    Outcome.Err("Empty text result").telegramError
                                                                } else {
                                                                    null
                                                                },
                                                        )
                                                )
                                    } else {
                                        it.translations
                                    },
                                messageResult =
                                    if (manual || summary) {
                                        it.messageResult
                                    } else {
                                        TextToolMessageResult(
                                            source = message,
                                            summary = summary,
                                            tone = tone,
                                            loading = false,
                                            result = result.value.takeIf { value -> value.text.isNotBlank() },
                                            error =
                                                if (result.value.text.isBlank()) {
                                                    Outcome.Err("Empty text result").telegramError
                                                } else {
                                                    null
                                                },
                                        )
                                    },
                            )
                        }
                    }

                    is Outcome.Err -> {
                        update {
                            it.copy(
                                translations =
                                    if (manual || summary) {
                                        it.translations + (
                                                id to
                                                        InlineTranslation(
                                                            message,
                                                            summary = summary,
                                                            manual = manual,
                                                            loading = false,
                                                            error = result.telegramError,
                                                        )
                                                )
                                    } else {
                                        it.translations
                                    },
                                messageResult =
                                    if (manual || summary) {
                                        it.messageResult
                                    } else {
                                        TextToolMessageResult(
                                            source = message,
                                            summary = summary,
                                            tone = tone,
                                            loading = false,
                                            error = result.telegramError,
                                        )
                                    },
                            )
                        }
                    }
                }
            }
    }

    private fun translateVisible() {
        if (!state.providerConfigured || !state.chatEnabled || !state.chatAllowed || !state.showChatTranslation ||
            chatJob?.isActive == true
        ) {
            return
        }
        val token = generation
        val language = state.targetLanguage
        chatJob =
            executor.work.launch {
                delay(200)
                update { it.copy(chatLoading = true, chatError = null) }
                while (current(token)) {
                    val pending =
                        executor.snapshot().messages.filter {
                            it.poll == null && it.id.id in visible && it.id.id !in dismissedTranslations && it.canUseTextTools() &&
                                    state.translations[it.id.id]?.source?.let { old -> old.text == it.text && old.entities == it.entities } !=
                                    true &&
                                    !DialogTextLanguage.isExcluded(
                                        it.text.orEmpty(),
                                        state.excludedLanguages,
                                    )
                        }
                    val batch = translationBatches(pending).firstOrNull()
                    if (batch == null) {
                        if (!translateVisiblePolls(token, language)) break
                        continue
                    }
                    val customProvider = state.customProvider
                    val result =
                        if (customProvider) {
                            executor.client.translateTexts(
                                batch.map {
                                    TextToolText(
                                        it.text.orEmpty(),
                                        it.entities,
                                    )
                                },
                                language,
                            )
                        } else {
                            executor.client.translateMessageTexts(
                                executor.chatId,
                                batch.map { it.id.id },
                                language,
                            )
                        }
                    when (result) {
                        is Outcome.Ok -> {
                            if (!current(token)) return@launch
                            if (result.value.size != batch.size || result.value.any { it.text.isBlank() }) {
                                update { it.copy(chatError = Outcome.Err("Empty translation result").telegramError) }
                                break
                            }
                            val entries =
                                batch
                                    .zip(result.value)
                                    .filter {
                                        unchanged(
                                            it.first,
                                        ) && it.second.text.isNotBlank() && it.first.id.id !in dismissedTranslations
                                    }.associate {
                                        it.first.id.id to
                                                InlineTranslation(
                                                    it.first,
                                                    result = it.second,
                                                )
                                    }
                            update {
                                it.copy(
                                    translations =
                                        it.translations.filterKeys { id -> id !in dismissedTranslations } + entries,
                                )
                            }
                        }

                        is Outcome.Err -> {
                            if (current(token)) update { it.copy(chatError = result.telegramError) }
                            break
                        }
                    }
                }
                if (current(token)) update { it.copy(chatLoading = false) }
            }
    }

    /** Polls carry their question/answers in the media payload, so they translate through the text API. */
    private suspend fun translateVisiblePolls(
        token: Long,
        language: String,
    ): Boolean {
        val pending =
            executor.snapshot().messages.firstOrNull {
                it.poll != null && it.id.id in visible && it.id.id !in dismissedTranslations && it.canUseTextTools() &&
                        state.translations[it.id.id]?.poll == null &&
                        !DialogTextLanguage.isExcluded(
                            it.textToolsText(),
                            state.excludedLanguages,
                        )
            }
        if (pending == null) return false
        val poll = requireNotNull(pending.poll)
        val strings = pollTexts(poll)
        if (strings.any { it.text.length > 25_000 }) {
            if (current(token)) update { it.copy(chatError = Outcome.Err("Poll text is too long").telegramError) }
            return false
        }
        val batches =
            buildList {
                var batch = mutableListOf<TextToolText>()
                var length = 0L
                strings.forEach { text ->
                    if (batch.size == 20 || length + text.text.length > 25_000L) {
                        add(batch)
                        batch = mutableListOf()
                        length = 0L
                    }
                    batch += text
                    length += text.text.length
                }
                if (batch.isNotEmpty()) add(batch)
            }
        val translated =
            buildList {
                for (batch in batches) {
                    when (val result = executor.client.translateTexts(batch, language)) {
                        is Outcome.Ok -> {
                            if (result.value.size != batch.size) return@buildList
                            addAll(result.value)
                        }

                        is Outcome.Err -> {
                            if (current(token)) update { it.copy(chatError = result.telegramError) }
                            return false
                        }
                    }
                }
            }
        if (!current(token) || translated.size != strings.size || pending.id.id in dismissedTranslations) return false
        val translatedPoll = pollTranslation(poll, translated) ?: return false
        update {
            it.copy(
                translations =
                    it.translations.filterKeys { id -> id !in dismissedTranslations } +
                            (
                                    pending.id.id to
                                            InlineTranslation(
                                                pending,
                                                poll = translatedPoll,
                                            )
                                    ),
            )
        }
        return true
    }

    private fun togglePeer(disabled: Boolean) {
        if (state.peerSaving) return
        val token = generation
        update { it.copy(peerSaving = true, chatError = null) }
        peerJob =
            executor.work.launch {
                when (
                    val result =
                        executor.client.togglePeerTextTranslations(executor.chatId, disabled)
                ) {
                    is Outcome.Ok -> {
                        if (current(token)) {
                            invalidateTranslations()
                            update {
                                it.copy(
                                    peerSaving = false,
                                    availability = it.availability?.copy(peerTranslationsDisabled = disabled),
                                    chatEnabled = it.chatEnabled && !disabled,
                                )
                            }
                            persist()
                        }
                    }

                    is Outcome.Err -> {
                        if (current(token)) {
                            update {
                                it.copy(
                                    peerSaving = false,
                                    chatError = result.telegramError,
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun draft(): TextToolText {
        val s = executor.snapshot()
        return TextToolText(
            s.draft,
            s.draftEntities +
                    s.draftMentions.map {
                        TextEntity(
                            "mention_name",
                            it.start,
                            it.length,
                            it.peerId.value.toString(),
                        )
                    },
        )
    }

    private fun openComposer(
        start: Int,
        end: Int,
        source: TextToolText?,
        markdown: Boolean?,
    ) {
        composerJob?.cancel()
        val original = source ?: draft()
        if (original.text.isBlank()) return
        val lo = minOf(start, end).coerceIn(0, original.text.length)
        val hi = maxOf(start, end).coerceIn(lo, original.text.length)
        update {
            it.copy(
                composer =
                    AiComposerState(
                        original,
                        executor
                            .snapshot()
                            .editing
                            ?.id
                            ?.id,
                        if (lo == hi) {
                            0
                        } else {
                            lo
                        },
                        if (lo == hi) original.text.length else hi,
                        markdown ?: executor.snapshot().draftFormatting,
                        editorOwned = source != null,
                    ),
                undo = null,
            )
        }
        if (!state.tonesLoaded) loadTones()
    }

    private fun composerMatches(composer: AiComposerState) =
        composer.editorOwned ||
                composer.matches(
                    draft(),
                    executor
                        .snapshot()
                        .editing
                        ?.id
                        ?.id,
                    executor.snapshot().draftFormatting,
                )

    fun draftChanged() {
        val composer = state.composer
        if (composer != null && !composerMatches(composer)) {
            composerJob?.cancel()
            update { it.copy(composer = composer.copy(stale = true, loading = false)) }
        }
        state.undo?.let { undo -> if (draft() != undo.applied) update { it.copy(undo = null) } }
    }

    private fun compose(
        tab: AiTextTab,
        tone: String?,
        emojify: Boolean,
    ) {
        val composer = state.composer ?: return
        if (!state.providerConfigured || !composerMatches(composer) || composer.stale) return
        composerJob?.cancel()
        val token = generation
        update {
            it.copy(
                composer =
                    composer.copy(
                        tab = tab,
                        tone = tone,
                        emojify = emojify,
                        loading = true,
                        result = null,
                        error = null,
                    ),
            )
        }
        composerJob =
            executor.work.launch {
                val result =
                    executor.client.composeTextWithAi(
                        composer.input,
                        proofread = tab == AiTextTab.Proofread,
                        emojify = emojify,
                        toLang =
                            state.targetLanguage.takeIf {
                                tab == AiTextTab.Translate
                            },
                        tone = tone.takeIf { tab == AiTextTab.Style },
                    )
                if (!current(token) || !composerMatches(composer)) return@launch
                when (result) {
                    is Outcome.Ok -> {
                        update {
                            if (result.value.result.text
                                    .isBlank()
                            ) {
                                it.copy(
                                    composer =
                                        it.composer?.copy(
                                            loading = false,
                                            error = Outcome.Err("Empty text result").telegramError,
                                        ),
                                )
                            } else {
                                it.copy(
                                    composer =
                                        it.composer?.copy(
                                            loading = false,
                                            result = result.value,
                                        ),
                                )
                            }
                        }
                    }

                    is Outcome.Err -> {
                        update {
                            it.copy(
                                composer =
                                    it.composer?.copy(
                                        loading = false,
                                        error = result.telegramError,
                                    ),
                            )
                        }
                    }
                }
            }
    }

    private fun setDraft(
        value: TextToolText,
        markdown: Boolean,
    ) {
        val mentions =
            value.entities.filter { it.kind == "mention_name" }.mapNotNull { entity ->
                entity.url
                    ?.toLongOrNull()
                    ?.let { DraftMention(PeerId(it), entity.offset, entity.length) }
            }
        executor.applyDraft(value.text, mentions)
        executor.emit(Msg.DraftEntities(value.entities.filterNot { it.kind == "mention_name" && it.url?.toLongOrNull() != null }))
        executor.emit(Msg.DraftFormatting(markdown))
        executor.scheduleDraftPreview()
        executor.persistComposerDraft()
        executor.scheduleComposerAt(value.text)
        executor.scheduleLinkPreview(value.text)
    }

    private fun apply() {
        val composer = state.composer ?: return
        val result = composer.result?.result ?: return
        if (composer.editorOwned || result.text.isBlank() || !composerMatches(composer) || composer.stale || composer.loading) return
        val applied =
            replaceTextToolSelection(composer.original, composer.start, composer.end, result)
        setDraft(applied, composer.markdown)
        update { it.copy(composer = null, undo = AiComposerUndo(composer, applied)) }
    }

    private fun undo() {
        val undo = state.undo ?: return
        if (draft() == undo.applied && executor
                .snapshot()
                .editing
                ?.id
                ?.id == undo.original.editingId
        ) {
            setDraft(undo.original.original, undo.original.markdown)
        }
        update { it.copy(undo = null) }
    }

    private fun loadTones() {
        if (state.customProvider) return
        tonesJob?.cancel()
        val token = generation
        update { it.copy(tonesLoading = true, toneError = null) }
        tonesJob =
            executor.work.launch {
                when (val result = executor.client.getAiTextTones()) {
                    is Outcome.Ok -> {
                        if (current(token)) {
                            update {
                                it.copy(
                                    tones = result.value,
                                    tonesLoading = false,
                                    tonesLoaded = true,
                                )
                            }
                        }
                    }

                    is Outcome.Err -> {
                        if (current(token)) {
                            update {
                                it.copy(
                                    tonesLoading = false,
                                    toneError = result.telegramError,
                                )
                            }
                        }
                    }
                }
            }
    }

    private var toneRequestId = 0

    private fun toneRequest(block: suspend () -> Unit) {
        if (state.toneBusy) return
        val id = ++toneRequestId
        update { it.copy(toneBusy = true, toneError = null) }
        toneJob =
            executor.work.launch {
                try {
                    block()
                } finally {
                    if (toneRequestId == id) update { it.copy(toneBusy = false) }
                }
            }
    }

    fun messagesChanged(ids: Set<Int>) {
        val selected =
            state.messageResult
                ?.source
                ?.id
                ?.id in ids
        messageRequests.keys.removeAll(ids)
        if (selected) messageJob?.cancel()
        chatJob?.cancel()
        update {
            it.copy(
                translations = it.translations - ids,
                messageResult = if (selected) null else it.messageResult,
                chatLoading = false,
            )
        }
        translateVisible()
    }

    fun leave() {
        generation++
        listOf(
            availabilityJob,
            messageJob,
            chatJob,
            composerJob,
            tonesJob,
            toneJob,
            peerJob,
        ).forEach { it?.cancel() }
        initialized = false
        messageRequests.clear()
        update {
            it.copy(
                availabilityLoading = false,
                chatLoading = false,
                translations = it.translations.filterValues { translation -> !translation.loading },
                messageResult = it.messageResult?.copy(loading = false),
                composer = it.composer?.copy(loading = false),
                tonesLoading = false,
                toneBusy = false,
                peerSaving = false,
            )
        }
    }
}
