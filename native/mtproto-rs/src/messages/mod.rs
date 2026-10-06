//! Send, search, and read-history RPCs.
//! https://core.telegram.org/api/sending
//! https://core.telegram.org/method/messages.sendMessage
//! https://core.telegram.org/api/offsets

mod entities;
pub(crate) mod inline_rpc;
mod read;
pub(crate) mod read_receipts_rpc;
mod search;
pub(crate) mod search_rpc;
mod send;
pub(crate) mod sticker_rpc;

pub(crate) use entities::*;
pub use read::*;
pub use search::*;
pub use send::*;
pub(crate) use send::{
    PHOTO_MAX, PHOTO_PART, all_new_messages, input_reply_to_thread, message_from_updates, random_id,
};

#[cfg(test)]
#[path = "../../tests/unit/messages_rpc_tests.rs"]
mod tests;
