use super::*;
use tellers_mtproto::codec::{Decoder, Encoder, Limits, TlDecode, TlEncode};

fn vector<T>(items: Vec<T>) -> Box<Vector<T>> {
    Box::new(Vector::Vector(VectorConstructor {
        field_0: items.len() as u32,
        field_1: items,
    }))
}

fn message() -> Message {
    let mut encoder = Encoder::new();
    for value in [0u32, 0, 37] {
        value.encode(&mut encoder).unwrap();
    }
    Peer::PeerUser(PeerUserConstructor { user_id: 7 })
        .encode(&mut encoder)
        .unwrap();
    100i32.encode(&mut encoder).unwrap();
    String::from("caption").encode(&mut encoder).unwrap();
    let bytes = encoder.into_bytes();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    let result = Message::Message(MessageConstructor::decode(&mut decoder).unwrap());
    decoder.finish().unwrap();
    result
}

fn response(items: Vec<Box<Update>>) -> Updates {
    Updates::Updates(UpdatesConstructor {
        updates: vector(items),
        users: vector(Vec::new()),
        chats: vector(Vec::new()),
        date: 100,
        seq: 1,
    })
}

#[test]
fn album_options_encode_silent_and_schedule_independently() {
    for silent in [false, true] {
        for schedule_date in [None, Some(crate::client::scheduled::SCHEDULE_WHEN_ONLINE)] {
            let mut request = MessagesSendMultiMediaRequest {
                flags: 0,
                silent: None,
                background: None,
                clear_draft: None,
                noforwards: None,
                update_stickersets_order: None,
                invert_media: None,
                allow_paid_floodskip: None,
                peer: Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
                reply_to: None,
                multi_media: vector(Vec::new()),
                schedule_date: None,
                send_as: None,
                quick_reply_shortcut: None,
                effect: None,
                allow_paid_stars: None,
            };
            SendOptions {
                silent,
                schedule_date,
                no_webpage: true,
            }
            .apply_album(&mut request);
            let mut encoded = Encoder::new();
            request.encode(&mut encoded).unwrap();
            let bytes = encoded.into_bytes();
            let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
            let decoded = MessagesSendMultiMediaRequest::decode(&mut decoder).unwrap();
            decoder.finish().unwrap();
            assert_eq!(decoded.silent.is_some(), silent);
            assert_eq!(decoded.schedule_date, schedule_date);
            assert!(decoded.allow_paid_stars.is_none() && decoded.allow_paid_floodskip.is_none());
        }
    }
}

#[test]
fn album_results_bind_each_random_id_in_request_order_and_ignore_unrelated_updates() {
    let mut updates = Vec::new();
    for (id, random) in [(38, Some(456)), (99, None), (37, Some(123))] {
        if let Some(random_id) = random {
            updates.push(Box::new(Update::UpdateMessageId(
                UpdateMessageIdConstructor { id, random_id },
            )));
        }
        let mut item = message();
        if let Message::Message(value) = &mut item {
            value.id = id;
        }
        updates.push(Box::new(Update::UpdateNewMessage(
            UpdateNewMessageConstructor {
                message: Box::new(item),
                pts: 1,
                pts_count: 1,
            },
        )));
    }
    let result = album_send_results(
        response(updates),
        7,
        &[(123, "one"), (456, "two")],
        &crate::HashMap::default(),
        &mut crate::media::MediaIndex::default(),
    )
    .unwrap();
    let ids = result
        .iter()
        .map(|result| match result {
            OutgoingMessageResult::Sent { message } => message.id,
            _ => panic!("immediate result required"),
        })
        .collect::<Vec<_>>();
    assert_eq!(ids, vec![37, 38]);
}

