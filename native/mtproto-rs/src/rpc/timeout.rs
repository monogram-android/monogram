use std::cell::Cell;

use crate::MtprotoError;

// Per OS thread: Kotlin runs drain / history / media on different threads.
// A process-wide atomic let getHistory(8s) steal download_media(45s) mid-wait.
thread_local! {
    static RPC_TIMEOUT_SECS: Cell<u64> = const { Cell::new(20) };
}

pub fn with_rpc_timeout_secs<T>(secs: u64, body: impl FnOnce() -> T) -> T {
    RPC_TIMEOUT_SECS.with(|cell| {
        struct Restore<'a>(&'a Cell<u64>, u64);
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                self.0.set(self.1);
            }
        }
        let previous = cell.replace(secs);
        let _restore = Restore(cell, previous);
        body()
    })
}

pub(crate) fn rpc_timeout_secs() -> u64 {
    RPC_TIMEOUT_SECS.with(|cell| cell.get()).max(1)
}

/// Idle timeout for a streaming getFile window: each completed part proves the
/// socket is alive, so the transfer may exceed the original RPC budget.
pub(crate) fn extend_streaming_deadlines(
    attempt_deadline: &mut std::time::Instant,
    overall_deadline: &mut std::time::Instant,
    hard_cap: &mut std::time::Instant,
) {
    let timeout = std::time::Duration::from_secs(rpc_timeout_secs());
    let now = std::time::Instant::now();
    *overall_deadline = now + timeout;
    *hard_cap = now
        + std::time::Duration::from_secs(
            rpc_timeout_secs()
                .saturating_mul(2)
                .max(rpc_timeout_secs() + 8),
        );
    *attempt_deadline = now + timeout;
}

/// Padded intermediate appends 0..=15 random bytes after the encrypted envelope.
/// Encrypted MTProto length is 24 + 16k (auth_key_id + msg_key + AES blocks).
pub(crate) fn trim_padded_mtproto_packet(packet: &[u8]) -> &[u8] {
    let mut n = packet.len();
    while n >= 24 && n % 16 != 8 {
        n -= 1;
    }
    if n < 24 { packet } else { &packet[..n] }
}

