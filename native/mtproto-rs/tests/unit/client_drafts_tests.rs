use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

fn local(thread_id: i32) -> CloudDraft {
    CloudDraft {
        chat_id: 7,
        thread_id,
        text: "Hello".into(),
        entities_json: Some(r#"[{"kind":"bold","offset":0,"length":5}]"#.into()),
        reply_to_message_id: 42,
        date: 10,
        no_webpage: true,
        has_unsupported_content: false,
    }
}

fn update(draft: DraftMessage) -> UpdateDraftMessageConstructor {
    UpdateDraftMessageConstructor {
        flags: 1,
        peer: Box::new(Peer::PeerUser(PeerUserConstructor { user_id: 7 })),
        top_msg_id: Some(99),
        saved_peer_id: None,
        draft: Box::new(draft),
    }
}

#[test]
fn saved_named_mentions_use_full_input_user_references() {
    let mut draft = local(0);
    draft.entities_json =
        Some(r#"[{"kind":"mention_name","offset":0,"length":5,"url":"42"}]"#.into());
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
    let request = save_request(
        InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
        &draft,
        &peers,
    )
    .unwrap();
    assert!(matches!(
        crate::peers::vector_items(request.entities.as_ref().unwrap())[0].as_ref(),
        MessageEntity::InputMessageEntityMentionName(_)
    ));
    assert!(
        save_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            &draft,
            &crate::HashMap::default()
        )
        .is_err()
    );
}

#[test]
fn save_flags_preserve_text_entities_reply_and_thread_context() {
    for thread in [0, 99] {
        let request = save_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            &local(thread),
            &crate::HashMap::default(),
        )
        .unwrap();
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesSaveDraftRequest::ID);
        let result = MessagesSaveDraftRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert!(result.no_webpage.is_some());
        assert_eq!(result.message, "Hello");
        assert_eq!(
            crate::peers::vector_items(result.entities.as_ref().unwrap()).len(),
            1
        );
        let InputReplyTo::InputReplyToMessage(reply) = *result.reply_to.unwrap() else {
            panic!("message reply required")
        };
        assert_eq!(reply.reply_to_msg_id, 42);
        assert_eq!(reply.top_msg_id, (thread != 0).then_some(thread));
        assert!(result.media.is_none());
    }
}

#[test]
fn clearing_a_thread_draft_keeps_its_thread_and_deletion_timestamp() {
    let mut draft = local(99);
    draft.text.clear();
    draft.entities_json = None;
    draft.reply_to_message_id = 0;
    draft.no_webpage = false;
    let request = save_request(
        InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
        &draft,
        &crate::HashMap::default(),
    )
    .unwrap();
    assert!(request.message.is_empty());
    let InputReplyTo::InputReplyToMessage(reply) = *request.reply_to.unwrap() else {
        panic!("thread context required")
    };
    assert_eq!((reply.reply_to_msg_id, reply.top_msg_id), (99, Some(99)));
    for date in [None, Some(456)] {
        let result = draft_from_update(
            &update(DraftMessage::DraftMessageEmpty(
                DraftMessageEmptyConstructor {
                    flags: u32::from(date.is_some()),
                    date,
                },
            )),
            123,
        );
        assert_eq!(result.date, date.unwrap_or(123));
        assert_eq!((result.chat_id, result.thread_id), (7, 99));
        assert!(result.text.is_empty());
    }
}

#[test]
fn decoded_formatting_round_trips_and_lossy_entities_are_protected() {
    let request = save_request(
        InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
        &local(99),
        &crate::HashMap::default(),
    )
    .unwrap();
    let mut remote = DraftMessageConstructor {
        flags: request.flags,
        no_webpage: request.no_webpage,
        invert_media: None,
        reply_to: request.reply_to,
        message: request.message,
        entities: request.entities,
        media: None,
        date: 123,
        effect: None,
        suggested_post: None,
        rich_message: None,
    };
    let result = draft_from_update(&update(DraftMessage::DraftMessage(remote.clone())), 0);
    assert_eq!(result.reply_to_message_id, 42);
    assert_eq!(result.date, 123);
    assert!(!result.has_unsupported_content);
    remote.entities = Some(Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(MessageEntity::MessageEntityMentionName(
            MessageEntityMentionNameConstructor {
                offset: 0,
                length: 5,
                user_id: 123,
            },
        ))],
    })));
    let result = draft_from_update(&update(DraftMessage::DraftMessage(remote.clone())), 0);
    assert!(!result.has_unsupported_content);
    assert!(
        result
            .entities_json
            .as_deref()
            .unwrap()
            .contains("mention_name")
    );
    remote.entities = Some(Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(MessageEntity::MessageEntityUnknown(
            MessageEntityUnknownConstructor {
                offset: 0,
                length: 5,
            },
        ))],
    })));
    let result = draft_from_update(&update(DraftMessage::DraftMessage(remote)), 0);
    assert!(result.has_unsupported_content);
    assert!(
        save_cloud_draft(0, result)
            .unwrap_err()
            .to_string()
            .contains("content")
    );
}
