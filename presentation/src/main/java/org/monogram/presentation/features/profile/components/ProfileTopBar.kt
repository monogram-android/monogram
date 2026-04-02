package org.monogram.presentation.features.profile.components

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileTopBar(
    onBack: () -> Unit,
    progress: Float,
    title: String,
    userModel: UserModel?,
    chatModel: ChatModel?,
    isVerified: Boolean,
    isSponsor: Boolean,
    canSearch: Boolean = false,
    canShare: Boolean = false,
    canEdit: Boolean = false,
    canEditContact: Boolean = false,
    canReport: Boolean = false,
    canBlock: Boolean = false,
    isBlocked: Boolean = false,
    canDelete: Boolean = false,
    onSearch: () -> Unit = {},
    onShare: () -> Unit = {},
    onEdit: () -> Unit = {},
    onEditContact: () -> Unit = {},
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasMenuActions = canShare || canEdit || canEditContact || canReport || canBlock || canDelete
    val iconButtonShapes = ExpressiveDefaults.iconButtonShapes()

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
                            contentDescription = stringResource(R.string.cd_verified),
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (isSponsor) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = stringResource(R.string.cd_sponsor),
                            modifier = Modifier.size(22.dp),
                            tint = Color(0xFFE53935)
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
                    IconButton(onClick = onBack, shapes = iconButtonShapes) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = iconTint
                        )
                    }
                }
            },
            actions = {
                if (canSearch || hasMenuActions) {
                    Card(
                        modifier = Modifier.padding(8.dp),
                        shape = RoundedCornerShape(50),
                        colors = CardDefaults.cardColors(containerColor = buttonBackground)
                    ) {
                        Row {
                            if (canSearch) {
                                IconButton(onClick = onSearch, shapes = iconButtonShapes) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = stringResource(R.string.search_section_chats),
                                        tint = iconTint
                                    )
                                }
                            }
                            if (hasMenuActions) {
                                IconButton(onClick = { showMenu = true }, shapes = iconButtonShapes) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = iconTint)
                                }
                            }
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
                        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            initialScale = 0.8f,
                            transformOrigin = TransformOrigin(1f, 0f)
                        ),
                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            targetScale = 0.9f,
                            transformOrigin = TransformOrigin(1f, 0f)
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 56.dp, end = 16.dp)
                    ) {
                        ViewerSettingsDropdown {
                            if (canShare) {
                                MenuOptionRow(
                                    icon = Icons.Rounded.Share,
                                    title = stringResource(R.string.menu_share),
                                    onClick = {
                                        showMenu = false
                                        onShare()
                                    }
                                )
                            }
                            if (canEdit) {
                                MenuOptionRow(
                                    icon = Icons.Rounded.Edit,
                                    title = stringResource(R.string.menu_edit),
                                    onClick = {
                                        showMenu = false
                                        onEdit()
                                    }
                                )
                            }
                            if (canEditContact) {
                                MenuOptionRow(
                                    icon = Icons.Rounded.Edit,
                                    title = stringResource(R.string.contact_menu_edit_name),
                                    onClick = {
                                        showMenu = false
                                        onEditContact()
                                    }
                                )
                            }
                            if (canReport) {
                                MenuOptionRow(
                                    icon = Icons.Rounded.Report,
                                    title = stringResource(R.string.menu_report),
                                    onClick = {
                                        showMenu = false
                                        onReport()
                                    }
                                )
                            }
                            if (canBlock && userModel != null) {
                                MenuOptionRow(
                                    icon = if (isBlocked) Icons.Rounded.LockOpen else Icons.Rounded.Block,
                                    title = if (isBlocked) stringResource(R.string.privacy_unblock_action) else stringResource(
                                        R.string.menu_block_user
                                    ),
                                    textColor = MaterialTheme.colorScheme.error,
                                    iconTint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        showMenu = false
                                        onBlock()
                                    }
                                )
                            }
                            if (canDelete) {
                                MenuOptionRow(
                                    icon = Icons.Rounded.Delete,
                                    title = if (chatModel?.isGroup == true || chatModel?.isChannel == true) stringResource(
                                        R.string.menu_leave
                                    ) else stringResource(
                                        R.string.menu_delete_chat
                                    ),
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
}
