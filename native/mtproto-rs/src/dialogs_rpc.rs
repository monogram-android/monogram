//! Dialogs, folders, and history RPCs.
//! https://core.telegram.org/api/folders
//! https://core.telegram.org/method/messages.getDialogs
//! https://core.telegram.org/method/messages.getHistory

pub use crate::dialogs::*;

#[cfg(test)]
use tellers_mtproto::latest::api::{
    Chat as TlChat, InputMessageIdConstructor, InputPeer, MessagesGetDialogsRequest,
    MessagesGetMessagesRequest,
};

#[cfg(test)]
#[path = "dialogs_rpc_tests.rs"]
mod tests;
