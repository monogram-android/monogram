package org.monogram.presentation.features.profile.logs.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.chats.MessageText
import org.monogram.presentation.features.chats.currentChat.components.chats.buildAnnotatedMessageTextWithEmoji
import org.monogram.presentation.features.chats.currentChat.components.chats.rememberMessageInlineContent
import org.monogram.presentation.features.profile.logs.ProfileLogsComponent
import java.io.File

@Composable
fun MessagePreview(
    message: MessageModel,
    oldMessage: MessageModel? = null,
    component: ProfileLogsComponent
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val content = message.content

            val mediaPath = when (content) {
                is MessageContent.Photo -> content.path
                is MessageContent.Gif -> content.path
                is MessageContent.Video -> content.path
                is MessageContent.Sticker -> content.path
                else -> null
            }

            if (mediaPath != null) {
                val file = File(mediaPath)
                if (file.exists()) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (content is MessageContent.Gif) 80.dp else 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                when (content) {
                                    is MessageContent.Photo -> component.onPhotoClick(mediaPath, content.caption)
                                    is MessageContent.Gif -> component.onVideoClick(
                                        mediaPath,
                                        content.caption,
                                        content.fileId,
                                        true
                                    )

                                    is MessageContent.Video -> component.onVideoClick(
                                        mediaPath,
                                        content.caption,
                                        content.fileId,
                                        content.supportsStreaming
                                    )

                                    else -> {}
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                val oldContent = oldMessage?.content
                when (content) {
                    is MessageContent.Text -> {
                        if (oldContent is MessageContent.Text) {
                            MessageText(
                                text = calculateDiff(oldContent.text, content.text),
                                inlineContent = emptyMap(),
                                style = MaterialTheme.typography.bodyMedium,
                                entities = emptyList()
                            )
                        } else {
                            val annotatedText = buildAnnotatedMessageTextWithEmoji(
                                text = content.text,
                                entities = content.entities
                            )
                            val inlineContent = rememberMessageInlineContent(
                                entities = content.entities,
                                fontSize = 14f
                            )
                            MessageText(
                                text = annotatedText,
                                inlineContent = inlineContent,
                                style = MaterialTheme.typography.bodyMedium,
                                entities = content.entities
                            )
                        }
                    }

                    is MessageContent.Photo -> {
                        val oldCaption = (oldContent as? MessageContent.Photo)?.caption
                        MediaPreviewText(stringResource(R.string.logs_media_photo), content.caption, content.entities, oldCaption)
                    }

                    is MessageContent.Video -> {
                        val oldCaption = (oldContent as? MessageContent.Video)?.caption
                        MediaPreviewText(stringResource(R.string.logs_media_video), content.caption, content.entities, oldCaption)
                    }

                    is MessageContent.Document -> {
                        val oldDoc = oldContent as? MessageContent.Document
                        val oldText = oldDoc?.caption?.ifEmpty { oldDoc.fileName }
                        val newText = content.caption.ifEmpty { content.fileName }
                        MediaPreviewText(stringResource(R.string.logs_media_document), newText, content.entities, oldText)
                    }

                    is MessageContent.Audio -> {
                        val oldAudio = oldContent as? MessageContent.Audio
                        val oldText = oldAudio?.caption?.ifEmpty { oldAudio.title.ifEmpty { oldAudio.fileName } }
                        val newText = content.caption.ifEmpty { content.title.ifEmpty { content.fileName } }
                        MediaPreviewText(stringResource(R.string.logs_media_audio), newText, content.entities, oldText)
                    }

                    is MessageContent.Sticker -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_sticker))
                                }
                                append(" ${content.emoji}")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.Voice -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_voice))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.VideoNote -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_video_note))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.Gif -> {
                        val oldCaption = (oldContent as? MessageContent.Gif)?.caption
                        MediaPreviewText(stringResource(R.string.logs_media_gif), content.caption, content.entities, oldCaption)
                    }

                    is MessageContent.Contact -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_contact))
                                }
                                append(": ${content.firstName} ${content.lastName}".trim())
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.Poll -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_poll))
                                }
                                append(": ${content.question}")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.Location -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_location))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.Venue -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(stringResource(R.string.logs_media_venue))
                                }
                                append(": ${content.title}")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is MessageContent.Service -> {
                        Text(
                            text = content.text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    MessageContent.Unsupported -> {
                        Text(
                            stringResource(R.string.logs_media_unsupported),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPreviewText(
    label: String,
    text: String,
    entities: List<MessageEntity>,
    oldText: String? = null
) {
    val annotatedText = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(label)
        }
        if (text.isNotEmpty() || !oldText.isNullOrEmpty()) {
            append(": ")
            if (oldText != null && oldText != text) {
                append(calculateDiff(oldText, text))
            } else {
                append(buildAnnotatedMessageTextWithEmoji(text, entities))
            }
        }
    }

    MessageText(
        text = annotatedText,
        inlineContent = emptyMap(),
        style = MaterialTheme.typography.bodyMedium,
        entities = if (oldText != null && oldText != text) emptyList() else entities
    )
}
