//! Instant View page mapping.
//! https://core.telegram.org/method/messages.getWebPage
//! https://core.telegram.org/constructor/page
//! https://core.telegram.org/type/PageBlock

mod blocks;
mod index;
mod rich;
mod rpc;

pub(crate) use blocks::*;
pub use index::INSTANT_VIEW_MEDIA_MSG;
pub(crate) use index::*;
pub(crate) use rich::*;
pub use rpc::*;
