package org.monogram.feature.dialog.ui

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import org.monogram.core.common.Outcome
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewFetchDecision
import org.monogram.core.models.InstantViewHeading
import org.monogram.core.models.InstantViewLink
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.InstantViewPages
import org.monogram.network.bridge.MtprotoClient

internal class InstantViewController(
    private val loadPage: suspend (String, Int) -> Outcome<InstantViewPage>,
    private val initialUrl: String,
    private val initialHash: Int,
) : InstanceKeeper.Instance {
    constructor(client: MtprotoClient, url: String, hash: Int) : this(client::getWebPage, url, hash)

    private val loadMutex = Mutex()
    private var loaded = false

    override fun onDestroy() {}

    data class State(
        val stack: List<InstantViewPage> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val search: String = "",
        val searchIndex: Int = 0,
        val pendingAnchor: String? = null,
        val matches: List<Int> = emptyList(),
        val outline: List<InstantViewHeading> = emptyList(),
        val readingMinutes: Int = 0,
    ) {
        val page: InstantViewPage? get() = stack.lastOrNull()
        val canGoBack: Boolean get() = stack.size > 1
        val searching: Boolean get() = search.isNotBlank()
        val matchCount: Int get() = matches.size

        val currentMatch: Int?
            get() = if (matches.isEmpty()) null else matches[searchIndex.coerceIn(
                0,
                matches.lastIndex
            )]
    }

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable

    suspend fun load() {
        loadMutex.withLock {
            if (loaded) return
            try {
                val ok = fetch(initialUrl, initialHash, replace = true)
                if (ok || mutable.value.error != null) loaded = true
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    suspend fun retry() {
        val current = mutable.value.page
        if (current == null) {
            fetch(initialUrl, initialHash, replace = true)
        } else {
            fetch(current.url, 0, replace = false, replaceTop = true)
        }
    }

    suspend fun reloadCurrent() {
        val current = mutable.value.page ?: return retry()
        fetch(current.url, 0, replace = false, replaceTop = true)
    }

    suspend fun openLink(href: String): Boolean {
        val current = mutable.value.page
        return when (val link = InstantViewPages.resolveLink(initialUrl, current?.url, href)) {
            is InstantViewLink.Anchor -> {
                mutable.update { it.copy(pendingAnchor = link.name) }
                true
            }

            is InstantViewLink.Page -> fetch(
                url = link.url,
                hash = 0,
                replace = false,
                pendingAnchor = link.anchor,
            )

            is InstantViewLink.External -> false
        }
    }

    fun consumeAnchor(): String? {
        val name = mutable.value.pendingAnchor ?: return null
        mutable.update { it.copy(pendingAnchor = null) }
        return name
    }

    fun pop() {
        mutable.update { current ->
            if (current.stack.size <= 1) {
                current
            } else {
                val stack = current.stack.dropLast(1)
                current.copy(stack = stack).derivedFrom(stack.lastOrNull(), current.search)
            }
        }
    }

    fun setSearch(query: String) {
        mutable.update { current ->
            current.copy(search = query, searchIndex = 0)
                .derivedFrom(current.page, query)
        }
    }

    fun nextMatch() = stepMatch(1)

    fun previousMatch() = stepMatch(-1)

    fun articleText(): String =
        mutable.value.page?.blocks?.let(InstantViewPages::plainText).orEmpty()

    private fun stepMatch(delta: Int) {
        mutable.update { current ->
            val count = current.matches.size
            if (count == 0) {
                current
            } else {
                val next = (current.searchIndex + delta).mod(count)
                current.copy(searchIndex = next)
            }
        }
    }

    private suspend fun fetch(
        url: String,
        hash: Int,
        replace: Boolean,
        replaceTop: Boolean = false,
        alreadyRefetchedPartial: Boolean = false,
        pendingAnchor: String? = null,
    ): Boolean {
        mutable.update { it.copy(loading = true, error = null) }
        return when (val result = loadPage(url, hash)) {
            is Outcome.Ok -> applyFetched(
                page = result.value,
                url = url,
                hash = hash,
                replace = replace,
                replaceTop = replaceTop,
                alreadyRefetchedPartial = alreadyRefetchedPartial,
                pendingAnchor = pendingAnchor,
            )

            is Outcome.Err -> {
                mutable.update {
                    it.copy(
                        loading = false,
                        error = if (replace || replaceTop) result.message else it.error,
                    )
                }
                false
            }
        }
    }

    private suspend fun applyFetched(
        page: InstantViewPage,
        url: String,
        hash: Int,
        replace: Boolean,
        replaceTop: Boolean,
        alreadyRefetchedPartial: Boolean,
        pendingAnchor: String?,
    ): Boolean = when (
        val decision = InstantViewPages.decideFetch(page, alreadyRefetchedPartial)
    ) {
        InstantViewFetchDecision.KeepExisting -> {
            if (mutable.value.page == null && hash != 0) {
                fetch(url, 0, replace, replaceTop, alreadyRefetchedPartial, pendingAnchor)
            } else {
                mutable.update {
                    it.copy(
                        loading = false,
                        pendingAnchor = pendingAnchor ?: it.pendingAnchor
                    )
                }
                mutable.value.page != null
            }
        }

        InstantViewFetchDecision.Unavailable -> {
            mutable.update {
                it.copy(
                    loading = false,
                    error = if (replace || replaceTop) "" else it.error
                )
            }
            false
        }

        is InstantViewFetchDecision.RefetchFull ->
            fetch(
                url = decision.url,
                hash = 0,
                replace = replace,
                replaceTop = replaceTop,
                alreadyRefetchedPartial = true,
                pendingAnchor = pendingAnchor,
            )

        is InstantViewFetchDecision.Show -> {
            mutable.update { current ->
                val stack = when {
                    replaceTop && current.stack.isNotEmpty() -> current.stack.dropLast(1) + decision.page
                    replace || current.stack.isEmpty() -> listOf(decision.page)
                    else -> current.stack + decision.page
                }
                current.copy(
                    loading = false,
                    error = null,
                    pendingAnchor = pendingAnchor ?: current.pendingAnchor,
                    stack = stack,
                    searchIndex = 0,
                ).derivedFrom(decision.page, current.search)
            }
            true
        }
    }

    private fun State.derivedFrom(page: InstantViewPage?, query: String): State {
        val plainText = page?.blocks?.let(InstantViewPages::plainText).orEmpty()
        val estimate = InstantViewPages.readingEstimate(plainText)
        return copy(
            outline = page?.blocks?.let(InstantViewPages::outline).orEmpty(),
            readingMinutes = estimate.minutes,
            matches = page?.blocks?.let { blocks -> matchIndexes(blocks, query) }.orEmpty(),
        )
    }

    private companion object {
        fun matchIndexes(blocks: List<InstantViewBlock>, query: String): List<Int> {
            val needle = query.trim()
            if (needle.isEmpty()) return emptyList()
            return blocks.mapIndexedNotNull { index, block ->
                if (blockSearchText(block).contains(needle, ignoreCase = true)) index else null
            }
        }
    }
}