#[test]
fn album_results_reject_missing_duplicate_and_foreign_acknowledgments() {
    for acknowledgments in [
        vec![(37, 123)],
        vec![(37, 123), (38, 123)],
        vec![(37, 123), (37, 456)],
        vec![(37, 123), (38, 999)],
        vec![(37, 123), (0, 456)],
    ] {
        let updates = acknowledgments
            .into_iter()
            .map(|(id, random_id)| {
                Box::new(Update::UpdateMessageId(UpdateMessageIdConstructor {
                    id,
                    random_id,
                }))
            })
            .collect();
        assert!(
            album_send_results(
                response(updates),
                7,
                &[(123, "one"), (456, "two")],
                &crate::HashMap::default(),
                &mut crate::media::MediaIndex::default()
            )
            .is_err()
        );
    }
    for requests in [vec![], vec![(0, "zero")], vec![(123, "one"), (123, "two")]] {
        assert!(
            album_send_results(
                response(Vec::new()),
                7,
                &requests,
                &crate::HashMap::default(),
                &mut crate::media::MediaIndex::default()
            )
            .is_err()
        );
    }
    let updates = vec![
        Box::new(Update::UpdateMessageId(UpdateMessageIdConstructor {
            id: 37,
            random_id: 123,
        })),
        Box::new(Update::UpdateMessageId(UpdateMessageIdConstructor {
            id: 38,
            random_id: 456,
        })),
        Box::new(Update::UpdateNewMessage(UpdateNewMessageConstructor {
            message: Box::new(message()),
            pts: 1,
            pts_count: 1,
        })),
    ];
    assert!(
        album_send_results(
            response(updates),
            7,
            &[(123, "one"), (456, "two")],
            &crate::HashMap::default(),
            &mut crate::media::MediaIndex::default()
        )
        .is_err()
    );
}

#[test]
fn album_results_keep_scheduled_identity_and_reject_foreign_peers() {
    let mut updates = Vec::new();
    for (id, random_id) in [(37, 123), (38, 456)] {
        let mut item = message();
        if let Message::Message(value) = &mut item {
            value.id = id;
        }
        updates.push(Box::new(Update::UpdateMessageId(
            UpdateMessageIdConstructor { id, random_id },
        )));
        updates.push(Box::new(Update::UpdateNewScheduledMessage(
            UpdateNewScheduledMessageConstructor {
                message: Box::new(item),
            },
        )));
    }
    let results = album_send_results(
        response(updates.clone()),
        7,
        &[(123, "one"), (456, "two")],
        &crate::HashMap::default(),
        &mut crate::media::MediaIndex::default(),
    )
    .unwrap();
    for (result, id) in results.into_iter().zip([37, 38]) {
        let OutgoingMessageResult::Scheduled { message } = result else {
            panic!("scheduled result required")
        };
        assert_eq!(message.message.id, id);
        assert_eq!(message.message.chat_id, 7);
    }
    if let Update::UpdateNewScheduledMessage(value) = updates[3].as_mut() {
        if let Message::Message(item) = value.message.as_mut() {
            item.peer_id = Box::new(Peer::PeerUser(PeerUserConstructor { user_id: 9 }));
        }
    }
    assert!(
        album_send_results(
            response(updates),
            7,
            &[(123, "one"), (456, "two")],
            &crate::HashMap::default(),
            &mut crate::media::MediaIndex::default()
        )
        .is_err()
    );
}

#[test]
fn immediate_result_uses_acknowledged_id_and_rejects_cross_namespace_ambiguity() {
    let mut unrelated = message();
    if let Message::Message(value) = &mut unrelated {
        value.id = 38;
    }
    let mut items = vec![
        Box::new(Update::UpdateMessageId(UpdateMessageIdConstructor {
            id: 37,
            random_id: 123,
        })),
        Box::new(Update::UpdateNewMessage(UpdateNewMessageConstructor {
            message: Box::new(unrelated),
            pts: 1,
            pts_count: 1,
        })),
        Box::new(Update::UpdateNewMessage(UpdateNewMessageConstructor {
            message: Box::new(message()),
            pts: 2,
            pts_count: 1,
        })),
    ];
    let mut media = crate::media::MediaIndex::default();
    let result = send_result(
        response(items.clone()),
        7,
        "caption",
        123,
        &crate::HashMap::default(),
        &mut media,
    )
    .unwrap();
    let OutgoingMessageResult::Sent { message } = &result else {
        panic!("immediate result required")
    };
    assert_eq!(message.id, 37);
    assert!(implicit_ack(&response(items.clone()), &result, 123).is_none());
    items.push(Box::new(Update::UpdateNewScheduledMessage(
        UpdateNewScheduledMessageConstructor {
            message: Box::new(self::message()),
        },
    )));
    assert!(
        send_result(
            response(items),
            7,
            "caption",
            123,
            &crate::HashMap::default(),
            &mut media
        )
        .is_err()
    );
}

