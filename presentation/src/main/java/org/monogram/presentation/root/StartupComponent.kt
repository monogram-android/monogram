package org.monogram.presentation.root

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import org.monogram.presentation.R

interface StartupComponent

class DefaultStartupComponent(
    context: AppComponentContext
) : StartupComponent, AppComponentContext by context

@Composable
fun StartupContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Loader()

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.app_name_monogram),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(R.string.startup_connecting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun Loader() {
    val infiniteTransition = rememberInfiniteTransition(label = "loader")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.primaryContainer

    Canvas(modifier = Modifier.size(64.dp)) {
        drawCircle(
            color = secondaryColor,
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        )

        drawArc(
            color = primaryColor,
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}