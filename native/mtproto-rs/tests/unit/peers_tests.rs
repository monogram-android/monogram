use super::*;
use crate::{HashMap, HashMapExt};

#[test]
fn min_user_does_not_replace_full_hash() {
    let mut peers = HashMap::new();
    upsert_cached_peer(&mut peers, 7, PeerKind::User, 7, 99, false);
    upsert_cached_peer(&mut peers, 7, PeerKind::User, 7, 1, true);
    let stored = peers.get(&7).expect("peer");
    assert_eq!(stored.access_hash, 99);
    assert!(!stored.min_hash);
}

#[test]
fn min_user_is_not_cached_without_full() {
    let mut peers = HashMap::new();
    upsert_cached_peer(&mut peers, 7, PeerKind::User, 7, 1, true);
    assert!(peers.get(&7).is_none());
}

#[test]
fn full_user_replaces_zero_hash() {
    let mut peers = HashMap::new();
    peers.insert(
        7,
        CachedPeer {
            kind: PeerKind::User,
            id: 7,
            access_hash: 0,
            min_hash: false,
        },
    );
    upsert_cached_peer(&mut peers, 7, PeerKind::User, 7, 55, false);
    assert_eq!(peers.get(&7).unwrap().access_hash, 55);
    assert!(has_usable_access_hash(peers.get(&7).unwrap()));
}

#[test]
fn zero_channel_hash_is_not_usable_input_peer() {
    let mut peers = HashMap::new();
    peers.insert(
        -1_000_000_000_042,
        CachedPeer {
            kind: PeerKind::Channel,
            id: 42,
            access_hash: 0,
            min_hash: false,
        },
    );
    assert!(usable_cached_peer(&peers, -1_000_000_000_042).is_none());
    assert!(require_usable_peer(&peers, -1_000_000_000_042).is_err());
}

#[test]
fn basic_chat_is_usable_without_hash() {
    let mut peers = HashMap::new();
    upsert_cached_peer(&mut peers, -5, PeerKind::Chat, 5, 0, false);
    assert!(has_usable_access_hash(peers.get(&-5).unwrap()));
}
