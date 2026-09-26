package org.monogram.feature.dialog.store

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.models.FixedLinkPreviewRules
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.WebpagePreview
import org.monogram.core.ui.AppearanceSettings

internal fun DialogExecutor.clearLinkPreview() {
    linkPreviewJob?.cancel()
    val current = snapshot()
    if (current.linkPreview != null || current.linkPreviewUrl != null ||
        current.linkPreviewLoading || current.linkPreviewHidden ||
        current.linkPreviewChoice != null || current.linkPreviewUrls.isNotEmpty()
    ) {
        emit(
            Msg.LinkPreview(
                preview = null,
                url = null,
                fixed = false,
                loading = false,
                hidden = false,
                choice = null,
                urls = emptyList(),
            ),
        )
    }
}

internal fun InstantViewPage.toComposerPreview(): WebpagePreview = WebpagePreview(
    url = url,
    title = title,
    siteName = siteName,
    description = description,
    type = webpageType,
    hasInstantView = hasInstantView,
    hash = hash,
)

internal fun DialogExecutor.scheduleLinkPreview(draft: String, prefer: String? = null) {
    val urls = FixedLinkPreviewRules.urls(draft)
    if (urls.isEmpty()) {
        clearLinkPreview()
        return
    }
    val current = snapshot()
    val choice = prefer?.takeIf { it in urls }
        ?: current.linkPreviewChoice?.takeIf { it in urls }
        ?: urls.first()
    val ready = current.linkPreview?.hasContent == true
    val sameChoice = FixedLinkPreviewRules.matchesPreviewSource(choice, current.linkPreviewUrl) ||
        choice == current.linkPreviewChoice
    if (sameChoice && (ready || current.linkPreviewLoading || current.linkPreviewHidden) && prefer == null) {
        if (urls != current.linkPreviewUrls || choice != current.linkPreviewChoice) {
            emit(
                Msg.LinkPreview(
                    preview = current.linkPreview,
                    url = current.linkPreviewUrl,
                    fixed = current.linkPreviewFixed,
                    loading = current.linkPreviewLoading,
                    hidden = current.linkPreviewHidden,
                    choice = choice,
                    urls = urls,
                ),
            )
        }
        return
    }
    linkPreviewJob?.cancel()
    emit(
        Msg.LinkPreview(
            preview = null,
            url = choice,
            fixed = false,
            loading = true,
            hidden = false,
            choice = choice,
            urls = urls,
        ),
    )
    linkPreviewJob = work.launch {
        delay(400)
        val wantFix = AppearanceSettings.state.value.fixLinkPreviews
        val fetchUrls = if (wantFix) {
            val candidates = FixedLinkPreviewRules.candidateFixedUrls(choice).map { it.url }
            if (candidates.isEmpty()) listOf(choice) else candidates
        } else {
            listOf(choice)
        }
        var found: Pair<String, WebpagePreview>? = null
        for (fetchUrl in fetchUrls) {
            found = fetchVisiblePreview(fetchUrl)?.let { fetchUrl to it }
            if (found != null) break
        }
        if (found == null) {
            for (attempt in 0 until 2) {
                delay(700)
                found = fetchVisiblePreview(fetchUrls.first())?.let { fetchUrls.first() to it }
                if (found != null) break
            }
        }
        if (snapshot().linkPreviewChoice != choice) return@launch
        val match = found
        if (match == null) {
            emit(
                Msg.LinkPreview(
                    preview = null,
                    url = choice,
                    fixed = false,
                    loading = false,
                    hidden = false,
                    choice = choice,
                    urls = urls,
                ),
            )
            return@launch
        }
        val (usedUrl, preview) = match
        emit(
            Msg.LinkPreview(
                preview = preview,
                url = usedUrl,
                fixed = usedUrl != choice,
                loading = false,
                hidden = false,
                choice = choice,
                urls = urls,
            ),
        )
    }
}

internal fun DialogExecutor.selectLinkPreview(url: String) {
    scheduleLinkPreview(snapshot().draft, prefer = url)
}

private suspend fun DialogExecutor.fetchVisiblePreview(fetchUrl: String): WebpagePreview? =
    when (val result = client.getWebPagePreview(fetchUrl)) {
        is Outcome.Ok -> result.value.toComposerPreview().takeIf { it.hasContent }
        is Outcome.Err -> null
    }

internal fun DialogExecutor.dismissLinkPreview() {
    linkPreviewJob?.cancel()
    val current = snapshot()
    emit(
        Msg.LinkPreview(
            preview = current.linkPreview,
            url = current.linkPreviewUrl,
            fixed = current.linkPreviewFixed,
            loading = false,
            hidden = true,
            choice = current.linkPreviewChoice,
            urls = current.linkPreviewUrls,
        ),
    )
}

internal fun DialogExecutor.restoreLinkPreview() {
    val current = snapshot()
    if (current.linkPreview != null) {
        emit(
            Msg.LinkPreview(
                preview = current.linkPreview,
                url = current.linkPreviewUrl,
                fixed = current.linkPreviewFixed,
                loading = false,
                hidden = false,
                choice = current.linkPreviewChoice,
                urls = current.linkPreviewUrls,
            ),
        )
        return
    }
    scheduleLinkPreview(current.draft, prefer = current.linkPreviewChoice)
}

internal fun DialogExecutor.fixLinkPreview() {
    if (!AppearanceSettings.state.value.fixLinkPreviews) return
    val currentUrl = snapshot().linkPreviewUrl ?: snapshot().draft.let(FixedLinkPreviewRules::firstUrl)
        ?: return
    val candidates = FixedLinkPreviewRules.candidateFixedUrls(currentUrl)
    if (candidates.isEmpty()) return
    linkPreviewJob?.cancel()
    emit(
        Msg.LinkPreview(
            preview = snapshot().linkPreview,
            url = currentUrl,
            fixed = snapshot().linkPreviewFixed,
            loading = true,
            hidden = false,
            choice = snapshot().linkPreviewChoice,
            urls = snapshot().linkPreviewUrls,
        ),
    )
    linkPreviewJob = work.launch {
        for (candidate in candidates) {
            when (val result = client.getWebPagePreview(candidate.url)) {
                is Outcome.Ok -> {
                    val preview = result.value.toComposerPreview()
                    if (!preview.title.isNullOrBlank() || !preview.description.isNullOrBlank() ||
                        preview.hasInstantView
                    ) {
                        val nextDraft = FixedLinkPreviewRules.replaceFirstUrl(snapshot().draft, candidate.url)
                        if (nextDraft != snapshot().draft) applyDraft(nextDraft)
                        emit(
                            Msg.LinkPreview(
                                preview = preview,
                                url = candidate.url,
                                fixed = true,
                                loading = false,
                                choice = snapshot().linkPreviewChoice,
                                urls = snapshot().linkPreviewUrls,
                            ),
                        )
                        return@launch
                    }
                }
                is Outcome.Err -> Unit
            }
        }
        emit(
            Msg.LinkPreview(
                preview = snapshot().linkPreview,
                url = currentUrl,
                fixed = false,
                loading = false,
                choice = snapshot().linkPreviewChoice,
                urls = snapshot().linkPreviewUrls,
            ),
        )
    }
}
