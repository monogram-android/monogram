package org.monogram.core.harmony

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HarmonyAccent {
    HarmonyBlue,
    DnaNeon,
}

data class HarmonyPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val glow: Color,
    val surfaceLight: Color,
    val surfaceDark: Color,
    val backgroundLight: Color,
    val backgroundDark: Color,
)

fun harmonyPalette(accent: HarmonyAccent): HarmonyPalette = when (accent) {
    HarmonyAccent.HarmonyBlue -> HarmonyPalette(
        primary = Color(0xFF0A59F7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E4FF),
        onPrimaryContainer = Color(0xFF001A53),
        secondary = Color(0xFF3D7BF0),
        glow = Color(0xFF6EA8FF),
        surfaceLight = Color(0xFFF7F8FA),
        surfaceDark = Color(0xFF0A1020),
        backgroundLight = Color(0xFFF7F8FA),
        backgroundDark = Color(0xFF070B14),
    )

    HarmonyAccent.DnaNeon -> HarmonyPalette(
        primary = Color(0xFF5AFF9E),
        onPrimary = Color(0xFF02090C),
        primaryContainer = Color(0xFF0D3B2A),
        onPrimaryContainer = Color(0xFFB9FFD8),
        secondary = Color(0xFF5ADCFF),
        glow = Color(0xFF5ADCFF),
        surfaceLight = Color(0xFFF2FAF6),
        surfaceDark = Color(0xFF0A1614),
        backgroundLight = Color(0xFFF7FBF9),
        backgroundDark = Color(0xFF02090C),
    )
}

object HarmonyTokens {
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
        val section = 48.dp
        val page = 64.dp
    }

    object Radius {
        val chip = 4.dp
        val control = 8.dp
        val card = 12.dp
        val panel = 16.dp
        val capsule = 24.dp
    }

    object Size {
        val controlHeight = 48.dp
        val secondaryControlHeight = 40.dp
        val tagHeight = 24.dp
        val avatar = 48.dp
    }

    object Elevation {
        val low = 2.dp
        val medium = 8.dp
        val high = 16.dp
        val modal = 24.dp
    }

    object Type {
        val caption = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
        val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
        val bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
        val subtitleSmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
        val subtitle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        val subtitleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
        val headline = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
        val display = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
    }

    val Shapes = Shapes(
        extraSmall = RoundedCornerShape(Radius.chip),
        small = RoundedCornerShape(Radius.control),
        medium = RoundedCornerShape(Radius.card),
        large = RoundedCornerShape(Radius.panel),
        extraLarge = RoundedCornerShape(Radius.capsule),
    )

    val Typography = Typography(
        displayLarge = Type.display,
        displayMedium = Type.headline,
        headlineLarge = Type.headline,
        headlineMedium = Type.subtitleLarge,
        titleLarge = Type.subtitle,
        titleMedium = Type.subtitleSmall,
        titleSmall = Type.bodyMedium,
        bodyLarge = Type.body,
        bodyMedium = Type.bodyMedium,
        bodySmall = Type.bodySmall,
        labelLarge = Type.body,
        labelMedium = Type.bodyMedium,
        labelSmall = Type.caption,
    )
}
