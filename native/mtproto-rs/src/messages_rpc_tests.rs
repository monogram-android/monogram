use super::{PHOTO_MAX, PHOTO_PART};

#[test]
fn channel_read_uses_channels_read_history() {
    use tellers_mtproto::latest::api::{ChannelsReadHistoryRequest, MessagesReadHistoryRequest};
    assert_eq!(ChannelsReadHistoryRequest::NAME, "channels.readHistory");
    assert_ne!(
        ChannelsReadHistoryRequest::ID,
        MessagesReadHistoryRequest::ID
    );
}

#[test]
fn unread_mention_and_reaction_methods_match_layer() {
    use tellers_mtproto::latest::api::{
        MessagesGetUnreadMentionsRequest, MessagesGetUnreadReactionsRequest,
        MessagesReadMentionsRequest, MessagesReadReactionsRequest,
    };
    assert_eq!(
        MessagesGetUnreadMentionsRequest::NAME,
        "messages.getUnreadMentions"
    );
    assert_eq!(MessagesReadMentionsRequest::NAME, "messages.readMentions");
    assert_eq!(
        MessagesGetUnreadReactionsRequest::NAME,
        "messages.getUnreadReactions"
    );
    assert_eq!(MessagesReadReactionsRequest::NAME, "messages.readReactions");
}

#[test]
fn read_message_contents_methods_match_layer() {
    use tellers_mtproto::latest::api::{
        ChannelsReadMessageContentsRequest, MessagesReadMessageContentsRequest,
    };
    assert_eq!(
        MessagesReadMessageContentsRequest::NAME,
        "messages.readMessageContents"
    );
    assert_eq!(
        ChannelsReadMessageContentsRequest::NAME,
        "channels.readMessageContents"
    );
}

#[test]
fn entities_json_maps_bold() {
    let json = r#"[{"kind":"bold","offset":0,"length":4}]"#;
    let (flag, entities) = super::entities_from_json(Some(json)).unwrap();
    assert_eq!(
        flag,
        tellers_mtproto::latest::api::MessagesSendMessageRequest::ENTITIES_FLAG
    );
    assert!(entities.is_some());
    assert_eq!(super::entities_from_json(None).unwrap().0, 0);
}

#[test]
fn entities_json_maps_custom_emoji() {
    let json = r#"[{"kind":"custom_emoji","offset":0,"length":2,"url":"42"}]"#;
    let (_, entities) = super::entities_from_json(Some(json)).unwrap();
    let items = entities.expect("entities");
    match items.as_ref() {
        tellers_mtproto::latest::api::Vector::Vector(v) => {
            assert!(matches!(
                v.field_1[0].as_ref(),
                tellers_mtproto::latest::api::MessageEntity::MessageEntityCustomEmoji(_)
            ));
        }
    }
}

#[test]
fn photo_part_size_matches_file_api() {
    assert_eq!(PHOTO_PART % 1024, 0);
    assert_eq!((512 * 1024) % PHOTO_PART, 0);
    assert_eq!(PHOTO_MAX, 10 * 1024 * 1024);
}

#[test]
fn forum_topic_reply_sets_top_msg_id() {
    use tellers_mtproto::latest::api::InputReplyTo;
    let (flag, reply) = super::input_reply_to_thread(9, 42);
    assert!(flag != 0);
    let InputReplyTo::InputReplyToMessage(header) = *reply.expect("reply") else {
        panic!("expected inputReplyToMessage");
    };
    assert_eq!(header.reply_to_msg_id, 9);
    assert_eq!(header.top_msg_id, Some(42));
}

#[test]
fn send_to_topic_uses_topic_as_reply() {
    use tellers_mtproto::latest::api::InputReplyTo;
    let (_, reply) = super::input_reply_to_thread(42, 0);
    let InputReplyTo::InputReplyToMessage(header) = *reply.expect("reply") else {
        panic!("expected inputReplyToMessage");
    };
    assert_eq!(header.reply_to_msg_id, 42);
    assert_eq!(header.top_msg_id, None);
}

#[test]
fn pinned_filter_matches_layer_223() {
    use tellers_mtproto::latest::api::InputMessagesFilterPinnedConstructor;
    assert_eq!(InputMessagesFilterPinnedConstructor::ID, 0x1bb0_0451);
    assert_eq!(
        InputMessagesFilterPinnedConstructor::NAME,
        "inputMessagesFilterPinned"
    );
}

#[test]
fn search_limit_clamps_to_api_max() {
    assert_eq!(super::clamp_search_limit(0), 1);
    assert_eq!(super::clamp_search_limit(40), 40);
    assert_eq!(super::clamp_search_limit(100), 100);
    assert_eq!(super::clamp_search_limit(101), 100);
}

#[test]
fn named_search_filters_match_layer() {
    use tellers_mtproto::latest::api::MessagesFilter;
    assert!(matches!(
        super::messages_filter("photo_video"),
        MessagesFilter::InputMessagesFilterPhotoVideo(_)
    ));
    assert!(matches!(
        super::messages_filter("document"),
        MessagesFilter::InputMessagesFilterDocument(_)
    ));
    assert!(matches!(
        super::messages_filter("url"),
        MessagesFilter::InputMessagesFilterUrl(_)
    ));
    assert!(matches!(
        super::messages_filter("gif"),
        MessagesFilter::InputMessagesFilterGif(_)
    ));
    assert!(matches!(
        super::messages_filter("voice"),
        MessagesFilter::InputMessagesFilterVoice(_)
    ));
    assert!(matches!(
        super::messages_filter("music"),
        MessagesFilter::InputMessagesFilterMusic(_)
    ));
    assert!(matches!(
        super::messages_filter("photos"),
        MessagesFilter::InputMessagesFilterPhotos(_)
    ));
    assert!(matches!(
        super::messages_filter("videos"),
        MessagesFilter::InputMessagesFilterVideo(_)
    ));
    assert!(matches!(
        super::messages_filter("chat_photos"),
        MessagesFilter::InputMessagesFilterChatPhotos(_)
    ));
    assert!(matches!(
        super::messages_filter("pinned"),
        MessagesFilter::InputMessagesFilterPinned(_)
    ));
    assert!(matches!(
        super::messages_filter("nope"),
        MessagesFilter::InputMessagesFilterEmpty(_)
    ));
}

#[test]
fn filter_keys_round_trip_profile_tabs() {
    for name in [
        "photo_video",
        "document",
        "url",
        "gif",
        "voice",
        "music",
        "photos",
        "videos",
        "chat_photos",
    ] {
        let filter = super::messages_filter(name);
        assert_eq!(super::messages_filter_key(&filter), Some(name));
    }
    assert_eq!(
        super::messages_filter_key(&super::messages_filter("nope")),
        None
    );
    assert_eq!(
        super::messages_filter_key(&super::messages_filter("pinned")),
        Some("pinned")
    );
}

#[test]
fn forward_requires_known_peers() {
    use crate::{HashMap, HashMapExt};
    use tellers_mtproto_session::{OsRandom, Snapshot};
    let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
    let peers = HashMap::new();
    let mut media = crate::media_rpc::MediaIndex::new();
    let err = super::forward_messages(&mut snapshot, 1, &peers, &mut media, 1, 7, 2)
        .expect_err("unknown from peer");
    match err {
        crate::MtprotoError::Message(message) => {
            assert!(message.contains("unknown peer 1"));
        }
        other => panic!("unexpected {other:?}"),
    }
}
