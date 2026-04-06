package org.monogram.presentation.features.chats.currentChat.editor.photo.casino

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlin.random.Random

suspend fun runCasinoSpin(
    startRotation: Float,
    onRotationFrame: (Float) -> Unit
): Float {
    val direction = if (Random.nextBoolean()) 1 else -1
    val extraTurns = Random.nextInt(4, 8) * 360f
    val extraOffset = Random.nextInt(25, 340).toFloat()
    val targetRotation = startRotation + direction * (extraTurns + extraOffset)
    val reboundRotation = targetRotation - direction * Random.nextInt(8, 20)
    val rotationAnimation = Animatable(startRotation)

    rotationAnimation.animateTo(
        targetValue = targetRotation,
        animationSpec = tween(
            durationMillis = 1850,
            easing = CubicBezierEasing(0.08f, 0.92f, 0.16f, 1f)
        )
    ) {
        onRotationFrame(value)
    }

    rotationAnimation.animateTo(
        targetValue = reboundRotation,
        animationSpec = tween(
            durationMillis = 180,
            easing = FastOutSlowInEasing
        )
    ) {
        onRotationFrame(value)
    }

    rotationAnimation.animateTo(
        targetValue = targetRotation,
        animationSpec = tween(
            durationMillis = 140,
            easing = FastOutSlowInEasing
        )
    ) {
        onRotationFrame(value)
    }

    return normalizeCasinoRotation(targetRotation)
}

fun normalizeCasinoRotation(value: Float): Float {
    var normalized = value % 360f
    if (normalized > 180f) normalized -= 360f
    if (normalized < -180f) normalized += 360f
    return normalized
}
