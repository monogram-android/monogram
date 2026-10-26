use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn reserved_filters_blank_slugs_and_invalid_orders_fail_before_session_access() {
    for id in [-1, 0, 1] {
        assert!(
            get_invites(0, id)
                .unwrap_err()
                .to_string()
                .contains("folder")
        );
        assert!(
            leave(0, id, &[])
                .unwrap_err()
                .to_string()
                .contains("folder")
        );
        assert!(
            set_filter(0, id, None)
                .unwrap_err()
                .to_string()
                .contains("folder")
        );
    }
    assert!(
        check_invite(0, " ")
            .unwrap_err()
            .to_string()
            .contains("slug")
    );
    assert!(
        edit_invite(0, 2, "slug", None, None)
            .unwrap_err()
            .to_string()
            .contains("change")
    );
    for order in [&[0, 2, 2][..], &[1, 2][..], &[-1][..]] {
        assert!(
            reorder_filters(0, order)
                .unwrap_err()
                .to_string()
                .contains("order")
        );
    }
    assert!(confirm(Bool::BoolFalse(BoolFalseConstructor {})).is_err());
    assert!(confirm(Bool::BoolTrue(BoolTrueConstructor {})).is_ok());
}

fn user(id: i64, hash: i64, min: bool) -> User {
    let flags = UserConstructor::ACCESS_HASH_FLAG | if min { UserConstructor::MIN_FLAG } else { 0 };
    let mut body = flags.to_le_bytes().to_vec();
    body.extend_from_slice(&0u32.to_le_bytes());
    body.extend_from_slice(&id.to_le_bytes());
    body.extend_from_slice(&hash.to_le_bytes());
    let mut decoder = Decoder::new(&body, Limits::default()).unwrap();
    let value = UserConstructor::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    User::User(value)
}

#[test]
fn preview_hashes_enable_exact_selections_and_min_updates_never_replace_them() {
    let directory = std::env::temp_dir().join(format!(
        "monogram-shared-folder-peers-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&directory).unwrap();
    let handle = super::super::create_client(
        1,
        "fixture".into(),
        directory.join("session.json").to_string_lossy().into(),
    );
    let result = with_client_mut(handle, |state| {
        assert!(peer_list(state, &[7]).is_err());
        let chats = Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![],
        });
        cache_peers(
            state,
            &Vector::Vector(VectorConstructor {
                field_0: 1,
                field_1: vec![Box::new(user(7, 70, false))],
            }),
            &chats,
        );
        let selection = peer_list(state, &[7])?;
        let Vector::Vector(selected) = *selection;
        assert_eq!(selected.field_1.len(), 1);
        let InputPeer::InputPeerUser(peer) = &*selected.field_1[0] else {
            panic!("user peer required")
        };
        assert_eq!((peer.user_id, peer.access_hash), (7, 70));
        cache_peers(
            state,
            &Vector::Vector(VectorConstructor {
                field_0: 2,
                field_1: vec![Box::new(user(7, 999, true)), Box::new(user(8, 80, true))],
            }),
            &chats,
        );
        assert_eq!(state.peers.get(&7).unwrap().access_hash, 70);
        assert!(peer_list(state, &[8]).is_err());
        assert!(peer_list(state, &[7, 7]).is_err());
        assert!(peer_list(state, &[7, 99]).is_err());
        let Vector::Vector(empty) = *peer_list(state, &[])?;
        assert!(
            empty.field_1.is_empty(),
            "leaving only the folder must not add chats to the leave selection"
        );
        assert!(state.updates.is_none());
        let before_pts = state.channel_pts.clone();
        super::super::management::cache_response_peers(
            state,
            &Updates::Updates(UpdatesConstructor {
                updates: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 0,
                    field_1: vec![],
                })),
                users: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 1,
                    field_1: vec![Box::new(user(9, 90, false))],
                })),
                chats: Box::new(chats.clone()),
                date: 123,
                seq: 7,
            }),
        );
        assert!(peer_list(state, &[9]).is_ok());
        assert!(
            state.updates.is_none(),
            "metadata caching must not advance global cursors"
        );
        assert_eq!(state.channel_pts, before_pts);
        Ok(())
    });
    super::super::destroy_client(handle);
    std::fs::remove_dir_all(&directory).unwrap();
    result.unwrap();
}
