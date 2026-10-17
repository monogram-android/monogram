use super::refresh_dialogs;
use crate::client::{call_with_migrate, ClientState};
use crate::media;

const BASIC_GROUP_MIN: i64 = -1_000_000_000_000;

/// How to load a missing peer avatar. Document ids that share `(id, 0)` are `Skip`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PeerAvatarFetch {
    Skip,
    Profile,
    Dialogs,
}

pub(crate) fn peer_avatar_fetch(
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
    user_id: Option<i64>,
    chat_id: i64,
) -> PeerAvatarFetch {
    if chat_id == 0 || user_id == Some(chat_id) {
        return PeerAvatarFetch::Profile;
    }
    if BASIC_GROUP_MIN < chat_id && chat_id < 0 {
        return PeerAvatarFetch::Profile;
    }
    let Some(peer) = peers.get(&chat_id) else {
        return PeerAvatarFetch::Skip;
    };
    if !matches!(
        peer.kind,
        crate::peers::PeerKind::User | crate::peers::PeerKind::Channel
    ) {
        return PeerAvatarFetch::Skip;
    }
    if crate::peers::has_usable_access_hash(peer) {
        PeerAvatarFetch::Profile
    } else {
        PeerAvatarFetch::Dialogs
    }
}

fn load_avatar_profile(state: &mut ClientState, chat_id: i64) {
    let _ = call_with_migrate(state, |state| {
        crate::profile::get_profile(
            &mut state.snapshot,
            state.api_id,
            &state.peers,
            &mut state.media,
            state.user_id,
            chat_id,
        )
    });
}

pub(crate) fn reindex_peer_avatar(state: &mut ClientState, chat_id: i64) {
    media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
    if state.media.contains_key(&(chat_id, 0)) {
        return;
    }
    match peer_avatar_fetch(&state.peers, state.user_id, chat_id) {
        PeerAvatarFetch::Skip => {}
        PeerAvatarFetch::Profile => load_avatar_profile(state, chat_id),
        PeerAvatarFetch::Dialogs => {
            let _ = call_with_migrate(state, refresh_dialogs);
            media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
            if state.media.contains_key(&(chat_id, 0)) {
                return;
            }
            if peer_avatar_fetch(&state.peers, state.user_id, chat_id) == PeerAvatarFetch::Profile {
                load_avatar_profile(state, chat_id);
            }
        }
    }
    media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
}

#[cfg(test)]
#[path = "../../tests/unit/peer_avatar_tests.rs"]
mod tests;
