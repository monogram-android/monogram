package org.monogram.feature.dialog.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.monogram.core.markup.KotlinMarkupParser
import org.monogram.core.markup.MarkupParser

internal val LocalMarkupParser = staticCompositionLocalOf<MarkupParser> { KotlinMarkupParser() }
