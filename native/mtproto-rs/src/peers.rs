//! Peer id encoding and access-hash cache for InputPeer construction.

use serde::{Deserialize, Serialize};
use tellers_mtproto::latest::api::{
    InputPeer, InputPeerChannelConstructor, InputPeerChatConstructor, InputPeerUserConstructor,
    Peer, Vector,
};

const CHANNEL_OFFSET: i64 = 1_000_000_000_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum PeerKind {
    User,
    Chat,
    Channel,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct CachedPeer {
    pub kind: PeerKind,
    pub id: i64,
    pub access_hash: i64,
    /// Min hashes must not be used as `InputPeer` (https://core.telegram.org/api/min).
    #[serde(default)]
    pub min_hash: bool,
}

pub fn peer_chat_id(peer: &Peer) -> i64 {
    match peer {
        Peer::PeerUser(u) => u.user_id,
        Peer::PeerChat(c) => -c.chat_id,
        Peer::PeerChannel(c) => -(CHANNEL_OFFSET + c.channel_id),
        _ => 0,
    }
}

pub fn chat_id_for_user(user_id: i64) -> i64 {
    user_id
}

pub fn chat_id_for_chat(chat_id: i64) -> i64 {
    -chat_id
}

pub fn chat_id_for_channel(channel_id: i64) -> i64 {
    -(CHANNEL_OFFSET + channel_id)
}

pub fn is_channel_chat_id(chat_id: i64) -> bool {
    chat_id <= -CHANNEL_OFFSET
}

pub fn channel_id_from_chat_id(chat_id: i64) -> Option<i64> {
    if is_channel_chat_id(chat_id) {
        Some(-chat_id - CHANNEL_OFFSET)
    } else {
        None
    }
}

/// Apply a peer from TL. Never replace a full hash with a `*Min` hash.
/// https://core.telegram.org/api/min
pub fn upsert_cached_peer(
    peers: &mut std::collections::HashMap<i64, CachedPeer>,
    chat_id: i64,
    kind: PeerKind,
    raw_id: i64,
    access_hash: i64,
    min: bool,
) {
    if kind != PeerKind::Chat && (min || access_hash == 0) {
        if let Some(existing) = peers.get(&chat_id) {
            if existing.access_hash != 0 && !existing.min_hash {
                return;
            }
        } else {
            return;
        }
        if min {
            return;
        }
    }
    peers.insert(
        chat_id,
        CachedPeer {
            kind,
            id: raw_id,
            access_hash,
            min_hash: min,
        },
    );
}

/// User/channel `InputPeer` needs a full access hash. Basic chats do not.
/// https://core.telegram.org/api/min
pub fn has_usable_access_hash(peer: &CachedPeer) -> bool {
    match peer.kind {
        PeerKind::Chat => true,
        PeerKind::User | PeerKind::Channel => !peer.min_hash && peer.access_hash != 0,
    }
}

pub fn usable_cached_peer(
    peers: &std::collections::HashMap<i64, CachedPeer>,
    chat_id: i64,
) -> Option<&CachedPeer> {
    peers
        .get(&chat_id)
        .filter(|peer| has_usable_access_hash(peer))
}

pub fn require_usable_peer(
    peers: &std::collections::HashMap<i64, CachedPeer>,
    chat_id: i64,
) -> Result<&CachedPeer, crate::MtprotoError> {
    usable_cached_peer(peers, chat_id).ok_or_else(|| {
        crate::MtprotoError::Message(format!("unknown peer {chat_id}; refresh chats first"))
    })
}

pub fn input_peer_from_cached(peer: &CachedPeer) -> InputPeer {
    match peer.kind {
        PeerKind::User => InputPeer::InputPeerUser(InputPeerUserConstructor {
            user_id: peer.id,
            access_hash: peer.access_hash,
        }),
        PeerKind::Chat => InputPeer::InputPeerChat(InputPeerChatConstructor { chat_id: peer.id }),
        PeerKind::Channel => InputPeer::InputPeerChannel(InputPeerChannelConstructor {
            channel_id: peer.id,
            access_hash: peer.access_hash,
        }),
    }
}

pub fn chat_id_from_input_peer(peer: &InputPeer) -> Option<i64> {
    match peer {
        InputPeer::InputPeerUser(u) => Some(chat_id_for_user(u.user_id)),
        InputPeer::InputPeerChat(c) => Some(chat_id_for_chat(c.chat_id)),
        InputPeer::InputPeerChannel(c) => Some(chat_id_for_channel(c.channel_id)),
        InputPeer::InputPeerSelf(_) => Some(0), // saved messages placeholder
        _ => None,
    }
}

pub fn vector_items<T>(vector: &Vector<T>) -> &[T] {
    match vector {
        Vector::Vector(v) => &v.field_1,
    }
}

pub fn vector_boxed_items<T>(vector: &Vector<Box<T>>) -> impl Iterator<Item = &T> {
    vector_items(vector).iter().map(|b| b.as_ref())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

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
}
