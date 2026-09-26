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


@Composable
internal fun InstantViewBlockContent(
    block: InstantViewBlock,
    scale: Float,
    query: String,
    mediaRepository: MediaRepository?,
    onOpenUrl: (String) -> Unit,
    onOpenPeer: (PeerId) -> Unit,
) {
    when (block) {
        is InstantViewBlock.Text -> {
            val style = when (block.kind) {
                "title" -> MaterialTheme.typography.headlineMedium.copy(fontSize = (28 * scale).sp)
                "heading" -> when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall.copy(fontSize = (24 * scale).sp)
                    2 -> MaterialTheme.typography.titleLarge.copy(fontSize = (20 * scale).sp)
                    else -> MaterialTheme.typography.titleMedium.copy(fontSize = (18 * scale).sp)
                }
                "kicker", "subtitle", "authorDate" -> MaterialTheme.typography.labelLarge.copy(fontSize = (14 * scale).sp)
                "pre" -> MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = (14 * scale).sp,
                )
                "footer" -> MaterialTheme.typography.bodySmall.copy(fontSize = (13 * scale).sp)
                else -> MaterialTheme.typography.bodyLarge.copy(fontSize = (16 * scale).sp)
            }
            InstantViewText(block.text, block.entities, style, query, onOpenUrl)
            if (block.kind == "authorDate") {
                InstantViewPages.formatPublishedDate(block.publishedDate)?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = (13 * scale).sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is InstantViewBlock.Quote -> Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
        ) {
            InstantViewText(
                block.text,
                block.entities,
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (16 * scale).sp,
                    fontWeight = if (block.pull) FontWeight.Medium else FontWeight.Normal,
                ),
                query,
                onOpenUrl,
            )
            block.blocks.forEach { InstantViewBlockContent(it, scale, query, mediaRepository, onOpenUrl, onOpenPeer) }
            block.caption?.let { InstantViewRich(it, scale, query, onOpenUrl) }
        }
        is InstantViewBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = when {
                            item.checkbox && item.checked -> "☑ "
                            item.checkbox -> "☐ "
                            block.ordered -> "${item.number ?: (index + 1).toString()}. "
                            else -> "• "
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = (16 * scale).sp),
                    )
                    Column(Modifier.weight(1f)) {
                        if (item.text.isNotBlank()) {
                            InstantViewText(
                                item.text,
                                item.entities,
                                MaterialTheme.typography.bodyLarge.copy(fontSize = (16 * scale).sp),
                                query,
                                onOpenUrl,
                            )
                        }
                        item.blocks.forEach { InstantViewBlockContent(it, scale, query, mediaRepository, onOpenUrl, onOpenPeer) }
                    }
                }
            }
        }
        is InstantViewBlock.Table -> InstantViewTable(block, scale, query, onOpenUrl)
        is InstantViewBlock.Details -> {
            var open by remember(block.title?.text) { mutableStateOf(block.open) }
            Column {
                Text(
                    text = block.title?.text ?: "",
                    modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = (16 * scale).sp),
                )
                if (open) {
                    block.blocks.forEach { InstantViewBlockContent(it, scale, query, mediaRepository, onOpenUrl, onOpenPeer) }
                }
            }
        }
        is InstantViewBlock.Photo -> InstantViewPhoto(
            cacheKey = block.cacheKey,
            width = block.width,
            height = block.height,
            mediaRepository = mediaRepository,
            openable = true,
        )
        is InstantViewBlock.Document -> InstantViewDocument(block, scale, query, mediaRepository, onOpenUrl)
        is InstantViewBlock.Cover -> InstantViewCover(block.block, scale, query, mediaRepository, onOpenUrl, onOpenPeer)
        is InstantViewBlock.Embed -> InstantViewEmbed(block, scale, query, mediaRepository, onOpenUrl)
        is InstantViewBlock.EmbedPost -> Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                block.photoCacheKey?.let { InstantViewPhoto(it, 40, 40, mediaRepository) }
                Column {
                    Text(block.author, style = MaterialTheme.typography.titleSmall)
                    InstantViewPages.formatPublishedDate(block.date)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            block.blocks.forEach { InstantViewBlockContent(it, scale, query, mediaRepository, onOpenUrl, onOpenPeer) }
            block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
        }
        is InstantViewBlock.MediaGroup -> InstantViewMediaGroup(block, scale, query, mediaRepository, onOpenUrl, onOpenPeer)
        is InstantViewBlock.Channel -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onOpenPeer(PeerId(block.peerId)) }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape)) {
                block.photoCacheKey?.let { InstantViewPhoto(it, 40, 40, mediaRepository, hero = true) }
            }
            Column {
                Text(block.title, style = MaterialTheme.typography.titleMedium)
                block.username?.takeIf { it.isNotBlank() }?.let {
                    Text("@$it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        is InstantViewBlock.Related -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = block.title?.text ?: stringResource(R.string.dialog_instant_view_related),
                style = MaterialTheme.typography.titleMedium,
            )
            block.articles.forEach { article ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpenUrl(article.url) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    article.photoCacheKey?.let {
                        Box(Modifier.width(72.dp)) {
                            InstantViewPhoto(it, 72, 72, mediaRepository)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(article.title ?: article.url, style = MaterialTheme.typography.titleSmall)
                        val subtitle = InstantViewPages.relatedSubtitle(article)
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
        is InstantViewBlock.Map -> InstantViewMap(block, scale, query, mediaRepository, onOpenUrl)
        is InstantViewBlock.Math -> InstantViewMath(block.source, scale)
        is InstantViewBlock.Anchor -> Spacer(Modifier.height(1.dp).fillMaxWidth())
        InstantViewBlock.Divider -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        is InstantViewBlock.Buttons -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEach { item ->
                val href = item.entities.firstNotNullOfOrNull { entityHref(it.kind, it.url, item.text) }
                Button(onClick = { href?.let(onOpenUrl) }, enabled = href != null) {
                    Text(item.text)
                }
            }
        }
        InstantViewBlock.Unsupported -> Text(
            text = stringResource(R.string.dialog_instant_view_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (block is InstantViewBlock.Photo) {
        block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
    }
}
