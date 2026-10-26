use super::*;
use tellers_mtproto::codec::{Decoder, Encoder, Limits, TlDecode, TlEncode};

#[test]
fn scheduled_text_replacement_resolves_named_mentions_before_rpc() {
    let peers = [(
        42,
        crate::peers::CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 42,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    let json = r#"[{"kind":"mention_name","offset":0,"length":5,"url":"42"}]"#;
    let request = edit_request(
        InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
        7,
        100,
        Some("Alice".into()),
        Some(json),
        false,
        &peers,
    )
    .unwrap();
    assert!(matches!(
        crate::peers::vector_items(request.entities.as_ref().unwrap())[0].as_ref(),
        MessageEntity::InputMessageEntityMentionName(_)
    ));
    assert!(
        edit_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            7,
            100,
            Some("Alice".into()),
            Some(json),
            false,
            &crate::HashMap::default()
        )
        .is_err()
    );
}

#[test]
fn edit_wire_distinguishes_reschedule_from_text_replacement_and_format_clear() {
    for text in [None, Some("new caption".into()), Some(String::new())] {
        let request = edit_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            7,
            100,
            text.clone(),
            None,
            text.is_some(),
            &crate::HashMap::default(),
        )
        .unwrap();
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesEditMessageRequest::ID);
        let decoded = MessagesEditMessageRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(decoded.schedule_date, Some(100));
        assert_eq!(decoded.message, text);
        assert_eq!(decoded.entities.is_some(), text.is_some());
        assert!(decoded.media.is_none());
        assert!(decoded.rich_message.is_none());
    }
}

fn photo_message() -> MessageConstructor {
    let mut encoder = Encoder::new();
    for value in [0u32, 0, 37] {
        value.encode(&mut encoder).unwrap();
    }
    Peer::PeerUser(PeerUserConstructor { user_id: 7 })
        .encode(&mut encoder)
        .unwrap();
    SCHEDULE_WHEN_ONLINE.encode(&mut encoder).unwrap();
    String::from("Alice").encode(&mut encoder).unwrap();
    let bytes = encoder.into_bytes();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    let mut message = MessageConstructor::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    message.flags |= MessageConstructor::ENTITIES_FLAG;
    message.entities = Some(vector(vec![Box::new(
        MessageEntity::MessageEntityMentionName(MessageEntityMentionNameConstructor {
            offset: 0,
            length: 5,
            user_id: 42,
        }),
    )]));
    message.flags |= MessageConstructor::MEDIA_FLAG;
    message.media = Some(Box::new(MessageMedia::MessageMediaPhoto(
        MessageMediaPhotoConstructor {
            flags: MessageMediaPhotoConstructor::PHOTO_FLAG,
            spoiler: None,
            live_photo: None,
            ttl_seconds: None,
            video: None,
            photo: Some(Box::new(Photo::Photo(PhotoConstructor {
                flags: 0,
                has_stickers: None,
                id: 123,
                access_hash: 456,
                file_reference: vec![1],
                date: 100,
                dc_id: 2,
                video_sizes: None,
                sizes: vector(vec![Box::new(PhotoSize::PhotoSize(PhotoSizeConstructor {
                    type_: "x".into(),
                    w: 128,
                    h: 128,
                    size: 123,
                }))]),
            }))),
        },
    )));
    message
}

#[test]
fn unsupported_queue_entities_and_old_journal_records_cannot_be_edited() {
    let mut source = photo_message();
    source.entities = Some(vector(vec![Box::new(MessageEntity::MessageEntityUnknown(
        MessageEntityUnknownConstructor {
            offset: 0,
            length: 5,
        },
    ))]));
    let response = MessagesMessages::MessagesMessages(MessagesMessagesConstructor {
        messages: vector(vec![Box::new(Message::Message(source))]),
        topics: vector(Vec::new()),
        chats: vector(Vec::new()),
        users: vector(Vec::new()),
    });
    let result = map_response(
        response,
        &mut crate::HashMap::default(),
        &mut crate::media::MediaIndex::default(),
    )
    .unwrap();
    assert!(result[0].has_unsupported_content);
    assert!(require_editable(&result, 7, 37).is_err());
    let mut known = result[0].clone();
    known.has_unsupported_content = false;
    assert!(require_editable(&[known.clone()], 7, 37).is_ok());
    assert!(require_editable(&[known.clone()], 8, 37).is_err());
    assert!(require_editable(&[known.clone()], 7, 38).is_err());
    assert!(require_editable(&[known.clone(), known.clone()], 7, 37).is_err());
    assert!(require_editable(&[], 7, 37).is_err());
    let mut old = serde_json::to_value(known).unwrap();
    old.as_object_mut()
        .unwrap()
        .remove("has_unsupported_content");
    let restored: ScheduledMessageDto = serde_json::from_value(old).unwrap();
    assert!(restored.has_unsupported_content);
}

