package org.monogram.presentation.settings.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.PrivacyValue
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.repository.PrivacyKey
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingContent(component: PrivacySettingComponent) {
    val state by component.state.subscribeAsState()

    val greenColor = Color(0xFF34A853)
    val redColor = Color(0xFFFF3B30)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("Who can add me to ${state.title.lowercase()}?")
                PrivacyOption(
                    title = "Everybody",
                    selected = state.selectedValue == PrivacyValue.EVERYBODY,
                    position = ItemPosition.TOP,
                    onClick = { component.onPrivacyValueChanged(PrivacyValue.EVERYBODY) }
                )
                PrivacyOption(
                    title = "My Contacts",
                    selected = state.selectedValue == PrivacyValue.MY_CONTACTS,
                    position = ItemPosition.MIDDLE,
                    onClick = { component.onPrivacyValueChanged(PrivacyValue.MY_CONTACTS) }
                )
                PrivacyOption(
                    title = "Nobody",
                    selected = state.selectedValue == PrivacyValue.NOBODY,
                    position = ItemPosition.BOTTOM,
                    onClick = { component.onPrivacyValueChanged(PrivacyValue.NOBODY) }
                )
            }

            if (state.privacyKey == PrivacyKey.PHONE_NUMBER) {
                item {
                    SectionHeader("Who can find me by my number?")
                    PrivacyOption(
                        title = "Everybody",
                        selected = state.searchSelectedValue == PrivacyValue.EVERYBODY,
                        position = ItemPosition.TOP,
                        onClick = { component.onSearchPrivacyValueChanged(PrivacyValue.EVERYBODY) }
                    )
                    PrivacyOption(
                        title = "My Contacts",
                        selected = state.searchSelectedValue == PrivacyValue.MY_CONTACTS,
                        position = ItemPosition.BOTTOM,
                        onClick = { component.onSearchPrivacyValueChanged(PrivacyValue.MY_CONTACTS) }
                    )
                    Text(
                        text = "Users who add your number to their contacts will see it on Telegram only if they are allowed to by the setting above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                    )
                }
            }

            item {
                SectionHeader("Add exceptions")
                val showAlways = state.selectedValue != PrivacyValue.EVERYBODY
                val showNever = state.selectedValue != PrivacyValue.NOBODY

                if (showAlways) {
                    val total = state.allowUsers.size + state.allowChats.size
                    SettingsTile(
                        icon = Icons.Rounded.Add,
                        title = "Always Allow",
                        subtitle = if (total > 0) "$total users/chats" else null,
                        iconColor = greenColor,
                        position = if (showNever) ItemPosition.TOP else ItemPosition.STANDALONE,
                        onClick = { component.onAddExceptionClicked(true) }
                    )
                }

                if (showNever) {
                    val total = state.disallowUsers.size + state.disallowChats.size
                    SettingsTile(
                        icon = Icons.Rounded.RemoveCircle,
                        title = "Never Allow",
                        subtitle = if (total > 0) "$total users/chats" else null,
                        iconColor = redColor,
                        position = if (showAlways) ItemPosition.BOTTOM else ItemPosition.STANDALONE,
                        onClick = { component.onAddExceptionClicked(false) }
                    )
                }
            }

            if (state.allowUsers.isNotEmpty() || state.allowChats.isNotEmpty()) {
                item {
                    SectionHeader("Always Allow")
                    val totalItems = state.allowUsers.size + state.allowChats.size
                    var currentIndex = 0

                    state.allowUsers.forEach { user ->
                        val position = when {
                            totalItems == 1 -> ItemPosition.STANDALONE
                            currentIndex == 0 -> ItemPosition.TOP
                            currentIndex == totalItems - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        ExceptionUserItem(
                            user = user,
                            position = position,
                            onClick = { component.onUserClicked(user.id) },
                            onRemove = { component.onRemoveUser(user.id, true) },
                            videoPlayerPool = component.videoPlayerPool
                        )
                        currentIndex++
                    }
                    state.allowChats.forEach { chat ->
                        val position = when {
                            totalItems == 1 -> ItemPosition.STANDALONE
                            currentIndex == 0 -> ItemPosition.TOP
                            currentIndex == totalItems - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        ExceptionChatItem(
                            chat = chat,
                            position = position,
                            onRemove = { component.onRemoveChat(chat.id, true) },
                            videoPlayerPool = component.videoPlayerPool
                        )
                        currentIndex++
                    }
                }
            }

            if (state.disallowUsers.isNotEmpty() || state.disallowChats.isNotEmpty()) {
                item {
                    SectionHeader("Never Allow")
                    val totalItems = state.disallowUsers.size + state.disallowChats.size
                    var currentIndex = 0

                    state.disallowUsers.forEach { user ->
                        val position = when {
                            totalItems == 1 -> ItemPosition.STANDALONE
                            currentIndex == 0 -> ItemPosition.TOP
                            currentIndex == totalItems - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        ExceptionUserItem(
                            user = user,
                            position = position,
                            onClick = { component.onUserClicked(user.id) },
                            onRemove = { component.onRemoveUser(user.id, false) },
                            videoPlayerPool = component.videoPlayerPool
                        )
                        currentIndex++
                    }
                    state.disallowChats.forEach { chat ->
                        val position = when {
                            totalItems == 1 -> ItemPosition.STANDALONE
                            currentIndex == 0 -> ItemPosition.TOP
                            currentIndex == totalItems - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        ExceptionChatItem(
                            chat = chat,
                            position = position,
                            onRemove = { component.onRemoveChat(chat.id, false) },
                            videoPlayerPool = component.videoPlayerPool
                        )
                        currentIndex++
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding()-16.dp)) }
        }
    }
}

@Composable
fun ExceptionUserItem(
    user: UserModel,
    position: ItemPosition,
    videoPlayerPool: VideoPlayerPool,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val displayName = remember(user) {
        buildString {
            if (user.firstName.isBlank()) {
                append("${user.id} (deleted)")
            } else {
                append(user.firstName)
                if (!user.lastName.isNullOrBlank()) {
                    append(" ")
                    append(user.lastName)
                }
            }
            if (!user.username.isNullOrBlank()) {
                append(" (@${user.username})")
            }
        }
    }

    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                path = user.avatarPath,
                name = user.firstName.ifBlank { "D" },
                size = 40.dp,
                isOnline = user.userStatus == UserStatusType.ONLINE,
                videoPlayerPool = videoPlayerPool
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = displayName, style = MaterialTheme.typography.titleMedium, fontSize = 16.sp)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove")
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}

@Composable
fun ExceptionChatItem(
    chat: ChatModel,
    position: ItemPosition,
    videoPlayerPool: VideoPlayerPool,
    onRemove: () -> Unit
) {
    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                path = chat.avatarPath,
                name = chat.title.take(1),
                size = 40.dp,
                videoPlayerPool = videoPlayerPool
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = chat.title, style = MaterialTheme.typography.titleMedium, fontSize = 16.sp)
                Text(
                    text = "Chat members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove")
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}

@Composable
fun PrivacyOption(
    title: String,
    selected: Boolean,
    position: ItemPosition,
    onClick: () -> Unit
) {
    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
