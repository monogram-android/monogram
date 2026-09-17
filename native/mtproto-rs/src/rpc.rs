//! Encrypted MTProto RPC over padded-intermediate framing using Tellers Engine.
//! https://core.telegram.org/mtproto/description
//! https://core.telegram.org/mtproto/mtproto-transports
//! https://core.telegram.org/mtproto/service_messages

mod dc;
mod framing;
mod inbound;
mod invoke;
mod live;
mod supervisor;
mod timeout;

pub use dc::{dc_endpoints, rotated_endpoints, same_ip_endpoints, set_use_test_dc, use_test_dc};
pub use framing::SystemClock;
pub(crate) use inbound::ungzip_if_needed;
pub use inbound::{
    BoxedQuery, NewSessionMetadata, clear_new_session_metadata, encode_boxed_bytes,
    take_new_session_metadata,
};
pub use invoke::invoke_raw;
pub use live::{LiveTransport, drop_live_transport, with_live_transport};
pub use timeout::with_rpc_timeout_secs;

pub(crate) use invoke::{
    invoke_batch_raw_with_retry, invoke_raw_with_retry_factory, receive_updates,
};

#[cfg(test)]
use crate::MtprotoError;
#[cfg(test)]
use crate::tcp;
#[cfg(test)]
use std::io::Read;
#[cfg(test)]
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
#[cfg(test)]
use tellers_mtproto::transport::{
    GzipPackedConstructor, MsgResendReqConstructor, NewSessionCreatedConstructor, PingRequest,
    PongConstructor, RpcErrorConstructor, Vector,
};
#[cfg(test)]
use tellers_mtproto_engine::{Engine, ExponentialBackoff};
#[cfg(test)]
use tellers_mtproto_session::{Clock, OsRandom, ReceivedMessageResult, Snapshot};
#[cfg(test)]
use tellers_mtproto_transport::{Framing, PaddedIntermediate};

#[cfg(test)]
pub(crate) use dc::{
    extra_reconnect_same_host, reconnect_backoff, reconnect_delay, rotated_same_ip,
};
#[cfg(test)]
pub(crate) use framing::{
    bad_msg_should_reconnect, clock_delta_micros, detailed_answer_request, open_live_addr,
    queue_update, recreate_session_after_bad_message,
};
#[cfg(test)]
pub(crate) use inbound::{
    BAD_MSG_NOTIFICATION, BAD_SERVER_SALT, InboundEvent, MAX_UNPACKED_BYTES, MAX_WRAPPER_DEPTH,
    MSG_CONTAINER, MSG_COPY, MSGS_ACK, NEW_SESSION_CREATED, PONG, RPC_RESULT,
    apply_new_session_salt, is_updates_type, parse_authenticated, parse_service_or_result,
    should_process_inbound,
};
#[cfg(test)]
pub(crate) use invoke::{RawMethod, RpcRetryPolicy, may_reconnect_request};
#[cfg(test)]
pub(crate) use live::{live_stack_depth, live_top_occupied};
#[cfg(test)]
pub(crate) use supervisor::{
    ConnectionState, ConnectionSupervisor, FailureClass, failure_class, is_transport_error,
    is_waitable_io,
};
#[cfg(test)]
pub(crate) use timeout::{
    KEEPALIVE_PING_SECS, fail_fast_idle, idle_after_complete_frames, idle_empty_first_byte,
    idle_needs_liveness_probe, idle_reused_socket, is_mid_frame, keepalive_probe_failed,
    leftover_frame_grace, live_transport_stale, note_inbound_liveness, recv_wait_deadline,
    rpc_attempt_budget, rpc_timeout_message, rpc_timeout_secs, subscribed_read_deadline,
    timeout_idle_needs_probe, trim_padded_mtproto_packet,
};

#[cfg(test)]
#[path = "rpc_tests.rs"]
mod tests;
