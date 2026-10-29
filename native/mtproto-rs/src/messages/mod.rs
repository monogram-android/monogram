//! Send, search, and read-history RPCs.
//! https://core.telegram.org/api/sending
//! https://core.telegram.org/method/messages.sendMessage
//! https://core.telegram.org/api/offsets

pub(crate) mod location_send;
pub use location_send::LocationSendSpec;
pub(crate) use location_send::send_location_with_options;
mod contact_send;
mod entities { pub(crate) use mono_proto_types::message_entities::*; }
pub(crate) mod inline_rpc { pub(crate) use mono_proto_messages::messages::inline_rpc::*; }
mod read { pub(crate) use mono_proto_messages::messages_read::*; }
pub(crate) mod read_receipts_rpc;
mod search { pub(crate) use mono_proto_messages::messages_search::*; }
pub(crate) mod search_rpc { pub(crate) use mono_proto_messages::messages_search_rpc::*; }
mod send;
pub use contact_send::ContactSendSpec;
pub(crate) use contact_send::{send_contact_with_options, validate_contact};
mod checklist_creation;
pub use checklist_creation::ChecklistSendSpec;
pub(crate) use checklist_creation::send_checklist_with_options;
mod poll_creation;
pub use poll_creation::PollSendSpec;
pub(crate) use poll_creation::send_poll_with_options;
mod dice { pub(crate) use mono_proto_messages::messages_dice::*; }
pub(crate) use dice::send_dice_with_options;
mod send_options;
pub use send_options::{OutgoingMessageResult, SendOptions};
pub(crate) use send_options::{album_send_results, implicit_ack, send_result};
pub(crate) mod sticker_rpc { pub(crate) use mono_proto_messages::messages_sticker_rpc::*; }

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
