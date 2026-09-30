package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewListItem
import org.monogram.core.models.InstantViewPages
import org.monogram.core.models.PeerId
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalLayoutApi::class)
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
            if (block.kind == "pre") {
                InstantViewPreformatted(block, scale, query, onOpenUrl)
            } else {
                InstantViewText(
                    text = block.text,
                    entities = block.entities,
                    style = ivTextStyle(block.kind, block.level, scale),
                    query = query,
                    onOpenUrl = onOpenUrl,
                    color = ivTextColor(block.kind),
                )
            }
            if (block.kind == "authorDate") {
                InstantViewPages.formatPublishedDate(block.publishedDate)?.let { date ->
                    Text(
                        text = date,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is InstantViewBlock.Quote -> {
            if (block.pull) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InstantViewText(
                        text = block.text,
                        entities = block.entities,
                        style = ivTextStyle("heading", 1, scale).copy(
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                        ),
                        query = query,
                        onOpenUrl = onOpenUrl,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    block.blocks.forEach {
                        InstantViewBlockContent(
                            it,
                            scale,
                            query,
                            mediaRepository,
                            onOpenUrl,
                            onOpenPeer
                        )
                    }
                    block.caption?.let { InstantViewRich(it, scale, query, onOpenUrl) }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp, bottom = 2.dp)
                            .width(IvSpacing.QuoteRuleWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
                    )
                    Spacer(Modifier.width(IvSpacing.QuoteIndent))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InstantViewText(
                            text = block.text,
                            entities = block.entities,
                            style = ivQuoteStyle(scale).copy(fontStyle = FontStyle.Italic),
                            query = query,
                            onOpenUrl = onOpenUrl,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        block.blocks.forEach {
                            InstantViewBlockContent(
                                it,
                                scale,
                                query,
                                mediaRepository,
                                onOpenUrl,
                                onOpenPeer
                            )
                        }
                        block.caption?.let { InstantViewRich(it, scale, query, onOpenUrl) }
                    }
                }
            }
        }

        is InstantViewBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    ListMarker(
                        item = item,
                        ordered = block.ordered,
                        fallbackNumber = (index + 1).toString(),
                        scale = scale,
                    )
                    Column(Modifier.weight(1f)) {
                        if (item.text.isNotBlank()) {
                            InstantViewText(
                                text = item.text,
                                entities = item.entities,
                                style = ivBodyStyle(scale),
                                query = query,
                                onOpenUrl = onOpenUrl,
                            )
                        }
                        item.blocks.forEach {
                            InstantViewBlockContent(
                                it,
                                scale,
                                query,
                                mediaRepository,
                                onOpenUrl,
                                onOpenPeer
                            )
                        }
                    }
                }
            }
        }

        is InstantViewBlock.Table -> InstantViewTable(block, scale, query, onOpenUrl)

        is InstantViewBlock.Details -> {
            var open by remember(block.title?.text) { mutableStateOf(block.open) }
            val rotation by animateFloatAsState(
                targetValue = if (open) 180f else 0f,
                label = "ivDetailsChevron",
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(IvSpacing.CardCorner))
                    .background(ivCardColor()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { open = !open }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = block.title?.text.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (open) R.string.dialog_instant_view_collapse else R.string.dialog_instant_view_expand,
                        ),
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = open,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(IvSpacing.BlockGap),
                    ) {
                        block.blocks.forEach {
                            InstantViewBlockContent(
                                it,
                                scale,
                                query,
                                mediaRepository,
                                onOpenUrl,
                                onOpenPeer
                            )
                        }
                    }
                }
            }
        }

        is InstantViewBlock.Photo -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InstantViewPhoto(
                cacheKey = block.cacheKey,
                width = block.width,
                height = block.height,
                mediaRepository = mediaRepository,
                openable = true,
            )
            block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
        }

        is InstantViewBlock.Document -> InstantViewDocument(
            block,
            scale,
            query,
            mediaRepository,
            onOpenUrl
        )

        is InstantViewBlock.Cover -> InstantViewCover(
            inner = block.block,
            scale = scale,
            query = query,
            mediaRepository = mediaRepository,
            onOpenUrl = onOpenUrl,
            onOpenPeer = onOpenPeer,
        )

        is InstantViewBlock.Embed -> InstantViewEmbed(
            block,
            scale,
            query,
            mediaRepository,
            onOpenUrl
        )

        is InstantViewBlock.EmbedPost -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(IvSpacing.CardCorner))
                .background(ivCardColor())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                block.photoCacheKey?.let {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                    ) {
                        InstantViewPhoto(it, 36, 36, mediaRepository)
                    }
                }
                Column {
                    Text(
                        text = block.author,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InstantViewPages.formatPublishedDate(block.date)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            block.blocks.forEach {
                InstantViewBlockContent(it, scale, query, mediaRepository, onOpenUrl, onOpenPeer)
            }
            block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
        }

        is InstantViewBlock.MediaGroup -> InstantViewMediaGroup(
            block = block,
            scale = scale,
            query = query,
            mediaRepository = mediaRepository,
            onOpenUrl = onOpenUrl,
            onOpenPeer = onOpenPeer,
        )

        is InstantViewBlock.Channel -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(IvSpacing.CardCorner))
                .background(ivCardColor())
                .clickable(role = Role.Button) { onOpenPeer(PeerId(block.peerId)) }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            ) {
                block.photoCacheKey?.let {
                    InstantViewPhoto(
                        it,
                        44,
                        44,
                        mediaRepository,
                        hero = true
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                block.username?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "@$it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is InstantViewBlock.Related -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = block.title?.text ?: stringResource(R.string.dialog_instant_view_related),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            block.articles.forEach { article ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(IvSpacing.CardCorner))
                        .background(ivCardColor())
                        .clickable(role = Role.Button) { onOpenUrl(article.url) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    article.photoCacheKey?.let {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            InstantViewPhoto(it, 64, 64, mediaRepository, hero = true)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = article.title ?: article.url,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = InstantViewPages.relatedSubtitle(article)
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        is InstantViewBlock.Map -> InstantViewMap(block, scale, query, mediaRepository, onOpenUrl)

        is InstantViewBlock.Math -> InstantViewMath(block.source, scale)

        is InstantViewBlock.Anchor -> Spacer(
            Modifier
                .height(1.dp)
                .fillMaxWidth()
        )

        InstantViewBlock.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IvSpacing.DividerHeight),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(1f / 3f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        is InstantViewBlock.Buttons -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.items.forEach { item ->
                val href = item.entities.firstNotNullOfOrNull { entity ->
                    entityHref(entity.kind, entity.url, item.text)
                }
                TextButton(onClick = { href?.let(onOpenUrl) }, enabled = href != null) {
                    Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        InstantViewBlock.Unsupported -> Text(
            text = stringResource(R.string.dialog_instant_view_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstantViewPreformatted(
    block: InstantViewBlock.Text,
    scale: Float,
    query: String,
    onOpenUrl: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ivCodeBackground())
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = IvSpacing.PreHorizontalPadding,
                vertical = IvSpacing.PreVerticalPadding,
            ),
    ) {
        InstantViewText(
            text = block.text,
            entities = block.entities,
            style = ivTextStyle("pre", 0, scale),
            query = query,
            onOpenUrl = onOpenUrl,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun ListMarker(
    item: InstantViewListItem,
    ordered: Boolean,
    fallbackNumber: String,
    scale: Float,
) {
    val gutter = Modifier.width(26.dp)
    if (item.checkbox) {
        Icon(
            imageVector = if (item.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            modifier = gutter
                .padding(top = 2.dp, end = 6.dp)
                .size(20.dp),
            tint = if (item.checked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        return
    }
    Text(
        text = if (ordered) item.number?.takeIf { it.isNotBlank() } ?: "$fallbackNumber." else "•",
        modifier = gutter.padding(end = 8.dp, top = 1.dp),
        style = ivBodyStyle(scale).copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}