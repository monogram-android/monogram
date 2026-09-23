use crate::MtprotoError;
use crate::tcp;

use super::live::{LiveTransport, SUPERVISING, put_live, take_live};

pub(crate) fn is_waitable_io(message: &str) -> bool {
    let lower = message.to_ascii_lowercase();
    lower.contains("timed out")
        || lower.contains("would block")
        || lower.contains("try again")
        || lower.contains("eagain")
        || lower.contains("os error 11")
        || lower.contains("os error 35")
        || lower.contains("os error 60")
        || lower.contains("os error 10060")
        || lower.contains("wsaetimedout")
        || lower.contains("resource temporarily unavailable")
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum FailureClass {
    Transport,
    Protocol,
    Migration,
    Authorization,
    Cancelled,
    RpcRejected,
    Internal,
}

pub(crate) fn failure_class(err: &MtprotoError) -> FailureClass {
    let MtprotoError::Message(message) = err else {
        return FailureClass::RpcRejected;
    };
    // A correlated RPC error is authoritative. Never infer socket failure from
    // words inside its server-provided description (e.g. CONNECTION_NOT_INITED).
    if let Some(code) = message
        .strip_prefix("RPC ")
        .and_then(|v| v.split_once(':'))
        .and_then(|(code, _)| code.parse::<i32>().ok())
    {
        return match code {
            303 => FailureClass::Migration,
            401 => FailureClass::Authorization,
            406 if message.contains("AUTH_KEY_DUPLICATED") => FailureClass::Authorization,
            500..=599 => FailureClass::Internal,
            _ => FailureClass::RpcRejected,
        };
    }
    let lower = message.to_ascii_lowercase();
    if lower.contains("client closed") || lower.contains("cancelled") {
        return FailureClass::Cancelled;
    }
    if lower.contains("malformed")
        || lower.contains("envelope")
        || lower.contains("ciphertext")
        || lower.contains("msg_key")
        || lower.contains("invalid inbound message time")
        || lower.contains("bad_msg_notification")
        || lower.contains("invalid mtproto")
        || lower.contains("gzip")
        || lower.contains("wrapper depth")
    {
        return FailureClass::Protocol;
    }
    if !is_waitable_io(message)
        && (lower.contains("rpc timeout")
            || lower.contains("timeout")
            || lower.contains("connection")
            || lower.contains("broken pipe")
            || lower.contains("reset"))
    {
        FailureClass::Transport
    } else {
        FailureClass::Internal
    }
}

pub(crate) fn is_transport_error(err: &MtprotoError) -> bool {
    matches!(
        failure_class(err),
        FailureClass::Transport | FailureClass::Protocol
    )
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ConnectionState {
    Disconnected,
    Connecting,
    /// Obfuscation is established; the first authenticated exchange is pending.
    Handshaking,
    Ready,
    BackingOff,
    Closing,
}

/// The lane mutex protects cross-thread entry. This lease additionally rejects
/// reentrant RPCs on the same lane and owns its socket across all retry attempts.
/// There is no detached reconnect task that can outlive the invoking operation.
pub(crate) struct ConnectionSupervisor {
    lane: Option<usize>,
    pub(crate) state: ConnectionState,
    pub(crate) transport: Option<LiveTransport>,
}

impl ConnectionSupervisor {
    pub(crate) fn acquire(dc_id: i32) -> Result<Self, MtprotoError> {
        let lane = SUPERVISING.with(|stack| {
            let mut stack = stack.borrow_mut();
            let lane = stack.len().checked_sub(1);
            if let Some(index) = lane {
                if stack[index] {
                    return Err(MtprotoError::Message(
                        "RPC supervisor already active on lane".into(),
                    ));
                }
                stack[index] = true;
            }
            Ok(lane)
        })?;
        let transport = take_live(dc_id);
        Ok(Self {
            lane,
            state: if transport.is_some() {
                ConnectionState::Ready
            } else {
                ConnectionState::Disconnected
            },
            transport,
        })
    }

    pub(crate) fn disconnect(&mut self) {
        self.state = ConnectionState::Closing;
        // Dropping TCP closes the socket before a replacement can be opened.
        self.transport.take();
        self.state = ConnectionState::Disconnected;
    }

    pub(crate) fn backoff(&mut self, delay: std::time::Duration) -> Result<(), MtprotoError> {
        self.disconnect();
        self.state = ConnectionState::BackingOff;
        let result = tcp::wait_reconnect(delay).map_err(|e| MtprotoError::Message(e.to_string()));
        self.state = ConnectionState::Disconnected;
        result
    }

    pub(crate) fn connect(
        &mut self,
        open: impl FnOnce() -> Result<LiveTransport, MtprotoError>,
    ) -> Result<bool, MtprotoError> {
        tcp::wait_reconnect(std::time::Duration::ZERO)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        if self.transport.is_some() {
            debug_assert_eq!(self.state, ConnectionState::Ready);
            return Ok(true);
        }
        debug_assert_eq!(self.state, ConnectionState::Disconnected);
        self.state = ConnectionState::Connecting;
        match open() {
            Ok(transport) => {
                self.transport = Some(transport);
                self.state = ConnectionState::Handshaking;
                Ok(false)
            }
            Err(error) => {
                self.state = ConnectionState::Disconnected;
                Err(error)
            }
        }
    }

    pub(crate) fn park_ready(&mut self) {
        self.state = ConnectionState::Ready;
        if let Some(transport) = self.transport.take() {
            put_live(transport);
        }
    }
}

impl Drop for ConnectionSupervisor {
    fn drop(&mut self) {
        if self.transport.is_some() {
            self.disconnect();
        }
        if let Some(index) = self.lane {
            SUPERVISING.with(|stack| {
                if let Some(active) = stack.borrow_mut().get_mut(index) {
                    *active = false;
                }
            });
        }
    }
}
