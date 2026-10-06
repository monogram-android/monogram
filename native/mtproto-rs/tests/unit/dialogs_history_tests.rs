use super::{get_history, refresh_message_media};
use crate::{HashMap, HashMapExt};
use tellers_mtproto_session::{OsRandom, Snapshot};

#[test]
fn unknown_peer_fails_before_rpc() {
    let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
    let mut peers = HashMap::new();
    let mut media = crate::media::MediaIndex::new();
    let err = get_history(
        &mut snapshot,
        1,
        &mut peers,
        &mut media,
        42,
        10,
        0,
        0,
        0,
        None,
    )
    .expect_err("unknown peer");
    match err {
        crate::MtprotoError::Message(message) => {
            assert!(message.contains("unknown peer 42"));
            assert!(message.contains("refresh chats first"));
        }
        other => panic!("unexpected {other:?}"),
    }
}

#[test]
fn get_dialogs_first_page_uses_empty_offset_peer() {
    use tellers_mtproto::latest::api::InputPeerEmptyConstructor;
    let request = super::MessagesGetDialogsRequest {
        flags: 0,
        exclude_pinned: None,
        folder_id: None,
        offset_date: 0,
        offset_id: 0,
        offset_peer: Box::new(super::InputPeer::InputPeerEmpty(
            InputPeerEmptyConstructor {},
        )),
        limit: 40,
        hash: 0,
    };
    let bytes = crate::rpc::encode_boxed_bytes(&request).expect("encode");
    assert!(
        bytes
            .windows(4)
            .any(|w| w == &super::MessagesGetDialogsRequest::ID.to_le_bytes()),
        "messages.getDialogs ctor",
    );
    assert!(
        bytes
            .windows(4)
            .any(|w| w == &InputPeerEmptyConstructor::ID.to_le_bytes()),
        "first page offset_peer is inputPeerEmpty",
    );
}

#[test]
fn get_messages_request_encodes_input_message_id() {
    let req = super::MessagesGetMessagesRequest {
        id: super::input_message_ids(7),
    };
    let bytes = crate::rpc::encode_boxed_bytes(&req).expect("encode");
    assert!(
        bytes
            .windows(4)
            .any(|w| w == &super::MessagesGetMessagesRequest::ID.to_le_bytes()),
        "messages.getMessages ctor",
    );
    assert!(
        bytes
            .windows(4)
            .any(|w| w == &super::InputMessageIdConstructor::ID.to_le_bytes()),
        "inputMessageID ctor",
    );
    assert!(bytes.windows(4).any(|w| w == &7i32.to_le_bytes()));
}

#[test]
fn refresh_message_media_unknown_peer_fails_before_rpc() {
    let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
    let peers = HashMap::new();
    let mut media = crate::media::MediaIndex::new();
    let err = refresh_message_media(&mut snapshot, 1, &peers, &mut media, 42, 7)
        .expect_err("unknown peer");
    match err {
        crate::MtprotoError::Message(message) => {
            assert!(message.contains("unknown peer 42"));
        }
        other => panic!("unexpected {other:?}"),
    }
}

use super::merge_channel_pts;

#[test]
fn dialog_pts_seed_is_positive_and_monotonic() {
    let mut cursors = HashMap::from_iter([(42_i64, 80_i32)]);

    merge_channel_pts(&mut cursors, 42, Some(64));
    merge_channel_pts(&mut cursors, 42, Some(96));
    merge_channel_pts(&mut cursors, 42, Some(0));
    merge_channel_pts(&mut cursors, 42, None);

    assert_eq!(cursors.get(&42), Some(&96));
}
