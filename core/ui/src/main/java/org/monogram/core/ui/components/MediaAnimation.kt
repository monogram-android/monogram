package org.monogram.core.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/** Whether animated media (emoji statuses, avatars, stickers, typing dots) may keep playing. */
val LocalMediaAnimationEnabled = compositionLocalOf { true }

object MediaScrollGate {
    private val sources = mutableStateMapOf<Any, Boolean>()

    fun set(source: Any, scrolling: Boolean) {
        if (scrolling) sources[source] = true else sources.remove(source)
    }

    fun isScrolling(): Boolean = sources.isNotEmpty()
}
