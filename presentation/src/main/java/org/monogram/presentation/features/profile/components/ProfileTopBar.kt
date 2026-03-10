package org.monogram.presentation.features.profile.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopBar(
    onBack: () -> Unit,
    progress: Float,
    title: String,
    userModel: UserModel?,
    chatModel: ChatModel?,
    isVerified: Boolean,
    onSearch: () -> Unit = {},
    onShare: () -> Unit = {},
    onEdit: () -> Unit = {},
    onBlock: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    val iconTint = lerp(
        start = MaterialTheme.colorScheme.onSurface,
        stop = MaterialTheme.colorScheme.onSurface,
        fraction = progress
    )

    val buttonBackground = MaterialTheme.colorScheme.background.copy(
        alpha = 0.75f * progress
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(1f - progress),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isVerified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Verified",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    userModel?.let { user ->
                        if (!user.statusEmojiPath.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StickerImage(
                                path = user.statusEmojiPath,
                                modifier = Modifier.size(22.dp),
                                animate = false
                            )
                        } else if (user.isPremium) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFF31A6FD)
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                Card(
                    modifier = Modifier.padding(8.dp),
                    shape = RoundedCornerShape(50),
                    colors = CardDefaults.cardColors(containerColor = buttonBackground)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = iconTint
                        )
                    }
                }
            },
            actions = {
                Card(
                    modifier = Modifier.padding(8.dp),
                    shape = RoundedCornerShape(50),
                    colors = CardDefaults.cardColors(containerColor = buttonBackground)
                ) {
                    Row {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search", tint = iconTint)
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = iconTint)
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            )
        )

        if (showMenu) {
            Popup(
                onDismissRequest = { showMenu = false },
                properties = PopupProperties(focusable = true)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showMenu = false }
                ) {
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { isVisible = true }

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(150)) + scaleIn(
                            animationSpec = spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMedium
                            ),
                            initialScale = 0.8f,
                            transformOrigin = TransformOrigin(1f, 0f)
                        ),
                        exit = fadeOut(tween(150)) + scaleOut(
                            animationSpec = tween(150),
                            targetScale = 0.9f,
                            transformOrigin = TransformOrigin(1f, 0f)
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 56.dp, end = 16.dp)
                    ) {
                        ViewerSettingsDropdown {
                            MenuOptionRow(
                                icon = Icons.Rounded.Share,
                                title = "Share",
                                onClick = {
                                    showMenu = false
                                    onShare()
                                }
                            )
                            MenuOptionRow(
                                icon = Icons.Rounded.Edit,
                                title = "Edit",
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                }
                            )
                            if (userModel != null) {
                                MenuOptionRow(
                                    icon = Icons.Rounded.Block,
                                    title = "Block User",
                                    textColor = MaterialTheme.colorScheme.error,
                                    iconTint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        showMenu = false
                                        onBlock()
                                    }
                                )
                            }
                            MenuOptionRow(
                                icon = Icons.Rounded.Delete,
                                title = if (chatModel?.isGroup == true || chatModel?.isChannel == true) "Leave" else "Delete Chat",
                                textColor = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.error,
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