thread_local! {
    pub(crate) static LAST_INBOUND_CTOR: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

pub(crate) fn rpc_timeout_message(
    received: usize,
    input: &[u8],
    needed: usize,
    available: usize,
) -> String {
    let prefix = if input.len() >= 4 {
        u32::from_le_bytes(input[..4].try_into().expect("four bytes"))
    } else {
        0
    };
    let ctor = LAST_INBOUND_CTOR.with(|c| c.get());
    format!(
        "RPC timeout recv={received} needed={needed} available={available} prefix={prefix} last_ctor={ctor:#x}"
    )
}

/// Idle attempts reserve a reconnect window. Mid-frame reads still use the
/// overall RPC deadline so a large in-flight padded frame is not abandoned.
pub(crate) fn rpc_attempt_budget(
    _attempt: usize,
    last: bool,
    remaining: std::time::Duration,
) -> std::time::Duration {
    if last {
        return remaining;
    }
    let reserve = std::cmp::min(std::time::Duration::from_secs(2), remaining / 4);
    let floor = std::time::Duration::from_secs(1);
    if remaining <= reserve.saturating_add(floor) {
        remaining
    } else {
        remaining - reserve
    }
}

pub(crate) fn is_mid_frame(needed: usize, available: usize) -> bool {
    available > 0 && (needed > available || needed == 0)
}

/// Leftover encrypted bytes after a decrypted container. Finish that frame
/// instead of `needed=0` abort + new TCP (logs: prefix=630 available=314).
pub(crate) fn leftover_frame_grace(used: bool, available: usize) -> bool {
    !used && available > 0
}

/// Empty buffer waiting for the next 4-byte length, after we already drained
/// complete frames. Do not spend the rest of the RPC budget idle.
pub(crate) fn idle_after_complete_frames(
    received: usize,
    needed: usize,
    available: usize,
    last_ctor: u32,
) -> bool {
    // Leftover 4-byte length after bytes, but no decrypted TL yet.
    // Do not fail-fast on msg_container/acks: history/media still expect
    // rpc_result in the next frame (emulator: recv=60KiB last_ctor=0x73f1f8dc).
    received > 0 && available == 0 && needed <= 4 && last_ctor == 0
}

/// New TCP accepted but silent (`drain updates` recv=0 last_ctor=0x0).
/// Fail before the whole RPC budget is burned.
pub(crate) fn idle_empty_first_byte(received: usize, needed: usize, available: usize) -> bool {
    received == 0 && available == 0 && needed <= 4
}

/// Parked/reused live TCP with no first length prefix (`loadMoreChats` log:
/// recv=0 needed=4 available=0 last_ctor=0x0). Fail fast so invoke_raw reconnects.
pub(crate) fn idle_reused_socket(
    reused: bool,
    received: usize,
    needed: usize,
    available: usize,
) -> bool {
    reused && received == 0 && available == 0 && needed <= 4
}

pub(crate) fn fail_fast_idle(
    reused: bool,
    received: usize,
    needed: usize,
    available: usize,
    last_ctor: u32,
) -> bool {
    idle_after_complete_frames(received, needed, available, last_ctor)
        || idle_reused_socket(reused, received, needed, available)
}

/// Silence after a decrypted service frame (msg_container/updates/acks).
/// Do not fail-fast the RPC: rpc_result may still be inbound. Probe with ping.
/// https://core.telegram.org/mtproto/service_messages#ping-messages-ping-pong
pub(crate) fn idle_needs_liveness_probe(
    received: usize,
    needed: usize,
    available: usize,
    last_ctor: u32,
) -> bool {
    received > 0 && available == 0 && needed <= 4 && last_ctor != 0
}

pub(crate) fn capture_timeout_field(message: &str, key: &str) -> Option<usize> {
    let rest = message.split(key).nth(1)?;
    let token = rest.split(|c: char| !c.is_ascii_digit()).next()?;
    token.parse().ok()
}

pub(crate) fn last_ctor_from_timeout(message: &str) -> Option<u32> {
    let rest = message.split("last_ctor=").nth(1)?;
    let token = rest.split_whitespace().next()?;
    let hex = token.strip_prefix("0x").unwrap_or(token);
    u32::from_str_radix(hex, 16).ok()
}

pub(crate) fn timeout_idle_needs_probe(err: &MtprotoError) -> bool {
    let MtprotoError::Message(message) = err else {
        return false;
    };
    if !message.contains("RPC timeout") {
        return false;
    }
    let recv = capture_timeout_field(message, "recv=").unwrap_or(0);
    let needed = capture_timeout_field(message, "needed=").unwrap_or(0);
    let available = capture_timeout_field(message, "available=").unwrap_or(0);
    let last_ctor = last_ctor_from_timeout(message).unwrap_or(0);
    idle_needs_liveness_probe(recv, needed, available, last_ctor)
}

/// Generic keepalive ping interval.
pub(crate) const KEEPALIVE_PING_SECS: u64 = 19;

pub(crate) fn live_transport_stale(last_io: std::time::Instant, now: std::time::Instant) -> bool {
    now.duration_since(last_io) >= std::time::Duration::from_secs(KEEPALIVE_PING_SECS)
}

pub(crate) fn subscribed_read_deadline(
    last_io: std::time::Instant,
    ping_sent: Option<std::time::Instant>,
) -> std::time::Instant {
    match ping_sent {
        Some(sent) => sent + std::time::Duration::from_secs(20),
        None => last_io + std::time::Duration::from_secs(KEEPALIVE_PING_SECS),
    }
}

/// Treats any inbound as liveness, not only `pong`.
pub(crate) fn note_inbound_liveness(ping_inflight: &mut Option<i64>) {
    *ping_inflight = None;
}

pub(crate) fn keepalive_probe_failed(ping_inflight: Option<i64>) -> bool {
    ping_inflight.is_some()
}

pub(crate) fn recv_wait_deadline(
    attempt_deadline: std::time::Instant,
    overall_deadline: std::time::Instant,
    needed: usize,
    available: usize,
) -> std::time::Instant {
    if is_mid_frame(needed, available) {
        overall_deadline
    } else {
        attempt_deadline
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extend_streaming_deadlines_moves_forward() {
        let now = std::time::Instant::now();
        let mut attempt = now;
        let mut overall = now;
        let mut hard = now;
        extend_streaming_deadlines(&mut attempt, &mut overall, &mut hard);
        assert!(overall > now);
        assert!(hard > overall);
        assert!(attempt > now);
    }
}
