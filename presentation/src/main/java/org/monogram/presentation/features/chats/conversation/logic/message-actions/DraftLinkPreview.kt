package org.monogram.presentation.features.chats.conversation.logic

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.DraftLinkPreviewRequest
import org.monogram.domain.models.LinkPreviewTarget
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

internal fun DefaultChatComponent.handleSelectDraftLinkPreview(url: String) {
    val normalizedUrl = DraftLinkPreviewTextParser.normalizeUrl(url) ?: url
    val matchingTarget = _state.value.draftLinkTargets.firstOrNull {
        it.normalizedUrl == normalizedUrl || it.sourceUrl == url
    } ?: return

    _state.update { current ->
        current.copy(
            selectedDraftLinkPreviewUrl = matchingTarget.normalizedUrl,
            dismissedDraftLinkPreviewUrls = current.dismissedDraftLinkPreviewUrls - matchingTarget.normalizedUrl,
            isDraftLinkPreviewDisabledForSend = false,
            draftLinkPreviewError = null
        )
    }
    resolveDraftLinkPreview()
}

internal fun DefaultChatComponent.handleDismissDraftLinkPreview() {
    val selectedUrl = _state.value.selectedDraftLinkPreviewUrl ?: return
    draftLinkPreviewJob?.cancel()
    _state.update { current ->
        current.copy(
            dismissedDraftLinkPreviewUrls = current.dismissedDraftLinkPreviewUrls + selectedUrl,
            draftLinkPreview = null,
            isDraftLinkPreviewLoading = false,
            draftLinkPreviewError = null,
            isDraftLinkPreviewDisabledForSend = true
        )
    }
}

internal fun DefaultChatComponent.recomputeDraftLinkPreview(
    text: String,
    updateDraftText: Boolean
) {
    val currentState = _state.value
    if (!currentState.showLinkPreviews) {
        clearDraftLinkPreviewState(
            draftText = if (updateDraftText) text else currentState.draftText,
            preserveDismissed = false
        )
        return
    }

    val targets = DraftLinkPreviewTextParser.parseTargets(text)
    if (targets.isEmpty()) {
        clearDraftLinkPreviewState(
            draftText = if (updateDraftText) text else currentState.draftText,
            preserveDismissed = false
        )
        return
    }

    val selectedUrl = selectDraftPreviewUrl(
        previousSelectedUrl = currentState.selectedDraftLinkPreviewUrl,
        previousDismissedUrls = currentState.dismissedDraftLinkPreviewUrls,
        targets = targets
    )

    val dismissedUrls =
        currentState.dismissedDraftLinkPreviewUrls.intersect(targets.map { it.normalizedUrl }
            .toSet())
    val shouldDisableForSend = selectedUrl?.let(dismissedUrls::contains) == true

    _state.update { state ->
        state.copy(
            draftText = if (updateDraftText) text else state.draftText,
            draftLinkTargets = targets,
            selectedDraftLinkPreviewUrl = selectedUrl,
            dismissedDraftLinkPreviewUrls = dismissedUrls,
            draftLinkPreview = if (shouldDisableForSend) null else state.draftLinkPreview?.takeIf {
                it.url == selectedUrl || it.displayUrl == selectedUrl
            },
            isDraftLinkPreviewLoading = false,
            draftLinkPreviewError = null,
            isDraftLinkPreviewDisabledForSend = shouldDisableForSend
        )
    }

    if (shouldDisableForSend) {
        draftLinkPreviewJob?.cancel()
        return
    }

    resolveDraftLinkPreview()
}

internal fun DefaultChatComponent.resolveDraftLinkPreview() {
    val state = _state.value
    val selectedUrl = state.selectedDraftLinkPreviewUrl ?: run {
        draftLinkPreviewJob?.cancel()
        _state.update {
            it.copy(
                draftLinkPreview = null,
                isDraftLinkPreviewLoading = false,
                draftLinkPreviewError = null,
                isDraftLinkPreviewDisabledForSend = false
            )
        }
        return
    }

    if (!state.showLinkPreviews || state.dismissedDraftLinkPreviewUrls.contains(selectedUrl)) {
        draftLinkPreviewJob?.cancel()
        _state.update {
            it.copy(
                draftLinkPreview = null,
                isDraftLinkPreviewLoading = false,
                draftLinkPreviewError = null,
                isDraftLinkPreviewDisabledForSend = true
            )
        }
        return
    }

    val sourceUrl =
        state.draftLinkTargets.firstOrNull { it.normalizedUrl == selectedUrl }?.sourceUrl
            ?: selectedUrl
    draftLinkPreviewJob?.cancel()
    draftLinkPreviewJob = scope.launch {
        _state.update {
            it.copy(
                isDraftLinkPreviewLoading = true,
                draftLinkPreviewError = null
            )
        }

        val preview = repositoryMessage.getDraftLinkPreview(
            DraftLinkPreviewRequest(
                sourceUrl = sourceUrl,
                useFixedPreview = _state.value.fixLinkPreviews
            )
        )

        val latestState = _state.value
        if (latestState.selectedDraftLinkPreviewUrl != selectedUrl) return@launch

        _state.update {
            it.copy(
                draftLinkPreview = preview?.webPage,
                isDraftLinkPreviewLoading = false,
                draftLinkPreviewError = if (preview == null) DRAFT_LINK_PREVIEW_ERROR_UNAVAILABLE else null
            )
        }
    }
}

internal fun DefaultChatComponent.clearDraftLinkPreviewState(
    draftText: String = _state.value.draftText,
    preserveDismissed: Boolean = false
) {
    draftLinkPreviewJob?.cancel()
    _state.update {
        it.copy(
            draftText = draftText,
            draftLinkTargets = emptyList(),
            selectedDraftLinkPreviewUrl = null,
            dismissedDraftLinkPreviewUrls = if (preserveDismissed) it.dismissedDraftLinkPreviewUrls else emptySet(),
            draftLinkPreview = null,
            isDraftLinkPreviewLoading = false,
            draftLinkPreviewError = null,
            isDraftLinkPreviewDisabledForSend = false
        )
    }
}

internal fun DefaultChatComponent.clearDraftLinkPreviewAfterSend() {
    clearDraftLinkPreviewState(draftText = "", preserveDismissed = false)
}

private fun selectDraftPreviewUrl(
    previousSelectedUrl: String?,
    previousDismissedUrls: Set<String>,
    targets: List<LinkPreviewTarget>
): String? {
    val normalizedUrls = targets.map { it.normalizedUrl }
    if (previousSelectedUrl != null && previousSelectedUrl in normalizedUrls) {
        return previousSelectedUrl
    }
    return normalizedUrls.firstOrNull { it !in previousDismissedUrls }
        ?: normalizedUrls.firstOrNull()
}

internal fun ChatComponent.State.hasDraftLinkPreviewContent(): Boolean {
    return draftLinkPreview != null || isDraftLinkPreviewLoading || draftLinkPreviewError != null
}

internal const val DRAFT_LINK_PREVIEW_ERROR_UNAVAILABLE = "unavailable"
