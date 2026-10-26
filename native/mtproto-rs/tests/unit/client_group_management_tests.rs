use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn false_mutation_result_is_not_reported_as_success() {
    assert!(confirm_change(Bool::BoolTrue(BoolTrueConstructor {})).is_ok());
    assert!(confirm_change(Bool::BoolFalse(BoolFalseConstructor {})).is_err());
}

#[test]
fn membership_rejects_empty_invites_and_invalid_history_limits_before_session_access() {
    assert!(
        invite_channel_members(0, -12, &[])
            .unwrap_err()
            .to_string()
            .contains("member")
    );
    for limit in [-1, 101] {
        assert!(
            add_group_member(0, -12, 7, limit)
                .unwrap_err()
                .to_string()
                .contains("limit")
        );
    }
}

#[test]
fn management_peer_resolution_preserves_roles_and_rejects_min_hashes() {
    use crate::peers::{CachedPeer, chat_id_for_channel};
    let directory = std::env::temp_dir().join(format!(
        "monogram-management-peers-{}-{}",
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
        for (id, kind, raw, hash) in [
            (7, PeerKind::User, 7, 70),
            (-12, PeerKind::Chat, 12, 0),
            (chat_id_for_channel(34), PeerKind::Channel, 34, 340),
        ] {
            state.peers.insert(
                id,
                CachedPeer {
                    kind,
                    id: raw,
                    access_hash: hash,
                    min_hash: false,
                },
            );
        }
        assert_eq!(basic_group_id(state, -12).unwrap(), 12);
        let InputUser::InputUser(user) = user_peer(state, 7).unwrap() else {
            panic!("user required")
        };
        assert_eq!((user.user_id, user.access_hash), (7, 70));
        let InputChannel::InputChannel(channel) =
            channel_peer(state, chat_id_for_channel(34)).unwrap()
        else {
            panic!("channel required")
        };
        assert_eq!((channel.channel_id, channel.access_hash), (34, 340));
        assert!(group_peer(state, -12).is_ok());
        assert!(group_peer(state, chat_id_for_channel(34)).is_ok());
        assert!(group_peer(state, 7).is_err());
        assert!(basic_group_id(state, chat_id_for_channel(34)).is_err());
        assert!(channel_peer(state, -12).is_err());
        assert!(user_peer(state, -12).is_err());
        assert!(user_peer(state, 99).is_err());
        state.peers.get_mut(&7).unwrap().min_hash = true;
        assert!(user_peer(state, 7).is_err());
        state
            .peers
            .get_mut(&chat_id_for_channel(34))
            .unwrap()
            .access_hash = 0;
        assert!(channel_peer(state, chat_id_for_channel(34)).is_err());
        Ok(())
    });
    super::super::destroy_client(handle);
    std::fs::remove_dir_all(&directory).unwrap();
    result.unwrap();
}

#[test]
fn channel_kinds_encode_exclusive_broadcast_and_megagroup_flags() {
    for kind in [
        ChannelKind::Broadcast,
        ChannelKind::Megagroup,
        ChannelKind::Forum,
    ] {
        let bytes =
            crate::rpc::encode_boxed_bytes(&channel_request("Group", "About", kind)).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(
            decoder.read_u32().unwrap(),
            ChannelsCreateChannelRequest::ID
        );
        let request = ChannelsCreateChannelRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(request.broadcast.is_some(), kind == ChannelKind::Broadcast);
        assert_eq!(request.megagroup.is_some(), kind != ChannelKind::Broadcast);
        assert_eq!(request.forum.is_some(), kind == ChannelKind::Forum);
        assert_eq!(request.title, "Group");
        assert_eq!(request.about, "About");
        assert!(request.for_import.is_none());
        assert!(request.ttl_period.is_none());
    }
}

#[test]
fn creation_rejects_missing_titles_and_non_user_members_before_session_access() {
    assert!(
        create_group(0, " ", &[7])
            .unwrap_err()
            .to_string()
            .contains("title")
    );
    for members in [&[][..], &[0][..], &[-12][..]] {
        assert!(
            create_group(0, "Group", members)
                .unwrap_err()
                .to_string()
                .contains("user peers")
        );
    }
    for kind in [
        ChannelKind::Broadcast,
        ChannelKind::Megagroup,
        ChannelKind::Forum,
    ] {
        assert!(
            create_channel(0, "\n", "", kind)
                .unwrap_err()
                .to_string()
                .contains("title")
        );
    }
}
