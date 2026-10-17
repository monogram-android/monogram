//! Dialog list, folders, and history offsets.
//! https://core.telegram.org/api/folders
//! https://core.telegram.org/api/offsets
//! https://core.telegram.org/method/messages.getDialogs

mod folders;
mod forum;
mod get_dialogs;
mod history;
mod message_map;
mod peers;
mod permissions;
mod preview;
pub(crate) mod rich_rpc;

pub use folders::*;
pub use forum::*;
pub use get_dialogs::*;
pub use history::*;
pub(crate) use message_map::*;
pub(crate) use peers::*;
pub(crate) use permissions::*;
pub(crate) use preview::*;

#[cfg(test)]
use tellers_mtproto::latest::api::{
    Chat as TlChat, InputMessageIdConstructor, InputPeer, MessagesGetDialogsRequest,
    MessagesGetMessagesRequest,
};

#[cfg(test)]
#[path = "../../tests/unit/dialogs_forum_tests.rs"]
mod forum_tests;
#[cfg(test)]
#[path = "../../tests/unit/dialogs_header_tests.rs"]
mod header_tests;
#[cfg(test)]
#[path = "../../tests/unit/dialogs_history_tests.rs"]
mod history_tests;
#[cfg(test)]
#[path = "../../tests/unit/dialogs_membership_tests.rs"]
mod membership_tests;
#[cfg(test)]
#[path = "../../tests/unit/dialogs_mute_tests.rs"]
mod mute_tests;
#[cfg(test)]
#[path = "../../tests/unit/dialogs_preview_tests.rs"]
mod preview_tests;
#[cfg(test)]
#[path = "../../tests/unit/dialogs_rights_tests.rs"]
mod rights_tests;
