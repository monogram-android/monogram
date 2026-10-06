use super::*;

use crate::{HashMap, HashMapExt};

use tellers_mtproto::codec::{Encoder, TlEncode};
use tellers_mtproto::latest::api::{
    MessageReactions, MessageReactionsConstructor, Peer, PeerUserConstructor, Update,
    UpdateBotStoppedConstructor, UpdateChannelReadMessagesContentsConstructor,
    UpdateChannelTooLongConstructor, UpdateDeleteChannelMessagesConstructor,
    UpdateDeleteMessagesConstructor, UpdateMessageReactionsConstructor,
    UpdatePtsChangedConstructor, UpdateReadChannelDiscussionInboxConstructor,
    UpdateReadMessagesContentsConstructor, UpdateShortConstructor, Updates, UpdatesConstructor,
    Vector, VectorConstructor,
};

#[test]
fn too_long_uses_higher_dialog_pts() {
    assert_eq!(next_channel_pts_after_too_long(10, 40, 3), 40);
}

#[test]
fn too_long_never_invents_pts_from_message_count() {
    assert_eq!(next_channel_pts_after_too_long(10, 10, 0), 10);
    assert_eq!(next_channel_pts_after_too_long(10, 8, 5), 10);
}

#[test]
fn discussion_receipt_preserves_thread_and_read_boundary() {
    let update =
        Update::UpdateReadChannelDiscussionInbox(UpdateReadChannelDiscussionInboxConstructor {
            flags: 0,
            channel_id: 42,
            top_msg_id: 100,
            read_max_id: 120,
            broadcast_id: None,
            broadcast_post: None,
        });
    let mut events = Vec::new();
    collect_other_updates(
        std::iter::once(&update),
        &mut MediaIndex::new(),
        &mut Vec::new(),
        &mut events,
    );
    assert!(matches!(
        events.first(),
        Some(UpdateEventDto::DiscussionInbox {
            channel_id, top_message_id: 100, read_max_id: 120,
        }) if *channel_id == chat_id_for_channel(42)
    ));
    assert!(matches!(events.get(1), Some(UpdateEventDto::ChatsChanged)));
}

#[test]
fn reaction_snapshot_requests_one_authoritative_dialog_refresh_even_when_empty() {
    let reactions = MessageReactions::MessageReactions(MessageReactionsConstructor {
        flags: 0,
        min: None,
        can_see_list: None,
        reactions_as_tags: None,
        results: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: Vec::new(),
        })),
        recent_reactions: None,
        top_reactors: None,
    });
    let update = Update::UpdateMessageReactions(UpdateMessageReactionsConstructor {
        flags: 0,
        peer: Box::new(Peer::PeerUser(PeerUserConstructor { user_id: 42 })),
        msg_id: 9,
        top_msg_id: None,
        saved_peer_id: None,
        reactions: Box::new(reactions),
    });
    let mut events = Vec::new();
    collect_other_updates(
        std::iter::repeat(&update).take(2),
        &mut MediaIndex::new(),
        &mut Vec::new(),
        &mut events,
    );
    assert_eq!(
        events
            .iter()
            .filter(|event| matches!(event, UpdateEventDto::ChatsChanged))
            .count(),
        1,
    );
    assert!(events.iter().all(|event| !matches!(
        event,
        UpdateEventDto::Ignored { kind } if kind.starts_with("unread_reactions_delta:")
    )));
}

#[test]
fn remote_message_contents_reads_request_authoritative_dialog_refresh() {
    let regular = Update::UpdateReadMessagesContents(UpdateReadMessagesContentsConstructor {
        flags: 0,
        messages: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![9],
        })),
        pts: 11,
        pts_count: 1,
        date: None,
    });
    let channel =
        Update::UpdateChannelReadMessagesContents(UpdateChannelReadMessagesContentsConstructor {
            flags: 0,
            channel_id: 42,
            top_msg_id: None,
            saved_peer_id: None,
            messages: Box::new(Vector::Vector(VectorConstructor {
                field_0: 1,
                field_1: vec![9],
            })),
        });
    assert!(
        advance_push_update(
            &channel,
            &mut cursor(),
            &mut HashMap::new(),
            true,
            &mut Vec::new(),
        )
        .unwrap()
    );
    let mut events = Vec::new();
    collect_other_updates(
        [&regular, &channel].into_iter(),
        &mut MediaIndex::new(),
        &mut Vec::new(),
        &mut events,
    );
    assert_eq!(events.len(), 1);
    assert!(matches!(events.first(), Some(UpdateEventDto::ChatsChanged)));
}

#[test]
fn update_message_id_emits_random_mapping() {
    let update =
        Update::UpdateMessageId(tellers_mtproto::latest::api::UpdateMessageIdConstructor {
            id: 44,
            random_id: 99,
        });
    let mut events = Vec::new();
    collect_other_updates(
        std::iter::once(&update),
        &mut MediaIndex::new(),
        &mut Vec::new(),
        &mut events,
    );
    match events.as_slice() {
        [UpdateEventDto::Ignored { kind }] => {
            assert_eq!(kind, "UpdateMessageId:99:44");
        }
        other => panic!("{other:?}"),
    }
}

fn cursor() -> UpdatesStateDto {
    UpdatesStateDto {
        pts: 10,
        qts: 2,
        seq: 3,
        date: 100,
    }
}

