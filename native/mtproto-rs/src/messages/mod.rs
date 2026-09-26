//! Send, search, and read-history RPCs.
//! https://core.telegram.org/api/sending
//! https://core.telegram.org/method/messages.sendMessage
//! https://core.telegram.org/api/offsets

mod entities;
mod read;
mod search;
mod send;

pub(crate) use entities::*;
pub use read::*;
pub use search::*;
pub use send::*;
pub(crate) use send::{
    PHOTO_MAX, PHOTO_PART, all_new_messages, input_reply_to_thread, message_from_updates, random_id,
};
