package org.monogram.presentation.core.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@SuppressLint("LocalContextResourcesRead")
@Composable
fun CountryFlag(
    iso: String,
    size: Dp,
    modifier: Modifier = Modifier,
    flagEmoji: String = ""
) {
    val context = LocalContext.current
    var flagResId = remember(iso) {
        context.resources.getIdentifier(
            "flag_${iso.lowercase().replace("-", "_")}",
            "drawable",
            context.packageName
        )
    }

    if (iso == "FT")
        flagResId = remember(iso) {
            context.resources.getIdentifier(
                "ton",
                "drawable",
                context.packageName
            )
        }

    if (iso == "YL")
        flagResId = remember(iso) {
            context.resources.getIdentifier(
                "ic_app_logo",
                "drawable",
                context.packageName
            )
        }

    if (iso == "GO")
        flagResId = remember(iso) {
            context.resources.getIdentifier(
                "question",
                "drawable",
                context.packageName
            )
        }

    if (flagResId != 0) {
        Image(
            painter = painterResource(id = flagResId),
            contentDescription = iso,
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else if (flagEmoji.isNotEmpty()) {
        Text(flagEmoji, fontSize = (size.value * 0.75).sp)
    }
}
