package org.monogram.presentation.features.auth.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.repository.AuthUiStatus
import org.monogram.presentation.R

@Composable
fun AuthStatusMessage(
    uiStatus: AuthUiStatus,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = uiStatus !is AuthUiStatus.Idle,
        modifier = modifier.fillMaxWidth(),
        enter = slideInVertically(
            initialOffsetY = { -it / 2 },
            animationSpec = spring(stiffness = Spring.StiffnessLow)
        ) + fadeIn() + scaleIn(
            initialScale = 0.96f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        ),
        exit = slideOutVertically(
            targetOffsetY = { -it / 3 },
            animationSpec = spring(stiffness = Spring.StiffnessMedium)
        ) + fadeOut() + scaleOut(targetScale = 0.98f)
    ) {
        val targetAlpha by animateFloatAsState(
            targetValue = if (uiStatus is AuthUiStatus.NetworkError) 1f else 0.96f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "auth_status_alpha"
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = targetAlpha },
            shape = RoundedCornerShape(20.dp),
            color = containerColor(uiStatus)
        ) {
            AnimatedContent(
                targetState = uiStatus,
                transitionSpec = {
                    (slideInVertically { it / 3 } + fadeIn()).togetherWith(
                        slideOutVertically { -it / 3 } + fadeOut()
                    ).using(SizeTransform(clip = false))
                },
                label = "auth_status_content"
            ) { status ->
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(messageRes(status)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = contentColor(status)
                    )
                }
            }
        }
    }
}

private fun messageRes(uiStatus: AuthUiStatus): Int = when (uiStatus) {
    is AuthUiStatus.Submitting -> R.string.auth_connecting_to_telegram
    is AuthUiStatus.SlowNetwork -> R.string.auth_connection_taking_longer
    is AuthUiStatus.NetworkError -> R.string.auth_network_unreachable_error
    AuthUiStatus.Idle -> R.string.auth_connecting_to_telegram
}

@Composable
private fun containerColor(uiStatus: AuthUiStatus) = when (uiStatus) {
    is AuthUiStatus.NetworkError -> MaterialTheme.colorScheme.errorContainer
    is AuthUiStatus.SlowNetwork -> MaterialTheme.colorScheme.secondaryContainer
    is AuthUiStatus.Submitting -> MaterialTheme.colorScheme.surfaceContainerHigh
    AuthUiStatus.Idle -> MaterialTheme.colorScheme.surface
}

@Composable
private fun contentColor(uiStatus: AuthUiStatus) = when (uiStatus) {
    is AuthUiStatus.NetworkError -> MaterialTheme.colorScheme.onErrorContainer
    is AuthUiStatus.SlowNetwork -> MaterialTheme.colorScheme.onSecondaryContainer
    is AuthUiStatus.Submitting -> MaterialTheme.colorScheme.onSurface
    AuthUiStatus.Idle -> MaterialTheme.colorScheme.onSurface
}
