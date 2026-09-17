use crate::HashMap;

use serde::{Deserialize, Serialize};
use tellers_mtproto::latest::api::{
    InputDocumentFileLocationConstructor, InputFileLocation, InputPeer,
    InputPeerChannelConstructor, InputPeerChatConstructor, InputPeerPhotoFileLocationConstructor,
    InputPeerUserConstructor, InputPhotoFileLocationConstructor, True, TrueConstructor,
};

use super::thumbs::is_getfile_thumb_size;
use crate::MtprotoError;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum MediaLocation {
    Photo {
        id: i64,
        access_hash: i64,
        file_reference: Vec<u8>,
        thumb_size: String,
        dc_id: i32,
    },
    Document {
        id: i64,
        access_hash: i64,
        file_reference: Vec<u8>,
        thumb_size: String,
        dc_id: i32,
        mime_type: String,
    },
    PeerPhoto {
        peer_kind: crate::peers::PeerKind,
        peer_id: i64,
        access_hash: i64,
        photo_id: i64,
        big: bool,
        dc_id: i32,
    },
    Inline {
        bytes: Vec<u8>,
        extension: String,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct MediaRef {
    pub kind: String,
    pub cache_key: String,
    pub location: MediaLocation,
    #[serde(default)]
    pub thumb_cache_key: Option<String>,
    #[serde(default)]
    pub thumb_location: Option<MediaLocation>,
    #[serde(default)]
    pub display_cache_key: Option<String>,
    #[serde(default)]
    pub display_location: Option<MediaLocation>,
    #[serde(default)]
    pub sticker_set_id: Option<i64>,
    #[serde(default)]
    pub sticker_set_access_hash: Option<i64>,
    #[serde(default)]
    pub source_url: Option<String>,
}

pub type MediaIndex = HashMap<(i64, i32), MediaRef>;

/// Avatar `InputPeerPhotoFileLocation` cannot use hash 0 / min.
/// https://core.telegram.org/api/min
pub fn fill_zero_peer_photo_hashes(
    media: &mut MediaIndex,
    peers: &HashMap<i64, crate::peers::CachedPeer>,
) {
    for ((chat_id, _), item) in media.iter_mut() {
        let MediaLocation::PeerPhoto { access_hash, .. } = &mut item.location else {
            continue;
        };
        if *access_hash != 0 {
            continue;
        }
        if let Some(peer) = crate::peers::usable_cached_peer(peers, *chat_id) {
            *access_hash = peer.access_hash;
        }
    }
}

pub fn peer_photo_needs_hash(media: &MediaRef) -> bool {
    matches!(
        media.location,
        MediaLocation::PeerPhoto { access_hash: 0, peer_kind, .. }
            if peer_kind != crate::peers::PeerKind::Chat
    )
}

pub(crate) fn input_location(loc: &MediaLocation) -> Result<InputFileLocation, MtprotoError> {
    match loc {
        MediaLocation::Photo {
            id,
            access_hash,
            file_reference,
            thumb_size,
            ..
        } => Ok(InputFileLocation::InputPhotoFileLocation(
            InputPhotoFileLocationConstructor {
                id: *id,
                access_hash: *access_hash,
                file_reference: file_reference.clone(),
                thumb_size: thumb_size.clone(),
            },
        )),
        MediaLocation::Document {
            id,
            access_hash,
            file_reference,
            thumb_size,
            ..
        } => Ok(InputFileLocation::InputDocumentFileLocation(
            InputDocumentFileLocationConstructor {
                id: *id,
                access_hash: *access_hash,
                file_reference: file_reference.clone(),
                thumb_size: thumb_size.clone(),
            },
        )),
        MediaLocation::PeerPhoto {
            peer_kind,
            peer_id,
            access_hash,
            photo_id,
            big,
            ..
        } => {
            let peer = match peer_kind {
                crate::peers::PeerKind::User => {
                    InputPeer::InputPeerUser(InputPeerUserConstructor {
                        user_id: *peer_id,
                        access_hash: *access_hash,
                    })
                }
                crate::peers::PeerKind::Chat => {
                    InputPeer::InputPeerChat(InputPeerChatConstructor { chat_id: *peer_id })
                }
                crate::peers::PeerKind::Channel => {
                    InputPeer::InputPeerChannel(InputPeerChannelConstructor {
                        channel_id: *peer_id,
                        access_hash: *access_hash,
                    })
                }
            };
            let big = if *big {
                Some(Box::new(True::True(TrueConstructor {})))
            } else {
                None
            };
            let flags = if big.is_some() { 1 } else { 0 };
            Ok(InputFileLocation::InputPeerPhotoFileLocation(
                InputPeerPhotoFileLocationConstructor {
                    flags,
                    big,
                    peer: Box::new(peer),
                    photo_id: *photo_id,
                },
            ))
        }
        MediaLocation::Inline { .. } => Err(MtprotoError::Message(
            "inline media does not need upload.getFile".into(),
        )),
    }
}

pub fn location_is_inline(loc: &MediaLocation) -> bool {
    matches!(loc, MediaLocation::Inline { .. })
}

pub(crate) fn media_dc(loc: &MediaLocation) -> Option<i32> {
    match loc {
        MediaLocation::Photo { dc_id, .. }
        | MediaLocation::Document { dc_id, .. }
        | MediaLocation::PeerPhoto { dc_id, .. } => Some(*dc_id),
        MediaLocation::Inline { .. } => None,
    }
}

/// DC that should own `upload.getFile` for this location. 0 / missing → home DC.
pub(crate) fn download_dc(home_dc: i32, loc: &MediaLocation) -> i32 {
    media_dc(loc).filter(|dc| *dc != 0).unwrap_or(home_dc)
}

pub fn is_file_reference_error(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if {
        let upper = message.to_ascii_uppercase();
        upper.contains("FILE_REFERENCE")
    })
}

/// Compare locations after a file-ref refresh. Peer photos have no `file_reference`.
/// https://core.telegram.org/api/file-references
pub fn location_token(loc: &MediaLocation) -> Vec<u8> {
    match loc {
        MediaLocation::Photo {
            id,
            file_reference,
            thumb_size,
            ..
        } => token_parts(*id, file_reference, thumb_size),
        MediaLocation::Document {
            id,
            file_reference,
            thumb_size,
            ..
        } => token_parts(*id, file_reference, thumb_size),
        MediaLocation::PeerPhoto {
            photo_id,
            access_hash,
            ..
        } => {
            let mut out = photo_id.to_le_bytes().to_vec();
            out.extend_from_slice(&access_hash.to_le_bytes());
            out
        }
        MediaLocation::Inline { bytes, .. } => bytes.clone(),
    }
}

thread_local! {
    static TOKEN_SCRATCH: std::cell::RefCell<Vec<u8>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

pub(crate) fn token_parts(id: i64, file_reference: &[u8], thumb_size: impl AsRef<str>) -> Vec<u8> {
    TOKEN_SCRATCH.with(|cell| {
        let mut out = cell.borrow_mut();
        out.clear();
        out.extend_from_slice(&id.to_le_bytes());
        out.extend_from_slice(file_reference);
        out.extend_from_slice(thumb_size.as_ref().as_bytes());
        out.clone()
    })
}

pub(crate) fn location_is_getfile_thumb(loc: &MediaLocation) -> bool {
    match loc {
        MediaLocation::Inline { bytes, .. } => !bytes.is_empty(),
        MediaLocation::Photo { thumb_size, .. } | MediaLocation::Document { thumb_size, .. } => {
            is_getfile_thumb_size(thumb_size)
        }
        MediaLocation::PeerPhoto { .. } => false,
    }
}

pub fn is_file_id_invalid(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if {
        message.to_ascii_uppercase().contains("FILE_ID_INVALID")
    })
}

/// Other getFile letters to try after `FILE_ID_INVALID` on the picked thumb.

pub fn location_thumb_size(loc: &MediaLocation) -> Option<&str> {
    match loc {
        MediaLocation::Photo { thumb_size, .. } | MediaLocation::Document { thumb_size, .. } => {
            Some(thumb_size.as_str())
        }
        _ => None,
    }
}

pub fn with_thumb_size(loc: &MediaLocation, thumb_size: &str) -> Option<MediaLocation> {
    match loc {
        MediaLocation::Photo {
            id,
            access_hash,
            file_reference,
            dc_id,
            ..
        } => Some(MediaLocation::Photo {
            id: *id,
            access_hash: *access_hash,
            file_reference: file_reference.clone(),
            thumb_size: thumb_size.to_string(),
            dc_id: *dc_id,
        }),
        MediaLocation::Document {
            id,
            access_hash,
            file_reference,
            dc_id,
            mime_type,
            ..
        } => Some(MediaLocation::Document {
            id: *id,
            access_hash: *access_hash,
            file_reference: file_reference.clone(),
            thumb_size: thumb_size.to_string(),
            dc_id: *dc_id,
            mime_type: mime_type.clone(),
        }),
        _ => None,
    }
}

/// Persist a working getFile letter so later thumb fetches skip the invalid size.
pub fn remember_thumb_location(media: &mut MediaRef, loc: MediaLocation) {
    if media.thumb_location.is_some() {
        media.thumb_location = Some(loc);
    } else {
        media.location = loc;
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaDownloadKind {
    Full,
    Thumb,
    Display,
}

/// Thumb downloads must use an inline blob or a real getFile size.
/// Falling back to the full document/photo is how thumbs hit `FILE_ID_INVALID`.
pub fn media_for_download(
    media: &MediaRef,
    kind: MediaDownloadKind,
) -> Result<MediaRef, MtprotoError> {
    match kind {
        MediaDownloadKind::Full => Ok(media.clone()),
        MediaDownloadKind::Display => {
            if let Some(loc) = &media.display_location {
                if location_is_getfile_thumb(loc) {
                    let mut copy = media.clone();
                    copy.location = loc.clone();
                    copy.cache_key = media
                        .display_cache_key
                        .clone()
                        .unwrap_or_else(|| format!("{}:display", media.cache_key));
                    return Ok(copy);
                }
            }
            Err(MtprotoError::Message("no display size".into()))
        }
        MediaDownloadKind::Thumb => {
            if let Some(loc) = &media.thumb_location {
                if location_is_getfile_thumb(loc) {
                    let mut copy = media.clone();
                    copy.location = loc.clone();
                    copy.cache_key = media
                        .thumb_cache_key
                        .clone()
                        .unwrap_or_else(|| media.cache_key.clone());
                    return Ok(copy);
                }
            }
            // Only size is already a getFile photo thumb (`m`/`x`/…); not a full document.
            if location_is_getfile_thumb(&media.location) {
                return Ok(media.clone());
            }
            if matches!(
                &media.location,
                MediaLocation::Inline { bytes, .. } if !bytes.is_empty()
            ) {
                return Ok(media.clone());
            }
            Err(MtprotoError::Message("no downloadable thumb".into()))
        }
    }
}
