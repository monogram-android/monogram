package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.RichText
import org.monogram.domain.models.UpdateInfo

fun TdApi.FormattedText.toChangelog(): List<RichText> {
    val text = this.text
    val markerIndex = text.indexOf("Changelog:", ignoreCase = true)
        .takeIf { it != -1 } ?: return emptyList()

    val changelogText = text.substring(markerIndex + "Changelog:".length).trimStart()
    val actualStart = text.indexOf(changelogText, markerIndex + "Changelog:".length)
    var currentOffset = actualStart

    return changelogText.lines().mapNotNull { line ->
        val trimmed = line.trim()
        val lineStart = text.indexOf(line, currentOffset)
        val trimmedStart = lineStart + line.indexOf(trimmed)
        currentOffset = lineStart + line.length

        if (trimmed.isEmpty()) return@mapNotNull null

        val numberingMatch = Regex("""^\d+\.\s*""").find(trimmed)
        val (finalText, finalStart) = if (numberingMatch != null) {
            trimmed.substring(numberingMatch.value.length) to (trimmedStart + numberingMatch.value.length)
        } else {
            trimmed to trimmedStart
        }

        val entities = this.entities?.mapNotNull { entity ->
            val overlapStart = maxOf(entity.offset, finalStart)
            val overlapEnd = minOf(entity.offset + entity.length, finalStart + finalText.length)
            if (overlapStart >= overlapEnd) return@mapNotNull null
            entity.toDomain()?.copy(
                offset = overlapStart - finalStart,
                length = overlapEnd - overlapStart
            )
        } ?: emptyList()

        RichText(finalText, entities)
    }
}

fun TdApi.TextEntity.toDomain(): MessageEntity? {
    return toMessageEntityOrNull()
}

fun TdApi.MessageDocument.toUpdateInfo(): UpdateInfo? {
    val text = this.caption.text
    val match = Regex("""(\d+\.\d+\.\d+)\s*\((\d+)\)""").find(text) ?: return null
    return UpdateInfo(
        version = match.groupValues[1],
        versionCode = match.groupValues[2].toInt(),
        description = text,
        changelog = this.caption.toChangelog(),
        fileId = this.document.document.id,
        fileName = this.document.fileName,
        fileSize = this.document.document.size
    )
}