package org.monogram.presentation.features.viewers.components

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.R
import java.util.*

@Composable
fun ViewerTopBar(
    onBack: () -> Unit,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActionActive: Boolean = false,
    onLockClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.8f),
                        Color.Black.copy(alpha = 0.4f),
                        Color.Transparent
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_back),
                    tint = Color.White
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onLockClick != null) {
                    IconButton(
                        onClick = onLockClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LockOpen,
                            contentDescription = stringResource(R.string.settings_lock_controls)
                        )
                    }
                }

                IconButton(
                    onClick = onActionClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isActionActive) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.viewer_options)
                    )
                }
            }
        }
    }
}

@Composable
fun ViewerCaption(
    caption: String,
    modifier: Modifier = Modifier,
    showGradient: Boolean = true
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showGradient) {
                    Modifier
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Black.copy(alpha = 0.9f)
                                )
                            )
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(top = 32.dp)
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .animateContentSize(),
            color = Color.Black.copy(alpha = 0.4f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = caption,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun ViewerSettingsDropdown(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 6.dp,
        shadowElevation = 2.dp,
        modifier = modifier
            .width(260.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
fun SeekFeedback(visible: Boolean, isRewind: Boolean, seekDuration: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isRewind) Icons.Rounded.Replay10 else Icons.Rounded.Forward10,
                null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = if (isRewind) {
                    stringResource(R.string.viewer_seek_rewind_format, seekDuration)
                } else {
                    stringResource(R.string.viewer_seek_forward_format, seekDuration)
                },
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun GestureOverlay(visible: Boolean, icon: ImageVector?, text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                if (text != null) Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun currentTime(): String =
    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
