package org.monogram.root

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

internal data class FoldBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal enum class FoldLayoutMode {
    NONE,
    VERTICAL_BOOK,
    HORIZONTAL_TABLETOP,
}

internal data class FoldLayout(
    val mode: FoldLayoutMode = FoldLayoutMode.NONE,
    val hinge: FoldBounds? = null,
) {
    val isSeparating: Boolean
        get() = mode != FoldLayoutMode.NONE && hinge != null
}

internal fun foldLayout(
    bounds: FoldBounds?,
    isSeparating: Boolean,
    isVertical: Boolean,
    windowWidthPx: Int,
    windowHeightPx: Int,
): FoldLayout {
    if (!isSeparating || bounds == null || bounds.width <= 0 || bounds.height <= 0 ||
        windowWidthPx <= 0 || windowHeightPx <= 0) {
        return FoldLayout()
    }

    val withinWindow = bounds.left >= 0 && bounds.top >= 0 &&
        bounds.right <= windowWidthPx && bounds.bottom <= windowHeightPx
    if (!withinWindow) return FoldLayout()

    return if (isVertical && bounds.left > 0 && bounds.right < windowWidthPx) {
        FoldLayout(FoldLayoutMode.VERTICAL_BOOK, bounds)
    } else if (!isVertical && bounds.top > 0 && bounds.bottom < windowHeightPx) {
        FoldLayout(FoldLayoutMode.HORIZONTAL_TABLETOP, bounds)
    } else {
        FoldLayout()
    }
}

@Composable
internal fun rememberFoldLayout(): FoldLayout {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return FoldLayout()
    val info by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.collectAsStateWithLifecycle(initialValue = WindowLayoutInfo(emptyList()))
    val feature = info.displayFeatures
        .filterIsInstance<FoldingFeature>()
        .firstOrNull { it.isSeparating }
    val rawBounds = feature?.bounds
    val rotatedLandscape = activity.display.rotation == android.view.Surface.ROTATION_90 ||
        activity.display.rotation == android.view.Surface.ROTATION_270
    val isVerticalFeature = feature?.orientation == FoldingFeature.Orientation.VERTICAL
    val bounds = rawBounds?.let {
        if (rotatedLandscape && isVerticalFeature && activity.window.decorView.width > activity.window.decorView.height) {
            FoldBounds(0, it.left, activity.window.decorView.width, it.right)
        } else {
            FoldBounds(it.left, it.top, it.right, it.bottom)
        }
    }
    return foldLayout(
        bounds = bounds,
        isSeparating = feature?.isSeparating == true,
        isVertical = isVerticalFeature && !rotatedLandscape,
        windowWidthPx = activity.window.decorView.width,
        windowHeightPx = activity.window.decorView.height,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
