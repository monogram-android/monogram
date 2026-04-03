package org.monogram.presentation.features.profile.logs.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlin.math.max

private enum class DiffType { Same, Added, Removed }

private data class DiffPart(val text: String, val type: DiffType)

fun calculateDiff(
    old: String,
    new: String,
    addedColor: Color = Color.Green.copy(alpha = 0.2f),
    removedColor: Color = Color.Red.copy(alpha = 0.2f)
): AnnotatedString {
    val oldWords = old.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val newWords = new.split(Regex("\\s+")).filter { it.isNotEmpty() }

    val diffList = computeDiff(oldWords, newWords)

    return buildAnnotatedString {
        diffList.forEachIndexed { index, part ->
            val type = part.type

            val style = when (type) {
                DiffType.Added -> SpanStyle(
                    background = addedColor,
                    fontWeight = FontWeight.Bold
                )

                DiffType.Removed -> SpanStyle(
                    background = removedColor,
                )

                DiffType.Same -> null
            }

            val nextPart = diffList.getOrNull(index + 1)
            val shouldColorSpace = nextPart != null &&
                    nextPart.type == type &&
                    type != DiffType.Same

            val contentAction = {
                append(part.text)
                if (shouldColorSpace) {
                    append(" ")
                }
            }

            if (style != null) {
                withStyle(style) { contentAction() }
            } else {
                contentAction()
            }

            if (!shouldColorSpace && index < diffList.size - 1) {
                append(" ")
            }
        }
    }
}

private fun computeDiff(oldList: List<String>, newList: List<String>): List<DiffPart> {
    val n = oldList.size
    val m = newList.size
    val dp = Array(n + 1) { IntArray(m + 1) }

    for (i in 1..n) {
        for (j in 1..m) {
            if (oldList[i - 1] == newList[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] + 1
            } else {
                dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    val result = mutableListOf<DiffPart>()
    var i = n
    var j = m

    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && oldList[i - 1] == newList[j - 1]) {
            result.add(DiffPart(oldList[i - 1], DiffType.Same))
            i--
            j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            result.add(DiffPart(newList[j - 1], DiffType.Added))
            j--
        } else {
            result.add(DiffPart(oldList[i - 1], DiffType.Removed))
            i--
        }
    }

    return result.reversed()
}