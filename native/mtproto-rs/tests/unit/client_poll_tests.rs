use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
fn poll(creator: bool, closed: bool) -> PollConstructor {
    PollConstructor {
        id: 123,
        flags: 0,
        closed: closed.then(|| Box::new(True::True(TrueConstructor {}))),
        creator: creator.then(|| Box::new(True::True(TrueConstructor {}))),
        public_voters: None,
        multiple_choice: None,
        quiz: None,
        open_answers: None,
        revoting_disabled: None,
        shuffle_answers: None,
        hide_results_until_close: None,
        subscribers_only: None,
        question: Box::new(TextWithEntities::TextWithEntities(
            TextWithEntitiesConstructor {
                text: "Fixture".into(),
                entities: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 0,
                    field_1: vec![],
                })),
            },
        )),
        answers: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![],
        })),
        close_period: None,
        close_date: None,
        countries_iso2: None,
        hash: 0,
    }
}
#[test]
fn close_media_requires_creator_and_closed_poll_is_idempotent() {
    assert!(close_poll_media(&poll(false, false)).is_err());
    assert!(close_poll_media(&poll(true, true)).unwrap().is_none());
    let mut original = poll(true, false);
    original.answers = Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(PollAnswer::PollAnswer(PollAnswerConstructor {
            flags: 0,
            text: original.question.clone(),
            option: vec![0, 255, 128],
            media: None,
            added_by: None,
            date: None,
        }))],
    }));
    let media = close_poll_media(&original).unwrap().unwrap();
    let InputMedia::InputMediaPoll(media) = media else {
        panic!()
    };
    let bytes = crate::rpc::encode_boxed_bytes(&media).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(decoder.read_u32().unwrap(), InputMediaPollConstructor::ID);
    let media = InputMediaPollConstructor::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    assert!(media.correct_answers.is_none());
    assert!(media.solution.is_none());
    let Poll::Poll(closed) = *media.poll;
    assert_eq!(closed.id, 123);
    let answer = crate::peers::vector_boxed_items(&closed.answers)
        .next()
        .unwrap();
    let PollAnswer::PollAnswer(answer) = answer else {
        panic!()
    };
    assert_eq!(answer.option, vec![0, 255, 128]);
    assert!(closed.closed.is_some());
    assert!(closed.creator.is_none());
    let TextWithEntities::TextWithEntities(question) = *closed.question;
    assert_eq!(question.text, "Fixture");
}
#[test]
fn close_rejects_nonpositive_ids_before_client_access() {
    for id in [0, -1] {
        assert!(
            matches!(close_poll(0,7,id),Err(MtprotoError::Message(ref value)) if value=="message id required")
        );
    }
}
#[test]
fn close_rejects_empty_or_deleted_messages() {
    let messages = Vector::Vector(VectorConstructor {
        field_0: 0,
        field_1: vec![],
    });
    let peer = InputPeer::InputPeerSelf(InputPeerSelfConstructor {});
    assert!(close_request(&messages, peer, 7, 37).is_err());
}

fn raw_message(sender_id: i64, id: i32) -> tellers_mtproto::latest::api::Message {
    use tellers_mtproto::latest::api::{
        Message, MessageConstructor, Peer, PeerChatConstructor, PeerUserConstructor,
    };
    Message::Message(MessageConstructor {
        flags: 0,
        out: None,
        mentioned: None,
        media_unread: None,
        silent: None,
        post: None,
        from_scheduled: None,
        legacy: None,
        edit_hide: None,
        pinned: None,
        noforwards: None,
        invert_media: None,
        flags2: 0,
        offline: None,
        video_processing_pending: None,
        paid_suggested_post_stars: None,
        paid_suggested_post_ton: None,
        id,
        from_id: Some(Box::new(Peer::PeerUser(PeerUserConstructor {
            user_id: sender_id,
        }))),
        from_boosts_applied: None,
        from_rank: None,
        peer_id: Box::new(Peer::PeerChat(PeerChatConstructor { chat_id: 3 })),
        saved_peer_id: None,
        fwd_from: None,
        via_bot_id: None,
        via_business_bot_id: None,
        guestchat_via_from: None,
        reply_to: None,
        date: 1,
        message: "hi".into(),
        media: None,
        reply_markup: None,
        entities: None,
        views: None,
        forwards: None,
        replies: None,
        edit_date: None,
        post_author: None,
        grouped_id: None,
        reactions: None,
        restriction_reason: None,
        ttl_period: None,
        quick_reply_shortcut_id: None,
        effect: None,
        factcheck: None,
        report_delivery_until_date: None,
        paid_message_stars: None,
        suggested_post: None,
        schedule_repeat_period: None,
        summary_from_language: None,
        rich_message: None,
    })
}

#[test]
fn close_request_checks_identity_and_encodes_media_only_edit() {
    let mut row = raw_message(8, 37);
    let Message::Message(message) = &mut row else {
        panic!()
    };
    message.media = Some(Box::new(MessageMedia::MessageMediaPoll(
        MessageMediaPollConstructor {
            flags: 0,
            poll: Box::new(Poll::Poll(poll(true, false))),
            attached_media: None,
            results: Box::new(PollResults::PollResults(PollResultsConstructor {
                flags: 0,
                min: None,
                has_unread_votes: None,
                can_view_stats: None,
                results: None,
                total_voters: None,
                recent_voters: None,
                solution: None,
                solution_entities: None,
                solution_media: None,
            })),
        },
    )));
    let rows = Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(row.clone())],
    });
    let peer = || InputPeer::InputPeerChat(InputPeerChatConstructor { chat_id: 3 });
    assert!(close_request(&rows, peer(), -4, 37).is_err());
    assert!(close_request(&rows, peer(), -3, 38).is_err());
    let request = close_request(&rows, peer(), -3, 37).unwrap().unwrap();
    let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(decoder.read_u32().unwrap(), MessagesEditMessageRequest::ID);
    let request = MessagesEditMessageRequest::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    assert_eq!(request.flags, MessagesEditMessageRequest::MEDIA_FLAG);
    assert_eq!(request.id, 37);
    assert!(request.message.is_none());
    assert!(request.entities.is_none());
    assert!(request.schedule_date.is_none());
    assert!(request.reply_markup.is_none());
    let duplicate = Vector::Vector(VectorConstructor {
        field_0: 2,
        field_1: vec![Box::new(row.clone()), Box::new(row)],
    });
    assert!(close_request(&duplicate, peer(), -3, 37).is_err());
}
