package org.monogram.presentation.settings.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.presentation.R
import org.monogram.domain.models.SessionModel
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.spacer.WidthSpacer
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.toShortRelativeDate

@Composable
internal fun SessionItem(
    session: SessionModel,
    modifier: Modifier = Modifier,
    isPending: Boolean = false,
    position: ItemPosition = ItemPosition.STANDALONE,
    onTerminate: (() -> Unit)?
) {
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = position.toShape(),
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformIcon(
                session = session,
                isPending = isPending,
            )

            WidthSpacer(16.dp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                PlatformName(
                    session = session,
                    isPending = isPending,
                )

                Text(
                    text = if (isPending) "${session.deviceModel} • ${session.location}"
                    else "${session.applicationName} ${session.applicationVersion} • ${session.platform}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = if (isPending) "${stringResource(R.string.sessions_unconfirmed)} • ${session.lastActiveDate.toShortRelativeDate(timeFormat)}"
                    else "${session.location} •  ${session.lastActiveDate.toShortRelativeDate(timeFormat)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            if (onTerminate != null) {
                ExitButton(onClick = onTerminate)
            }
        }
    }
}

@Composable
private fun PlatformIcon(
    session: SessionModel,
    isPending: Boolean,
) {
    val brandColor = remember { session.toBrandColor(isPending) }
    val iconPainter = session.toLogo(isPending)

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = brandColor.copy(alpha = 0.15f), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = session.toIconTint(isPending = isPending, fallback = brandColor)
        )
    }
}

@Composable
private fun PlatformName(
    session: SessionModel,
    isPending: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (isPending) stringResource(R.string.sessions_login_attempt) else session.deviceModel,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 18.sp,
            fontWeight = if (isPending) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (session.isOfficial && !isPending) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = stringResource(R.string.sticker_official),
                modifier = Modifier.size(16.dp),
                tint = Color(0xFF31A6FD)
            )
        }
    }
}

@Composable
private fun ExitButton(
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 8.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Logout,
            contentDescription = stringResource(R.string.sessions_terminate_action),
            tint = MaterialTheme.colorScheme.error
        )
    }
}