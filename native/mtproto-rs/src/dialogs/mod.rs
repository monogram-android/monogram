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

pub use folders::*;
pub use forum::*;
pub use get_dialogs::*;
pub use history::*;
pub(crate) use message_map::*;
pub(crate) use peers::*;
pub(crate) use permissions::*;
pub(crate) use preview::*;
