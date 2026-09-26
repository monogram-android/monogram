package org.monogram.core.ui.components

import androidx.compose.runtime.compositionLocalOf

/** Whether animated media (emoji statuses, avatars, stickers, typing dots) may keep playing. */
val LocalMediaAnimationEnabled = compositionLocalOf { true }
