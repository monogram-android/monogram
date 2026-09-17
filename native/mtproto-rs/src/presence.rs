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
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{
        SendMessageCancelActionConstructor, SendMessageChooseStickerActionConstructor,
        SendMessageRecordAudioActionConstructor, SendMessageTypingActionConstructor,
        SendMessageUploadPhotoActionConstructor, UserStatusOfflineConstructor,
        UserStatusOnlineConstructor, UserStatusRecentlyConstructor,
    };

    #[test]
    fn maps_online_offline_recently() {
        let online = UserStatus::UserStatusOnline(UserStatusOnlineConstructor { expires: 99 });
        assert_eq!(
            user_status_parts(Some(&online)),
            (Some("online".into()), Some(99))
        );
        let offline = UserStatus::UserStatusOffline(UserStatusOfflineConstructor { was_online: 7 });
        assert_eq!(
            user_status_parts(Some(&offline)),
            (Some("offline".into()), Some(7))
        );
        let recent = UserStatus::UserStatusRecently(UserStatusRecentlyConstructor {
            flags: 0,
            by_me: None,
        });
        assert_eq!(
            user_status_parts(Some(&recent)).0.as_deref(),
            Some("recently")
        );
        assert_eq!(user_status_parts(None), (None, None));
    }

    #[test]
    fn maps_actions_and_activity() {
        let typing =
            SendMessageAction::SendMessageTypingAction(SendMessageTypingActionConstructor {});
        let cancel =
            SendMessageAction::SendMessageCancelAction(SendMessageCancelActionConstructor {});
        let audio = SendMessageAction::SendMessageRecordAudioAction(
            SendMessageRecordAudioActionConstructor {},
        );
        let photo = SendMessageAction::SendMessageUploadPhotoAction(
            SendMessageUploadPhotoActionConstructor { progress: 0 },
        );
        let sticker = SendMessageAction::SendMessageChooseStickerAction(
            SendMessageChooseStickerActionConstructor {},
        );

        assert_eq!(action_kind(&typing), Some("typing"));
        assert!(action_is_active(&typing));
        assert_eq!(action_kind(&cancel), None);
        assert!(!action_is_active(&cancel));
        assert_eq!(action_kind(&audio), Some("record_audio"));
        assert_eq!(action_kind(&photo), Some("upload_photo"));
        assert_eq!(action_kind(&sticker), Some("choose_sticker"));
        assert!(action_is_active(&audio));
    }
}
