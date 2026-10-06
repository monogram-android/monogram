use super::*;
use crate::client::pipeline_parts;

use crate::client::set_pipeline_parts;
use tellers_mtproto::latest::api::{
    GeoPointConstructor, GeoPointEmptyConstructor, MessageEntity, MessageMediaContactConstructor,
    MessageMediaDiceConstructor, MessageMediaGeoConstructor, MessageMediaGeoLiveConstructor,
    MessageMediaPollConstructor, MessageMediaVenueConstructor, PollAnswerConstructor,
    PollAnswerVotersConstructor, PollConstructor, PollResultsConstructor,
    TextWithEntitiesConstructor,
};

#[test]
fn file_reference_error_matches_expired_and_invalid() {
    assert!(is_file_reference_error(&MtprotoError::Message(
        "RPC 400: FILE_REFERENCE_EXPIRED".into(),
    )));
    assert!(is_file_reference_error(&MtprotoError::Message(
        "FILE_REFERENCE_INVALID".into(),
    )));
    assert!(is_file_reference_error(&MtprotoError::Message(
        "FILE_REFERENCE_3_EXPIRED".into(),
    )));
    assert!(!is_file_reference_error(&MtprotoError::Message(
        "RPC timeout recv=2880".into(),
    )));
    assert!(!is_file_reference_error(&MtprotoError::UnknownClient));
}

#[test]
fn peer_photo_location_token_tracks_access_hash() {
    let peer = MediaLocation::PeerPhoto {
        peer_kind: crate::peers::PeerKind::User,
        peer_id: 1,
        access_hash: 0,
        photo_id: 1,
        big: true,
        dc_id: 2,
    };
    let mut updated = peer.clone();
    if let MediaLocation::PeerPhoto { access_hash, .. } = &mut updated {
        *access_hash = 9;
    }
    assert_ne!(location_token(&peer), location_token(&updated));
}

#[test]
fn fill_zero_peer_photo_hashes_from_full_peer() {
    let mut media = MediaIndex::new();
    media.insert(
        (-1_000_000_000_042, 0),
        media_ref_peer_photo(
            crate::peers::PeerKind::Channel,
            42,
            0,
            9,
            2,
            "avatar:-1000000000042".into(),
            false,
            None,
        ),
    );
    let mut peers = HashMap::new();
    peers.insert(
        -1_000_000_000_042,
        crate::peers::CachedPeer {
            kind: crate::peers::PeerKind::Channel,
            id: 42,
            access_hash: 77,
            min_hash: false,
        },
    );
    fill_zero_peer_photo_hashes(&mut media, &peers);
    match media.get(&(-1_000_000_000_042, 0)).unwrap().location {
        MediaLocation::PeerPhoto { access_hash, .. } => assert_eq!(access_hash, 77),
        _ => panic!("peer photo"),
    }
}
