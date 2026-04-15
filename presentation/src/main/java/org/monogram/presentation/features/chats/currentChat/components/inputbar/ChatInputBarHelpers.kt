package org.monogram.presentation.features.chats.currentChat.components.inputbar

import android.content.Context
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.StickerFormat
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.UserModel
import java.io.File
import java.io.FileOutputStream

internal data class InlineQueryInput(
    val botUsername: String,
    val query: String
)

internal fun parseInlineQueryInput(text: String, selection: TextRange): InlineQueryInput? {
    if (!selection.collapsed) return null
    val cursor = selection.start
    if (!text.startsWith('@') || cursor !in 0..text.length) return null

    val firstSpaceIndex = text.indexOf(' ')
    if (firstSpaceIndex <= 1 || cursor <= firstSpaceIndex) return null

    val botUsername = text.substring(1, firstSpaceIndex)
    if (botUsername.any { !it.isLetterOrDigit() && it != '_' }) return null

    val query = text.substring(firstSpaceIndex + 1)
    if (query.isBlank() || query.contains('\n')) return null

    return InlineQueryInput(botUsername = botUsername, query = query)
}

internal fun String.isInlineBotPrefillText(): Boolean {
    if (!startsWith("@") || !endsWith(" ")) return false
    val username = drop(1).dropLast(1)
    return username.isNotEmpty() && username.all { it.isLetterOrDigit() || it == '_' }
}

internal fun applyMentionSuggestion(value: TextFieldValue, user: UserModel): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val lastAt = text.lastIndexOf('@', selection.start - 1)
    if (lastAt == -1) return value

    val mentionText = user.username ?: user.firstName
    val newText = text.replaceRange(lastAt + 1, selection.start, "$mentionText ")
    val builder = AnnotatedString.Builder().apply { append(newText) }

    value.annotatedString.getStringAnnotations(0, text.length).forEach { annotation ->
        if (annotation.start < lastAt) {
            builder.addStringAnnotation(annotation.tag, annotation.item, annotation.start, annotation.end)
        } else if (annotation.start >= selection.start) {
            val offset = (mentionText.length + 1) - (selection.start - (lastAt + 1))
            builder.addStringAnnotation(
                annotation.tag,
                annotation.item,
                annotation.start + offset,
                annotation.end + offset
            )
        }
    }

    if (user.username == null) {
        builder.addStringAnnotation(
            MENTION_TAG,
            user.id.toString(),
            lastAt,
            lastAt + mentionText.length + 1
        )
    }

    return TextFieldValue(
        annotatedString = builder.toAnnotatedString(),
        selection = TextRange(lastAt + mentionText.length + 2)
    )
}

internal fun buildEditingMessageTextValue(
    message: MessageModel,
    knownCustomEmojis: MutableMap<Long, StickerModel>
): TextFieldValue? {
    val content = message.content as? MessageContent.Text ?: return null
    return buildTextFieldValueFromTextAndEntities(
        text = content.text,
        entities = content.entities,
        knownCustomEmojis = knownCustomEmojis
    )
}

internal fun buildTextFieldValueFromTextAndEntities(
    text: String,
    entities: List<MessageEntity>,
    knownCustomEmojis: MutableMap<Long, StickerModel>
): TextFieldValue {
    knownCustomEmojis.clear()

    entities.forEach { entity ->
        val type = entity.type as? MessageEntityType.CustomEmoji ?: return@forEach
        val emojiPath = type.path ?: return@forEach
        knownCustomEmojis[type.emojiId] = StickerModel(
            id = type.emojiId,
            customEmojiId = type.emojiId,
            width = 0,
            height = 0,
            emoji = "",
            path = emojiPath,
            format = StickerFormat.UNKNOWN
        )
    }

    val annotatedText = buildAnnotatedString {
        append(text)
        entities.forEach { entity ->
            val start = entity.offset.coerceIn(0, text.length)
            val end = (entity.offset + entity.length).coerceIn(0, text.length)
            if (start >= end) return@forEach

            when (val type = entity.type) {
                is MessageEntityType.CustomEmoji -> addStringAnnotation(
                    CUSTOM_EMOJI_TAG,
                    type.emojiId.toString(),
                    start,
                    end
                )

                is MessageEntityType.TextMention -> addStringAnnotation(
                    MENTION_TAG,
                    type.userId.toString(),
                    start,
                    end
                )

                else -> {
                    val richEntity = richEntityToAnnotation(type)
                    if (richEntity != null) {
                        addStringAnnotation(
                            RICH_ENTITY_TAG,
                            richEntity,
                            start,
                            end
                        )
                    }
                }
            }
        }
    }

    return TextFieldValue(annotatedText, TextRange(text.length))
}

internal fun Context.hasAllPermissions(permissions: List<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

internal fun Context.declaredPermissions(): Set<String> {
    val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
    return info.requestedPermissions?.toSet().orEmpty()
}

internal fun Context.copyUriToTempPath(uri: android.net.Uri): String? {
    return try {
        if (uri.scheme == "file") return uri.path
        val mime = contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("video") -> "mp4"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }
        val file = File(cacheDir, "attach_${System.nanoTime()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

internal fun Context.copyUriToTempDocumentPath(uri: android.net.Uri): String? {
    return try {
        if (uri.scheme == "file") return uri.path

        val displayName =
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
                ?.trim()
                .orEmpty()

        val mime = contentResolver.getType(uri).orEmpty()
        val safeName = when {
            displayName.isNotBlank() -> displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            mime.startsWith("application/pdf") -> "document_${System.nanoTime()}.pdf"
            else -> "document_${System.nanoTime()}.bin"
        }
        val file = File(cacheDir, "doc_${System.nanoTime()}_$safeName")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}