#[test]
fn short_send_confirmation_produces_an_uncounted_random_id_acknowledgment() {
    let updates = Updates::UpdateShortSentMessage(UpdateShortSentMessageConstructor {
        flags: 0,
        out: None,
        id: 37,
        pts: 12,
        pts_count: 1,
        date: 100,
        media: None,
        entities: None,
        ttl_period: None,
    });
    let result = send_result(
        updates.clone(),
        7,
        "caption",
        123,
        &crate::HashMap::default(),
        &mut crate::media::MediaIndex::default(),
    )
    .unwrap();
    let Updates::UpdateShort(value) = implicit_ack(&updates, &result, 123).unwrap() else {
        panic!("uncounted ack required")
    };
    assert_eq!(value.date, 100);
    let Update::UpdateMessageId(ack) = *value.update else {
        panic!("message-id ack required")
    };
    assert_eq!((ack.id, ack.random_id), (37, 123));
}

#[test]
fn media_send_flags_preserve_schedule_silent_and_random_id() {
    let mut request = MessagesSendMediaRequest {
        flags: 0,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
        reply_to: None,
        media: Box::new(InputMedia::InputMediaEmpty(InputMediaEmptyConstructor {})),
        message: "caption".into(),
        random_id: 123,
        reply_markup: None,
        entities: None,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
        suggested_post: None,
    };
    SendOptions {
        silent: true,
        no_webpage: false,
        schedule_date: Some(100),
    }
    .apply_media(&mut request);
    let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(decoder.read_u32().unwrap(), MessagesSendMediaRequest::ID);
    let decoded = MessagesSendMediaRequest::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    assert!(decoded.silent.is_some());
    assert_eq!(decoded.schedule_date, Some(100));
    assert_eq!(decoded.random_id, 123);
    assert!(decoded.clear_draft.is_none());
    assert!(decoded.allow_paid_stars.is_none());
}

#[test]
fn silent_and_schedule_options_roundtrip_independently_without_changing_random_id() {
    for silent in [false, true] {
        for date in [
            None,
            Some(100),
            Some(crate::scheduled::SCHEDULE_WHEN_ONLINE),
        ] {
            let options = SendOptions {
                silent,
                no_webpage: true,
                schedule_date: date,
            };
            let mut request = crate::messages::send_message_request(
                0,
                Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
                None,
                "text".into(),
                123,
                None,
            );
            options.apply_text(&mut request);
            let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
            let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
            assert_eq!(decoder.read_u32().unwrap(), MessagesSendMessageRequest::ID);
            let decoded = MessagesSendMessageRequest::decode(&mut decoder).unwrap();
            decoder.finish().unwrap();
            assert_eq!(decoded.silent.is_some(), silent);
            assert!(decoded.no_webpage.is_some());
            assert_eq!(decoded.schedule_date, date);
            assert_eq!(decoded.random_id, 123);
            assert!(decoded.clear_draft.is_none());
            assert!(decoded.allow_paid_stars.is_none());
        }
    }
}

#[test]
fn scheduled_send_requires_caller_owned_random_id_and_valid_date() {
    assert!(SendOptions::default().validate(0).is_ok());
    for date in [0, -1, i32::MAX] {
        assert!(
            SendOptions {
                silent: false,
                no_webpage: false,
                schedule_date: Some(date)
            }
            .validate(123)
            .is_err()
        );
    }
    let options = SendOptions {
        silent: true,
        no_webpage: false,
        schedule_date: Some(100),
    };
    assert!(options.validate(0).is_err());
    assert!(options.validate(-123).is_ok());
}

#[test]
fn scheduled_result_requires_matching_ack_and_stays_out_of_normal_history() {
    for random_id in [123, 456] {
        let updates = Updates::Updates(UpdatesConstructor {
            updates: vector(vec![
                Box::new(Update::UpdateMessageId(UpdateMessageIdConstructor {
                    id: 37,
                    random_id,
                })),
                Box::new(Update::UpdateNewScheduledMessage(
                    UpdateNewScheduledMessageConstructor {
                        message: Box::new(message()),
                    },
                )),
            ]),
            users: vector(Vec::new()),
            chats: vector(Vec::new()),
            date: 100,
            seq: 1,
        });
        let mut media = crate::media::MediaIndex::default();
        let result = send_result(
            updates,
            7,
            "caption",
            123,
            &crate::HashMap::default(),
            &mut media,
        );
        if random_id != 123 {
            assert!(result.is_err());
            continue;
        }
        let OutgoingMessageResult::Scheduled { message } = result.unwrap() else {
            panic!("queue result required")
        };
        assert_eq!((message.message.chat_id, message.message.id), (7, 37));
    }
    assert!(
        send_result(
            Updates::UpdatesTooLong(UpdatesTooLongConstructor {}),
            7,
            "caption",
            123,
            &crate::HashMap::default(),
            &mut crate::media::MediaIndex::default()
        )
        .is_err()
    );
}
