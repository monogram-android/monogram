package org.monogram.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.R
import org.monogram.core.ui.loading.MonogramLinearProgress

enum class AppSyncStatus {
    Hidden,
    Connecting,
    Syncing,
    FromCache,
    LoadingMore,
}

fun AppSyncStatus.showsProgress(): Boolean = when (this) {
    AppSyncStatus.Connecting,
    AppSyncStatus.Syncing,
    AppSyncStatus.LoadingMore,
    -> true
    AppSyncStatus.Hidden,
    AppSyncStatus.FromCache,
    -> false
}

@Composable
fun AppSyncStatus.uiLabel(): String? = when (this) {
    AppSyncStatus.Hidden -> null
    AppSyncStatus.Connecting -> stringResource(R.string.status_connecting)
    AppSyncStatus.Syncing -> stringResource(R.string.status_syncing)
    AppSyncStatus.FromCache -> stringResource(R.string.status_from_cache)
    AppSyncStatus.LoadingMore -> stringResource(R.string.status_loading_more)
}

@Composable
fun AppBarSyncTitle(
    idleTitle: String,
    sync: AppSyncStatus,
    modifier: Modifier = Modifier,
    replaceTitle: Boolean = true,
) {
    val status = sync.uiLabel()?.takeIf { sync != AppSyncStatus.FromCache }
    val title = if (replaceTitle) status ?: idleTitle else idleTitle
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (replaceTitle && status != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.semantics {
                contentDescription = listOfNotNull(title, status.takeIf { !replaceTitle }).joinToString(" ")
            },
        )
        if (!replaceTitle && status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AppSyncProgress(
    sync: AppSyncStatus,
    modifier: Modifier = Modifier,
) {
    MonogramLinearProgress(
        visible = sync.showsProgress(),
        modifier = modifier.fillMaxWidth(),
        height = 3.dp,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        status = sync.uiLabel(),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppStatusBanner(
    sync: AppSyncStatus,
    error: TelegramError?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var floodSeconds by remember(error) {
        mutableIntStateOf(error?.retryAfterSeconds ?: 0)
    }
    LaunchedEffect(error) {
        while (floodSeconds > 0) {
            kotlinx.coroutines.delay(1_000)
            floodSeconds--
        }
    }
    AppStatusBanner(
        text = error?.toUiMessage(floodSeconds),
        failed = error != null,
        progress = error?.kind == TelegramError.Kind.FileReference,
        onRetry = onRetry.takeIf {
            error != null &&
                (error.kind != TelegramError.Kind.Flood || floodSeconds <= 0)
        },
        modifier = modifier,
    )
    if (error == null) {
        AppSyncProgress(sync = sync, modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppStatusBanner(
    text: String?,
    failed: Boolean,
    progress: Boolean,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        modifier = modifier.fillMaxWidth(),
        enter = fadeIn() + expandVertically(clip = false),
        exit = fadeOut() + shrinkVertically(clip = false),
    ) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (failed) scheme.errorContainer else scheme.surfaceContainerHigh,
            contentColor = if (failed) scheme.onErrorContainer else scheme.onSurface,
        ) {
            Column {
                if (progress) {
                    MonogramLinearProgress(
                        modifier = Modifier.fillMaxWidth(),
                        height = 3.dp,
                        color = if (failed) scheme.error else scheme.primary,
                        trackColor = scheme.surfaceContainerHighest,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = text.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (failed && onRetry != null) {
                        TextButton(
                            onClick = onRetry,
                            shapes = ExpressiveDefaults.buttonShapesFor(ButtonDefaults.MinHeight),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = scheme.onErrorContainer,
                            ),
                        ) {
                            Text(stringResource(R.string.status_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelegramError.toUiMessage(remainingFloodSeconds: Int? = null): String = when (kind) {
    TelegramError.Kind.Flood -> if ((remainingFloodSeconds ?: retryAfterSeconds ?: argument ?: 0) > 0) {
        stringResource(
            R.string.status_flood_wait,
            remainingFloodSeconds ?: retryAfterSeconds ?: argument ?: 0,
        )
    } else {
        stringResource(R.string.status_flood_wait_expired)
    }
    TelegramError.Kind.Session -> stringResource(R.string.status_session)
    TelegramError.Kind.Network -> stringResource(R.string.status_network)
    TelegramError.Kind.FileReference -> stringResource(R.string.status_file_reference)
    TelegramError.Kind.Privacy -> stringResource(R.string.status_privacy)
    TelegramError.Kind.Peer -> stringResource(R.string.status_peer)
    TelegramError.Kind.Auth,
    TelegramError.Kind.Media,
    TelegramError.Kind.SeeOther,
    TelegramError.Kind.NotFound,
    TelegramError.Kind.NotAcceptable,
    TelegramError.Kind.Internal,
    TelegramError.Kind.Generic,
    -> stringResource(R.string.status_generic)
}
