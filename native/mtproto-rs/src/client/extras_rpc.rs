pub use mono_proto_client::adapter::messages::extras_rpc::*;

#[uniffi::remote(Record)]
pub struct ReactionPeerDto {
    pub peer_id: i64,
    pub title: String,
    pub date: i32,
    pub emoticon: String,
    pub document_id: i64,
}

#[uniffi::remote(Record)]
pub struct ReactionPeersDto {
    pub next_offset: Option<String>,
    pub peers: Vec<ReactionPeerDto>,
    pub count: i32,
}

#[uniffi::remote(Record)]
pub struct PollVoterDto {
    pub peer_id: i64,
    pub title: String,
    pub date: i32,
    pub options: Vec<Vec<u8>>,
}

#[uniffi::remote(Record)]
pub struct PollVotersDto {
    pub next_offset: Option<String>,
    pub voters: Vec<PollVoterDto>,
    pub count: i32,
}
