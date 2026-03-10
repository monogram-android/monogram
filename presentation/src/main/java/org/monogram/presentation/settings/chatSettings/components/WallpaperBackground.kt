package org.monogram.presentation.settings.chatSettings.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.monogram.domain.models.WallpaperModel
import java.io.File

@Composable
fun WallpaperBackground(
    wallpaper: WallpaperModel?,
    modifier: Modifier = Modifier,
    isBlurred: Boolean = false,
    isMoving: Boolean = false,
    blurIntensity: Int = 20,
    dimming: Int = 0,
    isGrayscale: Boolean = false,
    isChatSettings: Boolean = false
) {
    if (wallpaper == null) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surface))
        return
    }

    val activationSpec = remember {
        tween<Float>(durationMillis = 1000, easing = FastOutSlowInEasing)
    }

    val motionSpringSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 20f
        )
    }

    val fadeSpec = remember {
        tween<Float>(durationMillis = 600, easing = LinearEasing)
    }

    val animatedScale by animateFloatAsState(
        targetValue = if (isMoving) 1.1f else 1.0f,
        animationSpec = activationSpec,
        label = "scale"
    )

    val animatedBlur by animateFloatAsState(
        targetValue = if (isBlurred) blurIntensity.toFloat() else 0f,
        animationSpec = tween(600),
        label = "blur"
    )

    val animatedDimming by animateFloatAsState(
        targetValue = dimming / 100f,
        animationSpec = fadeSpec,
        label = "dimming"
    )

    val animatedSaturation by animateFloatAsState(
        targetValue = if (isGrayscale) 0f else 1f,
        animationSpec = tween(600),
        label = "saturation"
    )

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    var targetX by remember { mutableFloatStateOf(0f) }
    var targetY by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current

    DisposableEffect(isMoving) {
        if (isMoving) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event?.let {
                        val yRotationRate = it.values[0]
                        val xRotationRate = it.values[1]
                        targetX = (xRotationRate * 30f).coerceIn(-45f, 45f)
                        targetY = (yRotationRate * 30f).coerceIn(-45f, 45f)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            if (sensor != null) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }

            onDispose {
                if (sensor != null) sensorManager.unregisterListener(listener)
            }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(isMoving) {
        if (isMoving) {
            launch {
                snapshotFlow { targetX }.collectLatest { target ->
                    offsetX.animateTo(target, motionSpringSpec)
                }
            }
            launch {
                snapshotFlow { targetY }.collectLatest { target ->
                    offsetY.animateTo(target, motionSpringSpec)
                }
            }
        } else {
            launch { offsetX.animateTo(0f, activationSpec) }
            launch { offsetY.animateTo(0f, activationSpec) }
        }
    }

    Box(modifier = modifier) {
        val settings = wallpaper.settings

        val hasColors = remember(settings) {
            settings?.let {
                it.backgroundColor != null || it.secondBackgroundColor != null ||
                        it.thirdBackgroundColor != null || it.fourthBackgroundColor != null
            } ?: false
        }

        val isFullImage = remember(wallpaper) {
            !wallpaper.pattern && !wallpaper.slug.startsWith("emoji") && (wallpaper.documentId != 0L || wallpaper.slug == "built-in")
        }

        val isBackgroundDisabled = remember(isFullImage, hasColors) {
            isFullImage && !hasColors
        }

        val shouldShowBackground = !isBackgroundDisabled || isChatSettings

        if (shouldShowBackground) {
            val colors = remember(settings) {
                listOfNotNull(
                    settings?.backgroundColor?.let { Color(it or 0xFF000000.toInt()) },
                    settings?.secondBackgroundColor?.let { Color(it or 0xFF000000.toInt()) },
                    settings?.thirdBackgroundColor?.let { Color(it or 0xFF000000.toInt()) },
                    settings?.fourthBackgroundColor?.let { Color(it or 0xFF000000.toInt()) }
                )
            }

            val bgMod = Modifier.fillMaxSize()
            if (colors.isNotEmpty()) {
                if (colors.size == 1) {
                    Box(modifier = bgMod.background(colors[0]))
                } else {
                    val rotation = settings?.rotation ?: 0
                    Box(
                        modifier = bgMod.background(
                            Brush.linearGradient(
                                colors = colors,
                                start = Offset(0f, 0f),
                                end = when (rotation) {
                                    45 -> Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                                    90 -> Offset(Float.POSITIVE_INFINITY, 0f)
                                    135 -> Offset(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
                                    180 -> Offset(0f, Float.NEGATIVE_INFINITY)
                                    225 -> Offset(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                                    270 -> Offset(Float.NEGATIVE_INFINITY, 0f)
                                    315 -> Offset(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                                    else -> Offset(0f, Float.POSITIVE_INFINITY)
                                }
                            )
                        )
                    )
                }
            } else {
                Box(modifier = bgMod.background(MaterialTheme.colorScheme.surface))
            }
        }

        val imagePath = if (wallpaper.isDownloaded && !wallpaper.localPath.isNullOrEmpty()) {
            wallpaper.localPath
        } else {
            wallpaper.thumbnail?.localPath
        }

        if (imagePath != null && File(imagePath).exists()) {
            val file = File(imagePath)

            val colorFilter = remember(animatedSaturation) {
                if (animatedSaturation < 0.99f) {
                    val matrix = ColorMatrix().apply { setToSaturation(animatedSaturation) }
                    ColorFilter.colorMatrix(matrix)
                } else {
                    null
                }
            }

            val graphicsModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = offsetX.value
                    translationY = offsetY.value
                    rotationY = (offsetX.value / 60f)
                    rotationX = -(offsetY.value / 60f)
                }
                .let {
                    if (animatedBlur > 0f) it.blur((animatedBlur / 4f).dp) else it
                }

            if (wallpaper.pattern) {
                val intensity = (settings?.intensity ?: 50) / 100f
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    modifier = graphicsModifier.graphicsLayer { alpha = intensity },
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter
                )
            } else {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    modifier = graphicsModifier,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter
                )
            }
        } else if (wallpaper.slug.startsWith("emoji")) {
            // TODO: Implement rendering with gradient and emojis
            Log.d("WallpaperBackground", "Emoji wallpaper rendering not implemented for slug: ${wallpaper.slug}")
        } else if (!shouldShowBackground) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface))
        }

        // Dimming Overlay
        if (animatedDimming > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = animatedDimming }
                    .background(Color.Black)
            )
        }
    }
}