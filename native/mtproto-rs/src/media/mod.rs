//! Media locations, thumbs, and `upload.getFile`.
//! https://core.telegram.org/api/files
//! https://core.telegram.org/api/file-references

mod cdn { pub(crate) use mono_proto_media::media_cdn::*; }
mod download;
mod index { pub(crate) use mono_proto_media::media_index::*; }
mod location { pub(crate) use mono_proto_media::media_location::*; }
mod page_plain { pub(crate) use mono_proto_media::media_page::*; }
mod structured { pub(crate) use mono_proto_media::media_structured::*; }
mod thumbs { pub(crate) use mono_proto_media::media_thumbs::*; }
pub(crate) mod wallpaper_rpc;

pub(crate) use cdn::*;
pub(crate) use download::*;
pub use download::{ProgressCallback, notify_progress, set_progress_callback};
pub use index::*;
pub(crate) use index::{
    INSTANT_VIEW_CAP, MESSAGE_MEDIA_CAP, PERSISTED_INLINE_BYTE_CAP, ZERO_ID_DOCUMENT_CAP,
    bound_media_index, insert_media, persisted_media_entry, session_media_kept,
};
pub use location::*;
pub(crate) use page_plain::*;
pub(crate) use structured::*;
pub use thumbs::*;

#[cfg(test)]
use crate::MtprotoError;
#[cfg(test)]
use crate::{HashMap, HashMapExt};
#[cfg(test)]
use std::fs;
#[cfg(test)]
use std::io::Write;
#[cfg(test)]
use mono_proto::latest::api::{
    GeoPoint, PageBlock, PageCaption, Photo, PhotoSize, Poll, PollAnswer, PollAnswerVoters,
    PollResults, RichText, TextWithEntities, True, TrueConstructor, UploadFile, Vector,
    VectorConstructor,
};

#[cfg(test)]
#[path = "../../tests/unit/media_download_tests.rs"]
mod download_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_location_tests.rs"]
mod location_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_page_tests.rs"]
mod page_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_thumbs_tests.rs"]
mod thumbs_tests;
