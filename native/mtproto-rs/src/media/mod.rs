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

pub(crate) use cdn::*;
pub(crate) use download::*;
pub use index::*;
pub use location::*;
pub(crate) use page_plain::*;
pub(crate) use structured::*;
pub use thumbs::*;
