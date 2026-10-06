//! Map Telegram user status / typing actions into compact DTO strings.
//! https://core.telegram.org/type/UserStatus
//! https://core.telegram.org/type/SendMessageAction

use tellers_mtproto::latest::api::{SendMessageAction, UserStatus};

pub fn user_status_parts(status: Option<&UserStatus>) -> (Option<String>, Option<i64>) {
    match status {
        Some(UserStatus::UserStatusOnline(s)) => {
            ("online".to_string().into(), Some(i64::from(s.expires)))
        }
        Some(UserStatus::UserStatusOffline(s)) => {
            ("offline".to_string().into(), Some(i64::from(s.was_online)))
        }
        Some(UserStatus::UserStatusRecently(_)) => ("recently".to_string().into(), None),
        Some(UserStatus::UserStatusLastWeek(_)) => ("last_week".to_string().into(), None),
        Some(UserStatus::UserStatusLastMonth(_)) => ("last_month".to_string().into(), None),
        _ => (None, None),
    }
}

/// Compact wire kind for a live send-message action. `None` means cancel / not shown.
pub fn action_kind(action: &SendMessageAction) -> Option<&'static str> {
    match action {
        SendMessageAction::SendMessageTypingAction(_) => Some("typing"),
        SendMessageAction::SendMessageRecordAudioAction(_) => Some("record_audio"),
        SendMessageAction::SendMessageUploadAudioAction(_) => Some("upload_audio"),
        SendMessageAction::SendMessageRecordVideoAction(_) => Some("record_video"),
        SendMessageAction::SendMessageUploadVideoAction(_) => Some("upload_video"),
        SendMessageAction::SendMessageUploadPhotoAction(_) => Some("upload_photo"),
        SendMessageAction::SendMessageUploadDocumentAction(_) => Some("upload_document"),
        SendMessageAction::SendMessageGeoLocationAction(_) => Some("geo"),
        SendMessageAction::SendMessageChooseContactAction(_) => Some("contact"),
        SendMessageAction::SendMessageGamePlayAction(_) => Some("game"),
        SendMessageAction::SendMessageRecordRoundAction(_) => Some("record_round"),
        SendMessageAction::SendMessageUploadRoundAction(_) => Some("upload_round"),
        SendMessageAction::SpeakingInGroupCallAction(_) => Some("speaking"),
        SendMessageAction::SendMessageChooseStickerAction(_) => Some("choose_sticker"),
        SendMessageAction::SendMessageEmojiInteractionSeen(_) => Some("watching_emoji"),
        SendMessageAction::SendMessageCancelAction(_)
        | SendMessageAction::SendMessageHistoryImportAction(_)
        | SendMessageAction::SendMessageEmojiInteraction(_)
        | SendMessageAction::SendMessageTextDraftAction(_)
        | SendMessageAction::InputSendMessageRichMessageDraftAction(_)
        | SendMessageAction::SendMessageRichMessageDraftAction(_)
        | SendMessageAction::SendMessageStopDraftAction(_) => None,
    }
}

pub fn action_is_active(action: &SendMessageAction) -> bool {
    action_kind(action).is_some()
}

#[cfg(test)]
#[path = "../tests/unit/presence_tests.rs"]
mod tests;
