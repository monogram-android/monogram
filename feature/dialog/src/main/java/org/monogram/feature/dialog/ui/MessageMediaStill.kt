package org.monogram.feature.dialog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import org.monogram.core.ui.components.MonogramPlaceholder
import org.monogram.core.ui.loading.MonogramMediaLoadingOverlay
import org.monogram.feature.dialog.R
import java.io.File

@Composable
internal fun MediaProgressOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    bytes: Long = 0L,
    total: Long? = null,
    uploading: Boolean = false,
) {
    val fraction = downloadProgressFraction(bytes, total)
    MonogramMediaLoadingOverlay(
        visible = visible,
        modifier = modifier,
        progress = fraction?.let { { fraction } },
        size = 36.dp,
        scrim = Color.Black.copy(alpha = 0.28f),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.25f),
        status = if (uploading) stringResource(R.string.dialog_media_uploading) else null,
    )
}

@Composable
internal fun ProgressiveStill(
    thumb: File?,
    image: File?,
    contentDescription: String?,
    contentScale: ContentScale,
    failed: Boolean,
    failedText: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val context = LocalContext.current
    val sharp = image?.takeIf { stillImageFile(it) }
    val base = thumb?.takeIf { stillImageFile(it) }
    val hasFile = base != null || sharp != null
    val blurBase = base != null && base != sharp
    val baseRequest = remember(base, blurBase, context) {
        base?.let { localStillRequest(context, it, crossfade = 0, blur = blurBase) }
    }
    val sharpRequest = remember(sharp, base, context) {
        sharp?.let { localStillRequest(context, it, crossfade = if (base != null && base != sharp) 220 else 0) }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!hasFile && !failed) {
            MonogramPlaceholder(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (baseRequest != null && base != sharp) {
            AsyncImage(
                model = baseRequest,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (sharpRequest != null) {
            AsyncImage(
                model = sharpRequest,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        MediaProgressOverlay(visible = loading && sharp == null && hasFile)
        if (failed && base == null && sharp == null) {
            Text(
                text = failedText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun MediaTapModifier(
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    role: Role? = null,
): Modifier = if (onLongPress == null) {
    Modifier.clickable(role = role, onClick = onTap)
} else {
    Modifier.combinedClickable(
        role = role,
        onClick = onTap,
        onLongClick = onLongPress,
    )
}

private fun localStillRequest(
    context: android.content.Context,
    file: File,
    crossfade: Int,
    blur: Boolean = false,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey(if (blur) "${file.absolutePath}:preview-blur" else file.absolutePath)
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(crossfade)
    if (blur) {
        builder.size(128).transformations(PreviewBlurTransformation())
    }
    return builder.build()
}
