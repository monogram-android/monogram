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
    let typing = SendMessageAction::SendMessageTypingAction(SendMessageTypingActionConstructor {});
    let cancel = SendMessageAction::SendMessageCancelAction(SendMessageCancelActionConstructor {});
    let audio =
        SendMessageAction::SendMessageRecordAudioAction(SendMessageRecordAudioActionConstructor {});
    let photo =
        SendMessageAction::SendMessageUploadPhotoAction(SendMessageUploadPhotoActionConstructor {
            progress: 0,
        });
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