#[test]
fn rich_messages_and_unsupported_media_are_not_caption_editable() {
    let mut rich = photo_message();
    rich.rich_message = Some(Box::new(RichMessage::RichMessage(RichMessageConstructor {
        flags: 0,
        rtl: None,
        part: None,
        blocks: vector(Vec::new()),
        photos: vector(Vec::new()),
        documents: vector(Vec::new()),
    })));
    let mut unsupported = photo_message();
    unsupported.media = Some(Box::new(MessageMedia::MessageMediaUnsupported(
        MessageMediaUnsupportedConstructor {},
    )));
    let mut caption_above = photo_message();
    caption_above.invert_media = Some(Box::new(True::True(TrueConstructor {})));
    for source in [rich, unsupported, caption_above] {
        let response = MessagesMessages::MessagesMessages(MessagesMessagesConstructor {
            messages: vector(vec![Box::new(Message::Message(source))]),
            topics: vector(Vec::new()),
            chats: vector(Vec::new()),
            users: vector(Vec::new()),
        });
        let mapped = map_response(
            response,
            &mut crate::HashMap::default(),
            &mut crate::media::MediaIndex::default(),
        )
        .unwrap();
        assert!(mapped[0].has_unsupported_content);
        assert!(require_editable(&mapped, 7, 37).is_err());
    }
}

#[test]
fn scheduled_snapshots_preserve_ids_online_dates_and_named_mentions() {
    let message = photo_message();
    let response = MessagesMessages::MessagesMessages(MessagesMessagesConstructor {
        messages: vector(vec![Box::new(Message::Message(message))]),
        topics: vector(Vec::new()),
        chats: vector(Vec::new()),
        users: vector(Vec::new()),
    });
    let mut peers = crate::HashMap::default();
    let mut media = crate::media::MediaIndex::default();
    crate::dialogs::dtos_from_messages(response.clone(), &mut peers, &mut media).unwrap();
    let original = media.get(&(7, 37)).cloned().unwrap();
    let result = map_response(response, &mut peers, &mut media).unwrap();
    assert_eq!((result[0].message.chat_id, result[0].message.id), (7, 37));
    assert!(!result[0].has_unsupported_content);
    assert_eq!(result[0].message.date, i64::from(SCHEDULE_WHEN_ONLINE));
    assert_eq!(result[0].message.media_kind.as_deref(), Some("photo"));
    assert!(result[0].message.media_cache_key.is_some());
    assert_eq!(media.get(&(7, 37)), Some(&original));
    assert!(media.contains_key(&(7, -38)));
    let entities: serde_json::Value =
        serde_json::from_str(result[0].message.entities_json.as_ref().unwrap()).unwrap();
    assert_eq!(entities[0]["kind"], "mention_name");
    assert_eq!(entities[0]["url"], "42");
    assert!(
        map_response(
            MessagesMessages::MessagesMessagesNotModified(MessagesMessagesNotModifiedConstructor {
                count: 1
            }),
            &mut peers,
            &mut media
        )
        .is_err()
    );
}

