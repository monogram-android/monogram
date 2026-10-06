//! Android UniFFI surface for Monogram (Tellers MTProto runtime).
//! https://core.telegram.org/mtproto
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/layers

#![deny(unsafe_code)]

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

mod api_invoke;
mod auth;
mod client;
mod collections;
mod dialogs;
mod dns_txt;
mod dto;
mod emoji_status;
mod error;
mod ffi;
mod instant_view;
mod lottie;
pub mod media;
mod messages;
mod peers;
mod perf;
mod presence;
mod profile;
mod reply_markup;
mod request_control;
mod rpc;
mod scheduler;
mod service_messages;
mod session_crypto;
mod session_file;
mod stripped_jpeg;
mod tcp;
mod update_buffer;
mod updates;
mod upload;
#[allow(unsafe_code)]
mod vpx;
mod waveform;

pub(crate) use collections::{
    CompactString, HashMap, HashMapExt, HashSet, HashSetExt, IndexMap, SmallVec,
};

pub use dto::*;
pub use error::*;
pub use ffi::*;
pub use instant_view::InstantViewDto;
pub use media::wallpaper_rpc::{WallpaperCatalogDto, WallpaperDto};
pub use media::{ProgressCallback, notify_progress, set_progress_callback};

uniffi::setup_scaffolding!();

#[cfg(test)]
#[path = "../tests/unit/lib_tests.rs"]
mod tests;
