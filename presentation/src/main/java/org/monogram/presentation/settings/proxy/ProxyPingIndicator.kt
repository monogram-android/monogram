package org.monogram.presentation.settings.proxy

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.R

@Composable
fun ProxyPingIndicator(
    ping: Long?,
    isChecking: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isChecking && ping == null) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        if (isChecking) {
            val infiniteTransition = rememberInfiniteTransition(label = "ping")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.outline, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.proxy_checking),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        } else {
            val (color, text) = when {
                ping == null || ping == -1L -> Color(0xFFEA4335) to stringResource(R.string.proxy_offline)
                ping < 300 -> Color(0xFF34A853) to stringResource(R.string.proxy_ping_format, ping)
                ping < 800 -> Color(0xFFFBBC04) to stringResource(R.string.proxy_ping_format, ping)
                else -> Color(0xFFEA4335) to stringResource(R.string.proxy_ping_format, ping)
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                fontSize = 11.sp
            )
        }
    }
}
