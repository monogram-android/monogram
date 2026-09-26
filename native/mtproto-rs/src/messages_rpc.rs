//! Send, search, and related message RPCs.
//! https://core.telegram.org/api/sending
//! https://core.telegram.org/method/messages.sendMessage
//! https://core.telegram.org/method/messages.search

pub use crate::messages::*;

#[cfg(test)]
#[path = "messages_rpc_tests.rs"]
mod tests;
