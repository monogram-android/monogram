package org.monogram.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsTile
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsComponent
import org.monogram.feature.settings.SettingsPage
import java.io.File
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Star

private const val SPONSOR_BOOSTY_URL = "https://boosty.to/monogram"

internal fun LazyListScope.homeItems(
    component: SettingsComponent,
    stateTitle: String,
    stateSubtitle: String,
    avatarFile: File?,
    appVersion: String,
    buildStamp: String,
    onOpen: (SettingsPage) -> Unit,
    onLogout: () -> Unit,
    loading: Boolean,
    sponsorIds: Set<Long>,
    selfPeerId: Long?,
) {
    item {
        AccountHeader(
            title = stateTitle,
            subtitle = stateSubtitle,
            avatarFile = avatarFile,
            onClick = component::onOpenProfile,
        )
    }
    item { SectionHeader(stringResource(R.string.settings_section_settings)) }
    item {
        SettingsTile(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.settings_chat),
            subtitle = stringResource(R.string.settings_chat_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.TOP,
            onClick = { onOpen(SettingsPage.Appearance) },
            trailingContent = { Chevron() },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Folder,
            title = stringResource(R.string.settings_folders),
            subtitle = stringResource(R.string.settings_folders_sub),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.MIDDLE,
            onClick = { onOpen(SettingsPage.Folders) },
            trailingContent = { Chevron() },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications),
            subtitle = stringResource(R.string.settings_notifications_sub),
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.MIDDLE,
            onClick = { onOpen(SettingsPage.Notifications) },
            trailingContent = { Chevron() },
        )
    }
    item {
        SettingsTile(
            icon = Icons.Outlined.Storage,
            title = stringResource(R.string.settings_data),
            subtitle = stringResource(R.string.settings_data_sub),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.BOTTOM,
            onClick = { onOpen(SettingsPage.Data) },
            trailingContent = { Chevron() },
        )
    }
    item { SectionHeader(stringResource(R.string.settings_section_sponsors)) }
    item {
        val uriHandler = LocalUriHandler.current
        val selfIsSponsor = selfPeerId != null && selfPeerId in sponsorIds
        SettingsTile(
            icon = Icons.Outlined.Favorite,
            title = stringResource(R.string.settings_sponsors),
            subtitle = stringResource(R.string.settings_sponsors_count, sponsorIds.size),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = if (selfIsSponsor) ItemPosition.STANDALONE else ItemPosition.TOP,
            onClick = { uriHandler.openUri(SPONSOR_BOOSTY_URL) },
            trailingContent = { Chevron() },
        )
    }
    if (selfPeerId == null || selfPeerId !in sponsorIds) {
        item {
            val uriHandler = LocalUriHandler.current
            SettingsTile(
                icon = Icons.Outlined.Star,
                title = stringResource(R.string.settings_sponsor_promo),
                subtitle = stringResource(R.string.settings_sponsor_promo_sub),
                iconColor = MaterialTheme.colorScheme.primary,
                position = ItemPosition.BOTTOM,
                onClick = { uriHandler.openUri(SPONSOR_BOOSTY_URL) },
                trailingContent = { Chevron() },
            )
        }
    }
    item { SectionHeader(stringResource(R.string.settings_section_help)) }
    item {
        val uri = LocalUriHandler.current
        SettingsTile(
            icon = Icons.Outlined.Campaign,
            title = stringResource(R.string.settings_monogram_channel),
            subtitle = stringResource(R.string.settings_monogram_channel_sub),
            iconColor = MaterialTheme.colorScheme.tertiary,
            position = ItemPosition.TOP,
            onClick = { uri.openUri("https://t.me/monogram_android") },
            trailingContent = { Chevron() },
        )
    }
    item {
        val uri = LocalUriHandler.current
        SettingsTile(
            icon = Icons.AutoMirrored.Outlined.Chat,
            title = stringResource(R.string.settings_monogram_chat),
            subtitle = stringResource(R.string.settings_monogram_chat_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.MIDDLE,
            onClick = { uri.openUri("https://t.me/monogram_discuss") },
            trailingContent = { Chevron() },
        )
    }
    item {
        val uri = LocalUriHandler.current
        SettingsTile(
            icon = Icons.AutoMirrored.Outlined.Help,
            title = stringResource(R.string.settings_faq),
            subtitle = stringResource(R.string.settings_faq_sub),
            iconColor = MaterialTheme.colorScheme.secondary,
            position = ItemPosition.MIDDLE,
            onClick = { uri.openUri("https://telegram.org/faq") },
            trailingContent = { Chevron() },
        )
    }
    item {
        val uri = LocalUriHandler.current
        SettingsTile(
            icon = Icons.AutoMirrored.Outlined.Chat,
            title = stringResource(R.string.settings_ask),
            subtitle = stringResource(R.string.settings_ask_sub),
            iconColor = MaterialTheme.colorScheme.primary,
            position = ItemPosition.BOTTOM,
            onClick = { uri.openUri("https://telegram.org/support") },
            trailingContent = { Chevron() },
        )
    }
    item { SectionHeader(stringResource(R.string.settings_section_session)) }
    item {
        SettingsTile(
            icon = Icons.AutoMirrored.Outlined.Logout,
            title = stringResource(R.string.settings_logout),
            iconColor = MaterialTheme.colorScheme.error,
            titleColor = MaterialTheme.colorScheme.error,
            position = ItemPosition.STANDALONE,
            enabled = !loading,
            onClick = onLogout,
        )
    }
    item {
        Text(
            text = "${stringResource(R.string.settings_about)} $appVersion\n$buildStamp",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable

private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccountHeader(
    title: String,
    subtitle: String,
    avatarFile: File?,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            supportingContent = {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingContent = {
                PeerAvatar(title = title, size = 64.dp, imageFile = avatarFile)
            },
            trailingContent = { Chevron() },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
        }
    }
}

// The signed-in account has no presence to show: Telegram reports its own status too.
@Composable
internal fun profileSubtitle(username: String?): String {
    val handle = username?.takeIf { it.isNotBlank() }?.let { "@$it" }
    return handle ?: stringResource(R.string.settings_account_loading)
}
