use super::{peer_avatar_fetch, PeerAvatarFetch};
use crate::peers::{CachedPeer, PeerKind};
use crate::HashMap;

fn empty_peers() -> HashMap<i64, CachedPeer> {
    HashMap::default()
}

fn peer(kind: PeerKind, id: i64, access_hash: i64, min_hash: bool) -> CachedPeer {
    CachedPeer {
        kind,
        id,
        access_hash,
        min_hash,
    }
}

#[test]
fn arbitrary_positive_id_is_not_a_profile_fetch() {
    assert_eq!(
        peer_avatar_fetch(&empty_peers(), Some(7), 99),
        PeerAvatarFetch::Skip
    );
}

#[test]
fn self_basic_group_and_usable_peers_fetch_a_profile() {
    assert_eq!(
        peer_avatar_fetch(&empty_peers(), Some(7), 0),
        PeerAvatarFetch::Profile
    );
    assert_eq!(
        peer_avatar_fetch(&empty_peers(), Some(7), 7),
        PeerAvatarFetch::Profile
    );
    assert_eq!(
        peer_avatar_fetch(&empty_peers(), None, -100),
        PeerAvatarFetch::Profile
    );
    let mut peers = empty_peers();
    peers.insert(4, peer(PeerKind::User, 4, 9, false));
    assert_eq!(peer_avatar_fetch(&peers, None, 4), PeerAvatarFetch::Profile);
    let channel_id = -1_000_000_000_042;
    peers.insert(channel_id, peer(PeerKind::Channel, 42, 3, false));
    assert_eq!(
        peer_avatar_fetch(&peers, None, channel_id),
        PeerAvatarFetch::Profile
    );
}

#[test]
fn known_unusable_hash_refreshes_dialogs_and_min_author_stays_unresolved() {
    let mut peers = empty_peers();
    peers.insert(4, peer(PeerKind::User, 4, 0, true));
    assert_eq!(peer_avatar_fetch(&peers, None, 4), PeerAvatarFetch::Dialogs);
    let channel_id = -1_000_000_000_042;
    peers.insert(channel_id, peer(PeerKind::Channel, 42, 0, true));
    assert_eq!(
        peer_avatar_fetch(&peers, None, channel_id),
        PeerAvatarFetch::Dialogs
    );
    assert_eq!(
        peer_avatar_fetch(&empty_peers(), None, channel_id),
        PeerAvatarFetch::Skip
    );
}
