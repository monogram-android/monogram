use crate::MessageDto;
pub use mono_proto_client::scheduled::*;

pub type ScheduledMessageDto = mono_proto_client::adapter::types::event_types::ScheduledMessageDto;

#[uniffi::remote(Record)]
pub struct ScheduledMessageDto {
    pub message: MessageDto,
    pub has_unsupported_content: bool,
}
