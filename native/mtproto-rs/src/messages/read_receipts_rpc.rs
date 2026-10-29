pub(crate) use mono_proto_messages::messages_read_receipts_rpc::*;

pub type ReadParticipantDto = mono_proto_messages::messages_read_receipts_rpc::ReadParticipantDto;

#[uniffi::remote(Record)]
pub struct ReadParticipantDto {
    pub peer_id: i64,
    pub date: i32,
}

pub type ReadParticipantsDto = mono_proto_messages::messages_read_receipts_rpc::ReadParticipantsDto;

#[uniffi::remote(Record)]
pub struct ReadParticipantsDto {
    pub participants: Vec<ReadParticipantDto>,
    pub error: Option<String>,
}

pub type OutboxReadDto = mono_proto_messages::messages_read_receipts_rpc::OutboxReadDto;

#[uniffi::remote(Record)]
pub struct OutboxReadDto {
    pub date: i32,
    pub error: Option<String>,
}

pub type ReadReceiptConfigDto = mono_proto_messages::messages_read_receipts_rpc::ReadReceiptConfigDto;

#[uniffi::remote(Record)]
pub struct ReadReceiptConfigDto {
    pub chat_read_mark_size_threshold: i32,
    pub chat_read_mark_expire_period: i32,
    pub pm_read_date_expire_period: i32,
    pub from_server: bool,
}
