package org.monogram.presentation.settings.sessions

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.SessionModel
import org.monogram.domain.models.SessionType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition

/**
 * Map session model to its own color
 *
 * @param isPending `true` if this session in Pending state
 **/
internal fun SessionModel.toBrandColor(
    isPending: Boolean
): Color {
    if (isPending) return Color(0xFFF9AB00)
    if (isOculus) return Color(0xFF000000)
    if (isChrome) return Color(0xFF4285F4)

    return when (type) {
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

/**
 * Map session model to its own logo
 *
 * @param isPending `true` if this session in Pending state
 **/
@Composable
internal fun SessionModel.toLogo(
    isPending: Boolean
): Painter = when {
    isPending -> rememberVectorPainter(Icons.Rounded.Warning)
    isOculus -> painterResource(R.drawable.ic_oculus)
    isChrome -> painterResource(R.drawable.ic_chrome)

    type == SessionType.Android -> rememberVectorPainter(Icons.Rounded.Android)
    type in listOf(
        SessionType.Apple,
        SessionType.Mac,
        SessionType.Iphone,
        SessionType.Ipad
    ) -> painterResource(R.drawable.ic_apple)

    type == SessionType.Windows -> painterResource(R.drawable.ic_windows)
    type in listOf(SessionType.Linux, SessionType.Ubuntu) -> rememberVectorPainter(Icons.Rounded.Terminal)

    type == SessionType.Chrome -> painterResource(R.drawable.ic_chrome)
    type == SessionType.Xbox -> rememberVectorPainter(Icons.Rounded.VideogameAsset)

    type in listOf(
        SessionType.Firefox,
        SessionType.Safari,
        SessionType.Edge,
        SessionType.Opera,
        SessionType.Brave,
        SessionType.Vivaldi
    ) -> rememberVectorPainter(Icons.Rounded.Language)

    else -> rememberVectorPainter(Icons.Rounded.Laptop)
}

/**
 * Map session model to its own icon tint
 *
 * @param isPending `true` if this session in Pending state
 * @param fallback a fallback color to show
 **/
internal fun SessionModel.toIconTint(
    isPending: Boolean,
    fallback: Color
): Color {
    return when {
        isPending -> fallback
        isOculus -> Color.Unspecified
        isChrome -> Color.Unspecified
        type == SessionType.Chrome -> Color.Unspecified
        else -> fallback
    }
}

internal fun ItemPosition.toShape(): RoundedCornerShape {
    val cornerRadius = 24.dp
    return when (this) {
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
}