//! Media locations, thumbs, and `upload.getFile`.
//! https://core.telegram.org/api/files
//! https://core.telegram.org/api/file-references

mod cdn;
mod download;
mod index;
mod location;
mod page_plain;
mod structured;
mod thumbs;
pub(crate) mod wallpaper_rpc;

pub(crate) use cdn::*;
pub(crate) use download::*;
pub use download::{ProgressCallback, notify_progress, set_progress_callback};
pub use index::*;
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
use tellers_mtproto::latest::api::{
    GeoPoint, PageBlock, PageCaption, Photo, PhotoSize, Poll, PollAnswer, PollAnswerVoters,
    PollResults, RichText, TextWithEntities, True, TrueConstructor, UploadFile, Vector,
    VectorConstructor,
};

#[cfg(test)]
#[path = "../../tests/unit/media_download_tests.rs"]
mod download_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_index_tests.rs"]
mod index_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_location_tests.rs"]
mod location_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_page_tests.rs"]
mod page_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_structured_tests.rs"]
mod structured_tests;
#[cfg(test)]
#[path = "../../tests/unit/media_thumbs_tests.rs"]
mod thumbs_tests;
