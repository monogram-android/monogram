use super::*;
use tellers_mtproto::latest::api::{
    MessagePeerVoteConstructor, MessagePeerVoteInputOptionConstructor,
    MessagePeerVoteMultipleConstructor, Peer, PeerUserConstructor,
};

#[test]
fn live_location_edits_validate_movement_and_encode_stop_without_extending_period() {
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    let peers = [(
        7,
        CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 7,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    for (id, lat, long, heading, stopped) in [
        (0, Some(0.0), Some(0.0), None, false),
        (37, Some(f64::NAN), Some(0.0), None, false),
        (37, Some(91.0), Some(0.0), None, false),
        (37, Some(0.0), Some(-181.0), None, false),
        (37, Some(0.0), None, None, false),
        (37, Some(0.0), Some(0.0), Some(0), false),
        (37, Some(0.0), Some(0.0), Some(361), false),
        (37, Some(0.0), None, None, true),
        (37, None, None, Some(1), true),
    ] {
        assert!(live_location_edit_request(&peers, 7, id, lat, long, heading, stopped).is_err());
    }
    for stopped in [false, true] {
        let request = live_location_edit_request(
            &peers,
            7,
            37,
            (!stopped).then_some(-90.0),
            (!stopped).then_some(180.0),
            (!stopped).then_some(360),
            stopped,
        )
        .unwrap();
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesEditMessageRequest::ID);
        let row = MessagesEditMessageRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(row.flags, MessagesEditMessageRequest::MEDIA_FLAG);
        assert!(
            row.message.is_none()
                && row.entities.is_none()
                && row.reply_markup.is_none()
                && row.schedule_date.is_none()
        );
        let InputMedia::InputMediaGeoLive(media) = *row.media.unwrap() else {
            panic!()
        };
        assert!(media.period.is_none() && media.proximity_notification_radius.is_none());
        assert_eq!(media.stopped.is_some(), stopped);
        if stopped {
            assert!(matches!(
                *media.geo_point,
                InputGeoPoint::InputGeoPointEmpty(_)
            ));
            assert!(media.heading.is_none());
        } else {
            let InputGeoPoint::InputGeoPoint(point) = *media.geo_point else {
                panic!()
            };
            assert_eq!((point.lat, point.long), (-90.0, 180.0));
            assert_eq!(media.heading, Some(360));
        }
    }
}

#[test]
fn checklist_append_checks_arithmetic_before_rpc_and_preserves_trimmed_text() {
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    let peers = [(
        7,
        CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 7,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    for (message_id, first, titles) in [
        (0, 1, vec!["A".into()]),
        (37, 1, vec![" ".into()]),
        (37, i32::MAX, vec!["A".into(), "B".into()]),
    ] {
        assert!(todo_append_request(&peers, 7, message_id, first, &titles).is_err());
    }
    let request = todo_append_request(
        &peers,
        7,
        37,
        0,
        &[" First ".into(), " ".into(), "Second".into()],
    )
    .unwrap();
    let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(
        decoder.read_u32().unwrap(),
        MessagesAppendTodoListRequest::ID
    );
    let row = MessagesAppendTodoListRequest::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    let items: Vec<_> = vector_boxed_items(&row.list)
        .map(|item| {
            let TodoItem::TodoItem(item) = item;
            let TextWithEntities::TextWithEntities(title) = item.title.as_ref();
            (item.id, title.text.as_str())
        })
        .collect();
    assert_eq!(items, vec![(0, "First"), (1, "Second")]);
    assert!(todo_append_request(&peers, 7, 37, i32::MAX, &["Last".into()]).is_ok());
}

#[test]
fn checklist_toggle_rejects_empty_duplicate_and_conflicting_ids_but_preserves_signed_identity() {
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    let peers = [(
        7,
        CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 7,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    for (complete, incomplete) in [
        (vec![], vec![]),
        (vec![1, 1], vec![]),
        (vec![1], vec![1]),
        (vec![], vec![1, 1]),
    ] {
        assert!(todo_toggle_request(&peers, 7, 37, &complete, &incomplete).is_err());
    }
    let request = todo_toggle_request(&peers, 7, 37, &[0, -7], &[i32::MAX]).unwrap();
    let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(
        decoder.read_u32().unwrap(),
        MessagesToggleTodoCompletedRequest::ID
    );
    let row = MessagesToggleTodoCompletedRequest::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    assert_eq!(vector_items(&row.completed), &[0, -7]);
    assert_eq!(vector_items(&row.incompleted), &[i32::MAX]);
}

#[test]
fn viewer_pages_encode_opaque_offsets_and_bounded_limits() {
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    use tellers_mtproto::latest::api::{InputPeer, InputPeerSelfConstructor};
    for offset in [None, Some("opaque+/=cursor")] {
        let request = reaction_page_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            37,
            offset,
        );
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(
            decoder.read_u32().unwrap(),
            MessagesGetMessageReactionsListRequest::ID
        );
        let row = MessagesGetMessageReactionsListRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(row.offset.as_deref(), offset);
        assert_eq!(row.limit, 50);
        assert_eq!(row.id, 37);
        let request = poll_page_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            37,
            offset,
        );
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesGetPollVotesRequest::ID);
        let row = MessagesGetPollVotesRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(row.offset.as_deref(), offset);
        assert_eq!(row.limit, 50);
        assert_eq!(row.id, 37);
    }
}

#[test]
fn vote_requests_encode_retraction_and_all_selected_options_without_normalizing_bytes() {
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    let peers = [(
        7,
        CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 7,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    for options in [vec![], vec![vec![0, 255], vec![3, 0, 7]]] {
        let request = poll_vote_request(&peers, 7, 37, &options).unwrap();
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesSendVoteRequest::ID);
        let decoded = MessagesSendVoteRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(decoded.msg_id, 37);
        assert_eq!(
            crate::peers::vector_items(&decoded.options),
            options.as_slice()
        );
    }
    for id in [0, -1] {
        assert!(matches!(poll_vote_request(&HashMap::default(), 7, id, &[]),
            Err(MtprotoError::Message(ref message)) if message == "message id required"));
    }
    assert!(poll_vote_request(&HashMap::default(), 7, 37, &[]).is_err());
}

fn user_peer(id: i64) -> Box<Peer> {
    Box::new(Peer::PeerUser(PeerUserConstructor { user_id: id }))
}

#[test]
fn single_vote_keeps_option_bytes() {
    let row = MessagePeerVote::MessagePeerVote(MessagePeerVoteConstructor {
        peer: user_peer(7),
        option: vec![0x01, 0xa0],
        date: 11,
    });
    assert_eq!(poll_vote_options(&row), vec![vec![0x01, 0xa0]]);
}

#[test]
fn input_option_vote_has_no_bytes() {
    let row = MessagePeerVote::MessagePeerVoteInputOption(MessagePeerVoteInputOptionConstructor {
        peer: user_peer(7),
        date: 11,
    });
    assert!(poll_vote_options(&row).is_empty());
}

#[test]
fn multiple_vote_keeps_every_option() {
    let row = MessagePeerVote::MessagePeerVoteMultiple(MessagePeerVoteMultipleConstructor {
        peer: user_peer(7),
        options: Box::new(Vector::Vector(VectorConstructor {
            field_0: 2,
            field_1: vec![b"a".to_vec(), b"b".to_vec()],
        })),
        date: 11,
    });
    assert_eq!(poll_vote_options(&row), vec![b"a".to_vec(), b"b".to_vec()]);
}
