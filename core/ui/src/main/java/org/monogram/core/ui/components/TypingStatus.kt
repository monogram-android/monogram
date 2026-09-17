package org.monogram.core.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.TypingLabel
import org.monogram.core.models.typingLabel
import org.monogram.core.ui.R

fun liveActionTransition(): ContentTransform =
    ContentTransform(
        targetContentEnter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
        initialContentExit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 3 },
        sizeTransform = null,
    )

@Composable
fun typingStatusText(
    names: List<String>,
    action: String? = null,
): String {
    val kind = ChatActionKind.fromWire(action)
    return when (val label = typingLabel(names)) {
        TypingLabel.Generic -> stringResource(kind.genericRes)
        is TypingLabel.One -> stringResource(kind.oneRes, label.name)
        is TypingLabel.Two -> stringResource(kind.twoRes, label.first, label.second)
        is TypingLabel.Many -> pluralStringResource(kind.manyRes, label.count, label.count)
    }
}

private val ChatActionKind.genericRes: Int
    get() = when (this) {
        ChatActionKind.Typing -> R.string.status_typing
        ChatActionKind.RecordAudio -> R.string.status_record_audio
        ChatActionKind.UploadAudio -> R.string.status_upload_audio
        ChatActionKind.RecordVideo -> R.string.status_record_video
        ChatActionKind.UploadVideo -> R.string.status_upload_video
        ChatActionKind.UploadPhoto -> R.string.status_upload_photo
        ChatActionKind.UploadDocument -> R.string.status_upload_file
        ChatActionKind.Geo -> R.string.status_geo
        ChatActionKind.Contact -> R.string.status_contact
        ChatActionKind.Game -> R.string.status_game
        ChatActionKind.RecordRound -> R.string.status_record_round
        ChatActionKind.UploadRound -> R.string.status_upload_round
        ChatActionKind.ChooseSticker -> R.string.status_choose_sticker
        ChatActionKind.Speaking -> R.string.status_speaking
        ChatActionKind.WatchingEmoji -> R.string.status_watching
    }

private val ChatActionKind.oneRes: Int
    get() = when (this) {
        ChatActionKind.Typing -> R.string.status_typing_user
        ChatActionKind.RecordAudio -> R.string.status_record_audio_user
        ChatActionKind.UploadAudio -> R.string.status_upload_audio_user
        ChatActionKind.RecordVideo -> R.string.status_record_video_user
        ChatActionKind.UploadVideo -> R.string.status_upload_video_user
        ChatActionKind.UploadPhoto -> R.string.status_upload_photo_user
        ChatActionKind.UploadDocument -> R.string.status_upload_file_user
        ChatActionKind.Geo -> R.string.status_geo_user
        ChatActionKind.Contact -> R.string.status_contact_user
        ChatActionKind.Game -> R.string.status_game_user
        ChatActionKind.RecordRound -> R.string.status_record_round_user
        ChatActionKind.UploadRound -> R.string.status_upload_round_user
        ChatActionKind.ChooseSticker -> R.string.status_choose_sticker_user
        ChatActionKind.Speaking -> R.string.status_speaking_user
        ChatActionKind.WatchingEmoji -> R.string.status_watching_user
    }

private val ChatActionKind.twoRes: Int
    get() = when (this) {
        ChatActionKind.Typing -> R.string.status_typing_two
        ChatActionKind.RecordAudio -> R.string.status_record_audio_two
        ChatActionKind.UploadAudio -> R.string.status_upload_audio_two
        ChatActionKind.RecordVideo -> R.string.status_record_video_two
        ChatActionKind.UploadVideo -> R.string.status_upload_video_two
        ChatActionKind.UploadPhoto -> R.string.status_upload_photo_two
        ChatActionKind.UploadDocument -> R.string.status_upload_file_two
        ChatActionKind.Geo -> R.string.status_geo_two
        ChatActionKind.Contact -> R.string.status_contact_two
        ChatActionKind.Game -> R.string.status_game_two
        ChatActionKind.RecordRound -> R.string.status_record_round_two
        ChatActionKind.UploadRound -> R.string.status_upload_round_two
        ChatActionKind.ChooseSticker -> R.string.status_choose_sticker_two
        ChatActionKind.Speaking -> R.string.status_speaking_two
        ChatActionKind.WatchingEmoji -> R.string.status_watching_two
    }

private val ChatActionKind.manyRes: Int
    get() = when (this) {
        ChatActionKind.Typing -> R.plurals.status_typing_many
        ChatActionKind.RecordAudio -> R.plurals.status_record_audio_many
        ChatActionKind.UploadAudio -> R.plurals.status_upload_audio_many
        ChatActionKind.RecordVideo -> R.plurals.status_record_video_many
        ChatActionKind.UploadVideo -> R.plurals.status_upload_video_many
        ChatActionKind.UploadPhoto -> R.plurals.status_upload_photo_many
        ChatActionKind.UploadDocument -> R.plurals.status_upload_file_many
        ChatActionKind.Geo -> R.plurals.status_geo_many
        ChatActionKind.Contact -> R.plurals.status_contact_many
        ChatActionKind.Game -> R.plurals.status_game_many
        ChatActionKind.RecordRound -> R.plurals.status_record_round_many
        ChatActionKind.UploadRound -> R.plurals.status_upload_round_many
        ChatActionKind.ChooseSticker -> R.plurals.status_choose_sticker_many
        ChatActionKind.Speaking -> R.plurals.status_speaking_many
        ChatActionKind.WatchingEmoji -> R.plurals.status_watching_many
    }
