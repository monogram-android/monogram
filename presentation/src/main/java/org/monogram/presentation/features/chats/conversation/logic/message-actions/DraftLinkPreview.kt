package org.monogram.presentation.features.chats.conversation.logic

import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.DraftLinkPreviewRequest
import org.monogram.domain.models.LinkPreviewTarget
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent
import java.net.URI

internal fun DefaultChatComponent.handleSelectDraftLinkPreview(url: String) {
    val normalizedUrl = DraftLinkPreviewTextParser.normalizeUrl(url) ?: url
    val matchingTarget = _state.value.draftLinkTargets.firstOrNull {
        it.normalizedUrl == normalizedUrl || it.sourceUrl == url
    } ?: return

    Log.d(
        DRAFT_LINK_PREVIEW_TAG,
        "selectPreview raw=$url normalized=$normalizedUrl target=${matchingTarget.normalizedUrl}"
    )

    _state.update { current ->
        current.copy(
            selectedDraftLinkPreviewUrl = matchingTarget.normalizedUrl,
            resolvedDraftLinkPreviewUrl = resolveDraftLinkPreviewUrlForSend(
                selectedUrl = matchingTarget.normalizedUrl,
                fixLinkPreviews = current.fixLinkPreviews
            ),
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
    Log.d(DRAFT_LINK_PREVIEW_TAG, "dismissPreview selected=$selectedUrl")
    _state.update { current ->
        current.copy(
            dismissedDraftLinkPreviewUrls = current.dismissedDraftLinkPreviewUrls + selectedUrl,
            draftLinkPreview = null,
            resolvedDraftLinkPreviewUrl = null,
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
    Log.d(
        DRAFT_LINK_PREVIEW_TAG,
        "recomputeDraftLinkPreview textLength=${text.length} updateDraftText=$updateDraftText showLinkPreviews=${currentState.showLinkPreviews}"
    )
    if (!currentState.showLinkPreviews) {
        Log.d(DRAFT_LINK_PREVIEW_TAG, "recompute skipped because showLinkPreviews=false")
        clearDraftLinkPreviewState(
            draftText = if (updateDraftText) text else currentState.draftText,
            preserveDismissed = false
        )
        return
    }

    val targets = DraftLinkPreviewTextParser.parseTargets(text)
    Log.d(
        DRAFT_LINK_PREVIEW_TAG,
        "recompute parsedTargets=${targets.map { it.normalizedUrl }}"
    )
    if (targets.isEmpty()) {
        Log.d(DRAFT_LINK_PREVIEW_TAG, "recompute cleared because no targets found")
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
    Log.d(
        DRAFT_LINK_PREVIEW_TAG,
        "recompute selected=$selectedUrl dismissed=$dismissedUrls disableForSend=$shouldDisableForSend"
    )

    _state.update { state ->
        state.copy(
            draftText = if (updateDraftText) text else state.draftText,
            draftLinkTargets = targets,
            selectedDraftLinkPreviewUrl = selectedUrl,
            resolvedDraftLinkPreviewUrl = selectedUrl?.let {
                resolveDraftLinkPreviewUrlForSend(
                    selectedUrl = it,
                    fixLinkPreviews = state.fixLinkPreviews
                )
            },
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
        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "recompute skipped loading because selected preview is dismissed"
        )
        return
    }

    resolveDraftLinkPreview()
}

internal fun DefaultChatComponent.resolveDraftLinkPreview() {
    val state = _state.value
    val selectedUrl = state.selectedDraftLinkPreviewUrl ?: run {
        draftLinkPreviewJob?.cancel()
        Log.d(DRAFT_LINK_PREVIEW_TAG, "resolve aborted because selectedUrl is null")
        _state.update {
            it.copy(
                draftLinkPreview = null,
                isDraftLinkPreviewLoading = false,
                draftLinkPreviewError = null,
                resolvedDraftLinkPreviewUrl = null,
                isDraftLinkPreviewDisabledForSend = false
            )
        }
        return
    }

    if (!state.showLinkPreviews || state.dismissedDraftLinkPreviewUrls.contains(selectedUrl)) {
        draftLinkPreviewJob?.cancel()
        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "resolve aborted selected=$selectedUrl showLinkPreviews=${state.showLinkPreviews} dismissed=${
                state.dismissedDraftLinkPreviewUrls.contains(
                    selectedUrl
                )
            }"
        )
        _state.update {
            it.copy(
                draftLinkPreview = null,
                isDraftLinkPreviewLoading = false,
                draftLinkPreviewError = null,
                resolvedDraftLinkPreviewUrl = null,
                isDraftLinkPreviewDisabledForSend = true
            )
        }
        return
    }

    val sourceUrl =
        state.draftLinkTargets.firstOrNull { it.normalizedUrl == selectedUrl }?.sourceUrl
            ?: selectedUrl
    draftLinkPreviewJob?.cancel()
    Log.d(
        DRAFT_LINK_PREVIEW_TAG,
        "resolve start selected=$selectedUrl source=$sourceUrl fixLinkPreviews=${state.fixLinkPreviews}"
    )
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
        if (latestState.selectedDraftLinkPreviewUrl != selectedUrl) {
            Log.d(
                DRAFT_LINK_PREVIEW_TAG,
                "resolve drop result because selection changed from $selectedUrl to ${latestState.selectedDraftLinkPreviewUrl}"
            )
            return@launch
        }

        Log.d(
            DRAFT_LINK_PREVIEW_TAG,
            "resolve finish selected=$selectedUrl success=${preview != null} resolvedUrl=${preview?.resolvedUrl} webPageUrl=${preview?.webPage?.url}"
        )

        _state.update {
            it.copy(
                draftLinkPreview = preview?.webPage,
                resolvedDraftLinkPreviewUrl = preview?.resolvedUrl?.let { resolvedUrl ->
                    resolveDraftLinkPreviewUrlForSend(
                        selectedUrl = resolvedUrl,
                        fixLinkPreviews = it.fixLinkPreviews
                    )
                } ?: resolveDraftLinkPreviewUrlForSend(
                    selectedUrl = selectedUrl,
                    fixLinkPreviews = it.fixLinkPreviews
                ),
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
    Log.d(
        DRAFT_LINK_PREVIEW_TAG,
        "clearDraftLinkPreviewState draftTextLength=${draftText.length} preserveDismissed=$preserveDismissed"
    )
    _state.update {
        it.copy(
            draftText = draftText,
            draftLinkTargets = emptyList(),
            selectedDraftLinkPreviewUrl = null,
            resolvedDraftLinkPreviewUrl = null,
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

private fun resolveDraftLinkPreviewUrlForSend(
    selectedUrl: String,
    fixLinkPreviews: Boolean
): String {
    return if (fixLinkPreviews) {
        toFixedPreviewUrl(selectedUrl) ?: selectedUrl
    } else {
        selectedUrl
    }
}

private fun toFixedPreviewUrl(normalizedUrl: String): String? {
    val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return null
    val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
    val fixedHost = when (host) {
        "twitter.com", "x.com", "mobile.twitter.com", "mobile.x.com" -> "fxtwitter.com"
        "bsky.app" -> "fxbsky.app"
        else -> return null
    }

    return runCatching {
        URI(
            uri.scheme ?: "https",
            uri.userInfo,
            fixedHost,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment
        ).toString()
    }.getOrNull()
}

internal const val DRAFT_LINK_PREVIEW_ERROR_UNAVAILABLE = "unavailable"
private const val DRAFT_LINK_PREVIEW_TAG = "DraftLinkPreview"
