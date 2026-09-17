package org.monogram.feature.dialog.ui

import android.content.Intent
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.Outcome
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewCaption
import org.monogram.core.models.InstantViewFetchDecision
import org.monogram.core.models.InstantViewLink
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.InstantViewPages
import org.monogram.core.models.InstantViewRichText
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.feature.dialog.R
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import java.io.File
import org.monogram.core.ui.loading.MonogramCircularProgress
import org.monogram.core.ui.loading.MonogramLoadingContained
import org.monogram.core.ui.loading.MonogramLoadingHeroSize


internal class InstantViewController(
    private val loadPage: suspend (String, Int) -> Outcome<InstantViewPage>,
    private val initialUrl: String,
    private val initialHash: Int,
) : com.arkivanov.essenty.instancekeeper.InstanceKeeper.Instance {
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
    ) {
        val page: InstantViewPage? get() = stack.lastOrNull()
        val canGoBack: Boolean get() = stack.size > 1
        val matches: List<Int>
            get() {
                val query = search.trim()
                if (query.isEmpty()) return emptyList()
                return page?.blocks.orEmpty().mapIndexedNotNull { index, block ->
                    if (blockSearchText(block).contains(query, ignoreCase = true)) index else null
                }
            }
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
        fetch(current?.url ?: initialUrl, current?.hash ?: initialHash, replace = true)
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
            if (current.stack.size <= 1) current else current.copy(stack = current.stack.dropLast(1))
        }
    }

    fun setSearch(query: String) {
        mutable.update { it.copy(search = query, searchIndex = 0) }
    }

    fun nextMatch() {
        mutable.update { current ->
            val matches = current.matches
            if (matches.isEmpty()) current
            else current.copy(searchIndex = (current.searchIndex + 1) % matches.size)
        }
    }

    private suspend fun fetch(
        url: String,
        hash: Int,
        replace: Boolean,
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
                alreadyRefetchedPartial = alreadyRefetchedPartial,
                pendingAnchor = pendingAnchor,
            )
            is Outcome.Err -> {
                mutable.update { it.copy(loading = false, error = if (replace) result.message else it.error) }
                false
            }
        }
    }

    private suspend fun applyFetched(
        page: InstantViewPage,
        url: String,
        hash: Int,
        replace: Boolean,
        alreadyRefetchedPartial: Boolean,
        pendingAnchor: String?,
    ): Boolean = when (
        val decision = InstantViewPages.decideFetch(page, alreadyRefetchedPartial)
    ) {
        InstantViewFetchDecision.KeepExisting -> {
            if (mutable.value.page == null && hash != 0) {
                fetch(url, 0, replace, alreadyRefetchedPartial, pendingAnchor)
            } else {
                mutable.update { it.copy(loading = false, pendingAnchor = pendingAnchor ?: it.pendingAnchor) }
                mutable.value.page != null
            }
        }
        InstantViewFetchDecision.Unavailable -> {
            mutable.update { it.copy(loading = false, error = if (replace) "" else it.error) }
            false
        }
        is InstantViewFetchDecision.RefetchFull ->
            fetch(decision.url, 0, replace, alreadyRefetchedPartial = true, pendingAnchor = pendingAnchor)
        is InstantViewFetchDecision.Show -> {
            mutable.update { current ->
                current.copy(
                    loading = false,
                    error = null,
                    pendingAnchor = pendingAnchor ?: current.pendingAnchor,
                    stack = if (replace || current.stack.isEmpty()) {
                        listOf(decision.page)
                    } else {
                        current.stack + decision.page
                    },
                )
            }
            true
        }
    }
}
