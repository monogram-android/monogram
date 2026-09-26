//! Message media indexing + `upload.getFile` downloads.
//! https://core.telegram.org/api/files
//! https://core.telegram.org/method/upload.getFile

pub use crate::media::*;

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
#[path = "media_rpc_tests.rs"]
mod tests;
