use mono_proto::latest::api::*;

use crate::{MessageDto, MtprotoError};

pub type SendOptions = mono_proto_runtime::send_options::SendOptions;

#[uniffi::remote(Record)]
pub struct SendOptions {
    pub silent: bool,
    pub no_webpage: bool,
    pub schedule_date: Option<i32>,
}

pub type OutgoingMessageResult = mono_proto_types::event_types::OutgoingMessageResult;

#[uniffi::remote(Enum)]
pub enum OutgoingMessageResult {
    Sent { message: MessageDto },
    Scheduled { message: crate::ScheduledMessageDto },
}


pub(crate) use mono_proto_messages::messages_send_result::{album_send_results, implicit_ack, send_result};
