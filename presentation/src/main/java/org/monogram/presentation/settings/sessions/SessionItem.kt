package org.monogram.presentation.settings.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.SessionModel
import org.monogram.domain.models.SessionType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SessionItem(
    session: SessionModel,
    isPending: Boolean = false,
    position: ItemPosition = ItemPosition.STANDALONE,
    onTerminate: (() -> Unit)?
) {
    val isOculus = remember(session.type, session.deviceModel) {
        session.type == SessionType.Android && session.deviceModel.contains("OculusQuest", ignoreCase = true)
    }

    val isChrome = remember(session.type, session.deviceModel) {
        session.type == SessionType.Chrome || session.deviceModel.contains("Chrome", ignoreCase = true)
    }

    val brandColor = remember(session.type, isPending, isOculus) {
        if (isPending) return@remember Color(0xFFF9AB00)
        if (isOculus) return@remember Color(0xFF000000)
        if (isChrome) return@remember Color(0xFF4285F4)

        when (session.type) {
            SessionType.Android -> Color(0xFF34A853)
            SessionType.Linux, SessionType.Ubuntu -> Color(0xFFFCC624)
            SessionType.Apple, SessionType.Mac, SessionType.Iphone, SessionType.Ipad -> Color(0xFF000000)
            SessionType.Windows -> Color(0xFF0078D7)
            SessionType.Chrome -> Color(0xFF4285F4)
            SessionType.Edge -> Color(0xFF0078D7)
            SessionType.Firefox -> Color(0xFFE66000)
            SessionType.Safari -> Color(0xFF007AFF)
            SessionType.Opera -> Color(0xFFFF1B2D)
            SessionType.Vivaldi -> Color(0xFFEF3939)
            SessionType.Brave -> Color(0xFFFF1B2D)
            SessionType.Xbox -> Color(0xFF107C10)
            else -> Color(0xFF4285F4)
        }
    }

    val iconPainter: Painter = when {
        isPending -> rememberVectorPainter(Icons.Rounded.Warning)
        isOculus -> painterResource(R.drawable.ic_oculus)
        isChrome -> painterResource(R.drawable.ic_chrome)

        session.type == SessionType.Android -> rememberVectorPainter(Icons.Rounded.Android)
        session.type in listOf(
            SessionType.Apple,
            SessionType.Mac,
            SessionType.Iphone,
            SessionType.Ipad
        ) -> painterResource(R.drawable.ic_apple)

        session.type == SessionType.Windows -> painterResource(R.drawable.ic_windows)
        session.type in listOf(SessionType.Linux, SessionType.Ubuntu) -> rememberVectorPainter(Icons.Rounded.Terminal)

        session.type == SessionType.Chrome -> painterResource(R.drawable.ic_chrome)
        session.type == SessionType.Xbox -> rememberVectorPainter(Icons.Rounded.VideogameAsset)

        session.type in listOf(
            SessionType.Firefox,
            SessionType.Safari,
            SessionType.Edge,
            SessionType.Opera,
            SessionType.Brave,
            SessionType.Vivaldi
        ) -> rememberVectorPainter(Icons.Rounded.Language)

        else -> rememberVectorPainter(Icons.Rounded.Laptop)
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
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = brandColor.copy(alpha = 0.15f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val iconTint = when {
                    isPending -> brandColor
                    isOculus -> Color.Unspecified
                    isChrome -> Color.Unspecified
                    session.type == SessionType.Chrome -> Color.Unspecified
                    else -> brandColor
                }

                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconTint
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isPending) "Попытка входа" else session.deviceModel,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 18.sp,
                        fontWeight = if (isPending) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (session.isOfficial && !isPending) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Official",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF31A6FD)
                        )
                    }
                }

                Text(
                    text = if (isPending) "${session.deviceModel} • ${session.location}"
                    else "${session.applicationName} ${session.applicationVersion} • ${session.platform}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = if (isPending) "Не подтверждено • ${convertTimestampToTimeLegacy(session.lastActiveDate.toLong())}"
                    else "${session.location} • ${convertTimestampToTimeLegacy(session.lastActiveDate.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            if (onTerminate != null) {
                IconButton(
                    onClick = onTerminate,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = "Terminate session",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun convertTimestampToTimeLegacy(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return format.format(date)
}