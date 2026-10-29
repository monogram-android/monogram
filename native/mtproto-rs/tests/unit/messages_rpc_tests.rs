use super::{PHOTO_MAX, PHOTO_PART};

#[test]
fn retained_location_sends_preserve_random_identity_thread_and_options() {
    use crate::messages::location_send::location_send_request;
    use mono_proto::codec::{Decoder, Limits, TlDecode};
    use mono_proto::latest::api::*;
    let peers = [(
        7,
        crate::peers::CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 7,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    let mut spec = crate::LocationSendSpec {
        latitude: -90.0,
        longitude: 180.0,
        live_period: 0,
        heading: None,
    };
    let mut options = crate::SendOptions {
        silent: true,
        no_webpage: false,
        schedule_date: Some(crate::client::scheduled::SCHEDULE_WHEN_ONLINE),
    };
    for live in [false, true] {
        if live {
            spec.live_period = 900;
            spec.heading = Some(360);
            options.schedule_date = None;
        }
        let request = location_send_request(&peers, 7, &spec, 42, 99, 123, &options).unwrap();
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesSendMediaRequest::ID);
        let row = MessagesSendMediaRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(row.random_id, 123);
        assert!(row.silent.is_some());
        assert_eq!(row.schedule_date, options.schedule_date);
        let InputReplyTo::InputReplyToMessage(reply) = *row.reply_to.unwrap() else {
            panic!()
        };
        assert_eq!(reply.reply_to_msg_id, 42);
        assert_eq!(reply.top_msg_id, Some(99));
        if live {
            let InputMedia::InputMediaGeoLive(media) = *row.media else {
                panic!()
            };
            assert_eq!(media.period, Some(900));
            assert_eq!(media.heading, Some(360));
        } else {
            assert!(matches!(*row.media, InputMedia::InputMediaGeoPoint(_)));
        }
    }
    assert!(location_send_request(&peers, 7, &spec, 0, 0, 0, &options).is_err());
    options.schedule_date = Some(crate::client::scheduled::SCHEDULE_WHEN_ONLINE);
    assert!(location_send_request(&peers, 7, &spec, 0, 0, 123, &options).is_err());
    options.schedule_date = None;
    for (lat, long, period, heading) in [
        (f64::NAN, 0.0, 900, None),
        (0.0, 181.0, 900, None),
        (0.0, 0.0, -1, None),
        (0.0, 0.0, 59, None),
        (0.0, 0.0, 86401, None),
        (0.0, 0.0, 900, Some(0)),
        (0.0, 0.0, 0, Some(1)),
    ] {
        spec = crate::LocationSendSpec {
            latitude: lat,
            longitude: long,
            live_period: period,
            heading,
        };
        assert!(location_send_request(&peers, 7, &spec, 0, 0, 123, &options).is_err());
    }
}

#[test]
fn common_detected_entities_survive_json_round_trips() {
    let kinds = [
        "url",
        "mention",
        "hashtag",
        "bot_command",
        "email",
        "phone",
        "cashtag",
        "bank_card",
    ];
    let json = serde_json::to_string(
        &kinds.map(|kind| serde_json::json!({"kind": kind, "offset": 0, "length": 5})),
    )
    .unwrap();
    let (_, entities) = crate::messages::entities_from_json(Some(&json)).unwrap();
    assert_eq!(
        crate::peers::vector_items(entities.as_deref().unwrap()).len(),
        kinds.len()
    );
    let mapped = crate::dialogs::entities_to_json(entities.as_deref());
    let (_, restored) = crate::messages::entities_from_json(mapped.as_deref()).unwrap();
    assert_eq!(entities, restored);
}

#[test]
fn channel_read_uses_channels_read_history() {
    use mono_proto::latest::api::{ChannelsReadHistoryRequest, MessagesReadHistoryRequest};
    assert_eq!(ChannelsReadHistoryRequest::NAME, "channels.readHistory");
    assert_ne!(
        ChannelsReadHistoryRequest::ID,
        MessagesReadHistoryRequest::ID
    );
}

#[test]
fn unread_mention_and_reaction_methods_match_layer() {
    use mono_proto::latest::api::{
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
    use mono_proto::latest::api::{
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
        mono_proto::latest::api::MessagesSendMessageRequest::ENTITIES_FLAG
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
        mono_proto::latest::api::Vector::Vector(v) => {
            assert!(matches!(
                v.field_1[0].as_ref(),
                mono_proto::latest::api::MessageEntity::MessageEntityCustomEmoji(_)
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
    use mono_proto::latest::api::InputReplyTo;
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
    use mono_proto::latest::api::InputReplyTo;
    let (_, reply) = super::input_reply_to_thread(42, 0);
    let InputReplyTo::InputReplyToMessage(header) = *reply.expect("reply") else {
        panic!("expected inputReplyToMessage");
    };
    assert_eq!(header.reply_to_msg_id, 42);
    assert_eq!(header.top_msg_id, None);
}

#[test]
fn pinned_filter_matches_layer_223() {
    use mono_proto::latest::api::InputMessagesFilterPinnedConstructor;
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
    use mono_proto::latest::api::MessagesFilter;
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
    use mono_proto_session::{OsRandom, Snapshot};
    let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
    let peers = HashMap::new();
    let mut media = crate::media::MediaIndex::default();
    let err = super::forward_messages(&mut snapshot, 1, &peers, &mut media, 1, vec![7], 2, false)
        .expect_err("unknown from peer");
    match err {
        crate::MtprotoError::Message(message) => {
            assert!(message.contains("unknown peer 1"));
        }
        other => panic!("unexpected {other:?}"),
    }
}