#[test]
fn scheduled_pushes_use_distinct_media_identity_and_roundtrip_through_journal_events() {
    let mut media = crate::media::MediaIndex::default();
    let mut events = Vec::new();
    let update = Update::UpdateNewScheduledMessage(UpdateNewScheduledMessageConstructor {
        message: Box::new(Message::Message(photo_message())),
    });
    crate::updates::updates_rpc::collect_other_updates(
        std::iter::once(&update),
        &mut media,
        &mut Vec::new(),
        &mut events,
    );
    assert_eq!(events.len(), 1);
    let crate::UpdateEventDto::ScheduledMessageChanged { message } = &events[0] else {
        panic!("queue event required")
    };
    assert_eq!(message.message.id, 37);
    assert_eq!(message.message.date, i64::from(SCHEDULE_WHEN_ONLINE));
    assert!(
        message
            .message
            .entities_json
            .as_ref()
            .unwrap()
            .contains("mention_name")
    );
    assert!(media.contains_key(&(7, -38)));
    assert!(!media.contains_key(&(7, 37)));
    let encoded = serde_json::to_string(&events).unwrap();
    let decoded: Vec<crate::UpdateEventDto> = serde_json::from_str(&encoded).unwrap();
    assert!(
        matches!(&decoded[0], crate::UpdateEventDto::ScheduledMessageChanged { message } if message.message.id == 37)
    );
    let mut replacement = photo_message();
    replacement.media = None;
    replacement.flags &= !MessageConstructor::MEDIA_FLAG;
    let update = Update::UpdateNewScheduledMessage(UpdateNewScheduledMessageConstructor {
        message: Box::new(Message::Message(replacement)),
    });
    crate::updates::updates_rpc::collect_other_updates(
        std::iter::once(&update),
        &mut media,
        &mut Vec::new(),
        &mut events,
    );
    assert!(!media.contains_key(&(7, -38)));
}

#[test]
fn queue_deletion_retains_delivery_ids_without_deleting_normal_media_or_advancing_pts() {
    for sent in [None, Some(vec![500])] {
        let mut media = crate::media::MediaIndex::default();
        crate::dialogs::message_to_dto(&Message::Message(photo_message()), &mut media).unwrap();
        map_update(&Message::Message(photo_message()), &mut media).unwrap();
        let update =
            Update::UpdateDeleteScheduledMessages(UpdateDeleteScheduledMessagesConstructor {
                flags: if sent.is_some() {
                    UpdateDeleteScheduledMessagesConstructor::SENT_MESSAGES_FLAG
                } else {
                    0
                },
                peer: Box::new(Peer::PeerUser(PeerUserConstructor { user_id: 7 })),
                messages: vector(vec![37]),
                sent_messages: sent.clone().map(vector),
            });
        let mut cursor = crate::UpdatesStateDto {
            pts: 10,
            qts: 4,
            date: 100,
            seq: 2,
        };
        assert!(
            crate::updates::updates_rpc::advance_push_update(
                &update,
                &mut cursor,
                &mut crate::HashMap::default(),
                true,
                &mut Vec::new()
            )
            .unwrap()
        );
        assert_eq!(
            (cursor.pts, cursor.qts, cursor.seq, cursor.date),
            (10, 4, 2, 100)
        );
        let mut events = Vec::new();
        crate::updates::updates_rpc::collect_other_updates(
            std::iter::once(&update),
            &mut media,
            &mut Vec::new(),
            &mut events,
        );
        let crate::UpdateEventDto::ScheduledMessagesDeleted {
            chat_id,
            message_ids,
            sent_message_ids,
        } = &events[0]
        else {
            panic!("queue deletion required")
        };
        assert_eq!((*chat_id, message_ids.as_slice()), (7, &[37][..]));
        assert_eq!(*sent_message_ids, sent);
        assert!(media.contains_key(&(7, 37)));
        assert!(!media.contains_key(&(7, -38)));
    }
}

#[test]
fn selections_and_dates_are_validated_before_client_lookup() {
    for ids in [vec![], vec![0], vec![-1], vec![1, 1], (1..=101).collect()] {
        assert!(validate_ids(&ids).is_err());
        assert!(delete_scheduled_messages(0, 7, ids).is_err());
    }
    assert!(validate_ids(&(1..=100).collect::<Vec<_>>()).is_ok());
    for date in [-1, 0, i32::MAX] {
        assert!(validate_date(date).is_err());
    }
    assert!(validate_date(SCHEDULE_WHEN_ONLINE).is_ok());
}
