use super::*;
use tellers_mtproto::latest::api::*;

fn rows(messages: Vec<Message>) -> Vector<Box<Message>> {
    Vector::Vector(VectorConstructor {
        field_0: messages.len() as u32,
        field_1: messages.into_iter().map(Box::new).collect(),
    })
}

fn service(id: i32) -> Message {
    Message::MessageService(MessageServiceConstructor {
        flags: 0,
        out: None,
        mentioned: None,
        media_unread: None,
        reactions_are_possible: None,
        silent: None,
        post: None,
        legacy: None,
        id,
        from_id: Some(Box::new(Peer::PeerUser(PeerUserConstructor { user_id: 8 }))),
        peer_id: Box::new(Peer::PeerChat(PeerChatConstructor { chat_id: 3 })),
        saved_peer_id: None,
        reply_to: None,
        date: 1,
        action: Box::new(MessageAction::MessageActionChatEditTitle(
            MessageActionChatEditTitleConstructor {
                title: "fixture".into(),
            },
        )),
        reactions: None,
        ttl_period: None,
    })
}

#[test]
fn exact_lookup_accepts_only_one_matching_message() {
    let mut index = MediaIndex::default();
    let result = select_message(&rows(vec![service(37)]), &mut index, -3, 37)
        .unwrap()
        .unwrap();
    assert_eq!((result.chat_id, result.id), (-3, 37));
    assert!(select_message(&rows(vec![service(37)]), &mut index, -4, 37).is_err());
    assert!(select_message(&rows(vec![service(38)]), &mut index, -3, 37).is_err());
    assert!(select_message(&rows(vec![service(37), service(37)]), &mut index, -3, 37).is_err());
}

#[test]
fn deleted_lookup_requires_matching_empty_identity() {
    let mut index = MediaIndex::default();
    assert!(
        select_message(&rows(vec![]), &mut index, -3, 37)
            .unwrap()
            .is_none()
    );
    for (id, peer, valid) in [(37, 3, true), (38, 3, false), (37, 4, false)] {
        let empty = Message::MessageEmpty(MessageEmptyConstructor {
            flags: 1,
            id,
            peer_id: Some(Box::new(Peer::PeerChat(PeerChatConstructor {
                chat_id: peer,
            }))),
        });
        assert_eq!(
            select_message(&rows(vec![empty]), &mut index, -3, 37).is_ok(),
            valid
        );
    }
}

#[test]
fn lookup_rejects_local_ids_before_resolving_peers_or_connecting() {
    let mut snapshot =
        tellers_mtproto_session::Snapshot::new(2, &mut tellers_mtproto_session::OsRandom).unwrap();
    for id in [0, -1] {
        assert!(
            matches!(get_message(&mut snapshot, 1, &HashMap::default(), &mut MediaIndex::default(), -3, id),
            Err(MtprotoError::Message(ref value)) if value == "message id required")
        );
    }
}
