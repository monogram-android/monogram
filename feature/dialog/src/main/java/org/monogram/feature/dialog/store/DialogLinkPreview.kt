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
    if (snapshot().linkPreview != null || snapshot().linkPreviewUrl != null ||
        snapshot().linkPreviewLoading || snapshot().linkPreviewHidden
    ) {
        emit(Msg.LinkPreview(preview = null, url = null, fixed = false, loading = false, hidden = false))
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

internal fun DialogExecutor.scheduleLinkPreview(draft: String) {
    val url = FixedLinkPreviewRules.firstUrl(draft)
    if (url == null) {
        clearLinkPreview()
        return
    }
    val current = snapshot()
    val ready = current.linkPreview?.hasContent == true
    if (FixedLinkPreviewRules.matchesPreviewSource(url, current.linkPreviewUrl) &&
        (ready || current.linkPreviewLoading || current.linkPreviewHidden)
    ) {
        return
    }
    linkPreviewJob?.cancel()
    emit(Msg.LinkPreview(preview = null, url = url, fixed = false, loading = true, hidden = false))
    linkPreviewJob = work.launch {
        delay(400)
        val wantFix = AppearanceSettings.state.value.fixLinkPreviews
        val fetchUrls = if (wantFix) {
            val candidates = FixedLinkPreviewRules.candidateFixedUrls(url).map { it.url }
            if (candidates.isEmpty()) listOf(url) else candidates
        } else {
            listOf(url)
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
        val match = found
        if (match == null) {
            emit(Msg.LinkPreview(preview = null, url = url, fixed = false, loading = false, hidden = false))
            return@launch
        }
        val (usedUrl, preview) = match
        emit(
            Msg.LinkPreview(
                preview = preview,
                url = usedUrl,
                fixed = usedUrl != url,
                loading = false,
                hidden = false,
            ),
        )
    }
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
            ),
        )
        return
    }
    val url = current.linkPreviewUrl ?: return
    emit(Msg.LinkPreview(preview = null, url = null, fixed = false, loading = true, hidden = false))
    scheduleLinkPreview(current.draft)
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
                        emit(Msg.LinkPreview(preview = preview, url = candidate.url, fixed = true, loading = false))
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
            ),
        )
    }
}
