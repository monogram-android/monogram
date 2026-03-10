package org.monogram.presentation.features.webapp.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.monogram.presentation.features.webapp.MainButtonState
import org.monogram.presentation.features.webapp.SecondaryButtonState

@Composable
fun MainButton(
    state: MainButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    defaultColor: Color = MaterialTheme.colorScheme.primary,
    defaultTextColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shine")
    val shineOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shineOffset"
    )

    Button(
        onClick = onClick,
        modifier = modifier.then(
            if (state.hasShineEffect) {
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .drawWithContent {
                        drawContent()
                        val shineWidth = size.width * 0.5f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.25f),
                                    Color.Transparent,
                                ),
                                start = Offset(x = shineOffset * size.width - shineWidth, y = 0f),
                                end = Offset(x = shineOffset * size.width, y = size.height)
                            )
                        )
                    }
            } else Modifier
        ),
        enabled = state.isActive,
        colors = ButtonDefaults.buttonColors(
            containerColor = state.color ?: defaultColor,
            contentColor = state.textColor ?: defaultTextColor
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        if (state.isProgressVisible) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = state.textColor ?: defaultTextColor,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(state.text)
    }
}

@Composable
fun SecondaryButton(
    state: SecondaryButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    defaultColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    defaultTextColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = state.isActive,
        colors = ButtonDefaults.buttonColors(
            containerColor = state.color ?: defaultColor,
            contentColor = state.textColor ?: defaultTextColor
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        if (state.isProgressVisible) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = state.textColor ?: defaultTextColor,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(state.text)
    }
}