fn delete(pts: i32) -> Update {
    Update::UpdateDeleteMessages(UpdateDeleteMessagesConstructor {
        messages: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![7],
        })),
        pts,
        pts_count: 1,
    })
}

fn packet(seq: i32, updates: Vec<Update>) -> Vec<u8> {
    let value = Updates::Updates(UpdatesConstructor {
        updates: Box::new(Vector::Vector(VectorConstructor {
            field_0: updates.len() as u32,
            field_1: updates.into_iter().map(Box::new).collect(),
        })),
        users: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: Vec::new(),
        })),
        chats: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: Vec::new(),
        })),
        date: 101,
        seq,
    });
    encode(value)
}

fn encode(value: Updates) -> Vec<u8> {
    let mut encoder = Encoder::new();
    value.encode(&mut encoder).unwrap();
    encoder.into_bytes()
}

fn apply(bytes: &[u8], cursor: &mut UpdatesStateDto) -> Result<Vec<UpdateEventDto>, MtprotoError> {
    apply_push(
        bytes,
        &mut HashMap::new(),
        &mut HashMap::new(),
        cursor,
        &mut HashMap::new(),
        &mut Vec::new(),
    )
}

#[test]
fn push_continuity_and_duplicate_suppression() {
    let mut state = cursor();
    let bytes = packet(4, vec![delete(11)]);
    assert_eq!(apply(&bytes, &mut state).unwrap().len(), 1);
    assert_eq!((state.pts, state.seq, state.date), (11, 4, 101));
    assert!(apply(&bytes, &mut state).unwrap().is_empty());
    // Pts belongs to a separate sequence even if outer seq was seen.
    assert_eq!(
        apply(&packet(4, vec![delete(12)]), &mut state)
            .unwrap()
            .len(),
        1
    );
    assert_eq!(state.pts, 12);
}

#[test]
fn push_gap_and_unsupported_update_roll_back_entire_packet() {
    for bytes in [
        packet(5, vec![delete(11)]),
        packet(4, vec![delete(11), delete(13)]),
        packet(
            4,
            vec![
                delete(11),
                Update::UpdatePtsChanged(UpdatePtsChangedConstructor {}),
            ],
        ),
    ] {
        let mut state = cursor();
        assert!(apply(&bytes, &mut state).is_err());
        assert_eq!(
            (state.pts, state.qts, state.seq, state.date),
            (10, 2, 3, 100)
        );
    }
}

#[test]
fn push_counter_checks_zero_count_overflow_and_qts() {
    let mut local = 10;
    assert!(advance_push_counter(&mut local, 10, 0).unwrap());
    assert!(!advance_push_counter(&mut local, 9, 0).unwrap());
    assert!(advance_push_counter(&mut local, 11, 0).is_err());
    assert!(advance_push_counter(&mut i32::MAX, i32::MAX, 1).is_err());
    assert!(advance_push_counter(&mut local, 10, -1).is_err());
    let mut state = cursor();
    let update = Update::UpdateBotStopped(UpdateBotStoppedConstructor {
        user_id: 42,
        date: 101,
        stopped: Box::new(tellers_mtproto::latest::api::Bool::BoolFalse(
            tellers_mtproto::latest::api::BoolFalseConstructor {},
        )),
        qts: 3,
    });
    assert!(
        advance_push_update(
            &update,
            &mut state,
            &mut HashMap::new(),
            true,
            &mut Vec::new()
        )
        .unwrap()
    );
    assert_eq!(state.qts, 3);
    assert!(
        !advance_push_update(
            &update,
            &mut state,
            &mut HashMap::new(),
            true,
            &mut Vec::new()
        )
        .unwrap()
    );
}

#[test]
fn push_channel_gaps_schedule_recovery_without_advancing_state() {
    let id = chat_id_for_channel(42);
    let update = Update::UpdateDeleteChannelMessages(UpdateDeleteChannelMessagesConstructor {
        channel_id: 42,
        messages: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![7],
        })),
        pts: 15,
        pts_count: 1,
    });
    let mut state = cursor();
    let mut channels = HashMap::from_iter([(id, 10)]);
    let mut pending = Vec::new();
    assert!(
        apply_push(
            &packet(4, vec![delete(11), update]),
            &mut HashMap::new(),
            &mut HashMap::new(),
            &mut state,
            &mut channels,
            &mut pending
        )
        .is_err()
    );
    assert_eq!(state.pts, 10);
    assert_eq!(channels[&id], 10);
    assert_eq!(pending, vec![id]);
}

#[test]
fn push_channel_too_long_and_malformed_payload() {
    let mut state = cursor();
    let mut pending = Vec::new();
    let bytes = encode(Updates::UpdateShort(UpdateShortConstructor {
        update: Box::new(Update::UpdateChannelTooLong(
            UpdateChannelTooLongConstructor {
                flags: 0,
                channel_id: 42,
                pts: None,
            },
        )),
        date: 101,
    }));
    apply_push(
        &bytes,
        &mut HashMap::new(),
        &mut HashMap::new(),
        &mut state,
        &mut HashMap::new(),
        &mut pending,
    )
    .unwrap();
    assert_eq!(pending, vec![chat_id_for_channel(42)]);
    let mut malformed = packet(4, vec![delete(11)]);
    malformed.push(0);
    assert!(apply(&malformed, &mut state).is_err());
    assert!(apply(&[0; 8], &mut state).is_err());
}
