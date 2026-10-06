//! https://core.telegram.org/mtproto/description
//! https://core.telegram.org/api/invoking

use tellers_mtproto_engine::{Engine, ExponentialBackoff, Method, RequestHandle};
use tellers_mtproto_session::{Clock, ReceivedMessageResult, Snapshot};
use tellers_mtproto_transport::{Error as TransportError, Framing};

use super::salts::SaltWindow;
use crate::MtprotoError;

/// A bad server salt is retryable. Only a bad message aborts the updates session.
pub(crate) fn updates_session_should_abort(retryable_salt: bool) -> bool {
    !retryable_salt
}

#[cfg(test)]
#[path = "../../tests/unit/rpc_invoke_updates_salt_tests.rs"]
mod updates_salt_tests;

fn take_salt_resend<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    message_id: i64,
    clock: &SystemClock,
) -> Result<Vec<tellers_mtproto_engine::OutboundMessage>, MtprotoError> {
    let err = tellers_mtproto_engine::Error::Authorization("bad_server_salt".into());
    match engine.fail_request(message_id, clock.unix_micros(), &err) {
        Ok(()) => {}
        Err(tellers_mtproto_engine::Error::UnknownRequest(_)) => return Ok(Vec::new()),
        Err(e) => return Err(MtprotoError::Message(e.to_string())),
    }
    engine
        .poll(clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut resent = Vec::new();
    while let Some(outbound) = engine.next_outbound() {
        resent.push(outbound);
    }
    Ok(resent)
}

fn remember_future_salts(snapshot: &mut Snapshot, windows: &[SaltWindow], clock: &SystemClock) {
    super::salts::store_windows(snapshot.session_id, windows);
    let now = (clock.unix_micros() / 1_000_000) as i32;
    if let Some(salt) = super::salts::choose_salt(windows, now) {
        snapshot.server_salt = salt;
    }
}

use super::dc::{extra_reconnect_same_host, reconnect_backoff, same_ip_endpoints};
use super::framing::{
    SystemClock, bad_msg_should_reconnect, flush_acks, make_padding, open_live, open_live_addr,
    queue_update, recover_detailed_answer, recreate_session_after_bad_message, recv_framed,
    repair_clock_from_server_msg_id, send_framed, send_ping, send_state_probe, try_decode_complete,
};
use super::inbound::{
    BAD_MSG_NOTIFICATION, InboundEvent, MAX_UNPACKED_BYTES, apply_new_session_salt, map_rpc_error,
    parse_authenticated, should_process_inbound,
};
use super::live::LiveTransport;
use super::supervisor::{ConnectionSupervisor, FailureClass, failure_class, is_transport_error};
use super::timeout::{
    LAST_INBOUND_CTOR, extend_streaming_deadlines, leftover_frame_grace, live_transport_stale,
    note_inbound_liveness, rpc_attempt_budget, rpc_timeout_message, rpc_timeout_secs,
    subscribed_read_deadline, trim_padded_mtproto_packet,
};

pub(crate) struct RawMethod {
    pub(crate) body: Vec<u8>,
}
impl Method for RawMethod {
    type Response = Vec<u8>;
    fn encode_request(&self) -> Result<Vec<u8>, tellers_mtproto_codec::Error> {
        Ok(self.body.clone())
    }
    fn decode_response(bytes: &[u8]) -> Result<Self::Response, tellers_mtproto_codec::Error> {
        Ok(bytes.to_vec())
    }
}

/// Invoke a pre-encoded boxed TL request and return the raw `rpc_result` payload.
pub fn invoke_raw(snapshot: &mut Snapshot, request_body: Vec<u8>) -> Result<Vec<u8>, MtprotoError> {
    invoke_raw_with_retry(snapshot, request_body, false)
}

#[derive(Clone, Copy)]
pub(crate) struct RpcRetryPolicy {
    pub(crate) backoff: ExponentialBackoff,
    pub(crate) replay_safe: bool,
}

impl tellers_mtproto_engine::RetryPolicy for RpcRetryPolicy {
    fn deadline(&self, attempt: u32, now: i64) -> i64 {
        self.backoff.deadline(attempt, now)
    }

    fn after_failure(
        &self,
        attempt: u32,
        now: i64,
        error: &tellers_mtproto_engine::Error,
    ) -> tellers_mtproto_engine::RetryDecision {
        if matches!(error, tellers_mtproto_engine::Error::Timeout { .. }) {
            return tellers_mtproto_engine::RetryDecision::Fail;
        }
        self.backoff.after_failure(attempt, now, error)
    }
}

pub(crate) fn may_reconnect_request(replay_safe: bool, send_started: bool) -> bool {
    replay_safe || !send_started
}

fn check_silent_requests<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    transport: &mut LiveTransport,
    clock: &SystemClock,
    silent: &mut super::main_policy::SilentRequests,
    ids: &[i64],
    sent: bool,
    attempt: &mut std::time::Instant,
    overall: &mut std::time::Instant,
    hard_cap: std::time::Instant,
) -> Result<(), MtprotoError> {
    let now = std::time::Instant::now();
    silent.observe(ids, now);
    if !sent {
        return Ok(());
    }
    let due = silent.due(now, now >= *overall);
    if due.is_empty() {
        return Ok(());
    }
    let probe = send_state_probe(
        engine,
        &mut transport.conn,
        &mut transport.framing,
        &due,
        clock,
    )?;
    transport.last_io = now;
    silent.probed(probe, due);
    // Give a status response a bounded grace period before any reconnect retry.
    *overall = (*overall)
        .max(now + std::time::Duration::from_secs(5))
        .min(hard_cap);
    *attempt = (*attempt).max(*overall);
    Ok(())
}

pub(crate) fn invoke_raw_with_retry(
    snapshot: &mut Snapshot,
    request_body: Vec<u8>,
    replay_safe: bool,
) -> Result<Vec<u8>, MtprotoError> {
    invoke_raw_with_retry_factory(snapshot, replay_safe, |_| Ok(request_body.clone()))
}

/// Invoke an API body which may need to be rebuilt for each TCP connection.
///
/// `new_connection` is true for the first request on a newly-opened socket,
/// including replay-safe retries after a transport failure. MTProto API callers
/// use it to attach `invokeWithLayer(initConnection(...))` exactly where the
/// protocol requires it, without keeping that wrapper on every request.
pub(crate) fn invoke_raw_with_retry_factory<F>(
    snapshot: &mut Snapshot,
    replay_safe: bool,
    mut request_body: F,
) -> Result<Vec<u8>, MtprotoError>
where
    F: FnMut(bool) -> Result<Vec<u8>, MtprotoError>,
{
    if snapshot.auth_key.as_ref().map(|k| k.len()) != Some(256) {
        return Err(MtprotoError::Message(
            "auth key required before API RPC".into(),
        ));
    }

    let clock = SystemClock;
    let timeout_secs = rpc_timeout_secs();
    let policy = RpcRetryPolicy {
        replay_safe,
        backoff: ExponentialBackoff {
            timeout_micros: (timeout_secs as i64) * 1_000_000,
            initial_delay_micros: 0,
            max_attempts: 3,
        },
    };
    let mut last_error: Option<MtprotoError> = None;
    let started = std::time::Instant::now();
    let overall_deadline = started + std::time::Duration::from_secs(timeout_secs);
    let hard_cap = started
        + std::time::Duration::from_secs(timeout_secs.saturating_mul(2).max(timeout_secs + 8));
    let dc_for_eps = snapshot.dc_id;
    let pinned = same_ip_endpoints(dc_for_eps);
    let extras: Vec<crate::dns_txt::TxtEndpoint> = crate::dns_txt::endpoints_for(dc_for_eps)
        .into_iter()
        .filter(|e| extra_reconnect_same_host(&pinned, &e.addr))
        .take(3)
        .collect();
    let same_ip_tries = pinned.len().min(3).max(1);
    let max_attempts = same_ip_tries + extras.len();
    let mut supervisor = ConnectionSupervisor::acquire(snapshot.dc_id)?;
    for attempt in 0..max_attempts {
        let mut remaining = overall_deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        if attempt > 0 {
            let delay = reconnect_backoff(attempt);
            if remaining <= delay {
                break;
            }
            supervisor.backoff(delay)?;
            remaining = overall_deadline.saturating_duration_since(std::time::Instant::now());
        }
        let last = attempt + 1 == max_attempts;
        let budget = rpc_attempt_budget(
            attempt,
            last,
            remaining.max(std::time::Duration::from_secs(1)),
        );
        let attempt_deadline = std::time::Instant::now() + budget;
        let attempt_deadline = if attempt_deadline > overall_deadline {
            overall_deadline
        } else {
            attempt_deadline
        };
        let io_secs = budget.as_secs().max(1);
        let mut engine = Engine::new(snapshot.clone(), policy)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        let dc_id = engine.session.dc_id;
        if attempt > 0 {
            eprintln!("monogram.api reconnect attempt={attempt} after transport error");
        }
        let opened = supervisor.connect(|| {
            if attempt < same_ip_tries {
                // Same host:port as the live session. Rotating 443→5222→80 after a
                // stall is what produced recv=0 on :80 / :5222 through the emulator path.
                open_live(dc_id, &engine.session, io_secs, 0)
            } else {
                let extra = &extras[attempt - same_ip_tries];
                eprintln!(
                    "monogram.api reconnect dns-txt dc={} addr={}",
                    extra.dc_id, extra.addr
                );
                open_live_addr(
                    extra.dc_id,
                    extra.addr.as_str(),
                    extra.secret.as_ref().map(|s| s.as_slice()),
                    io_secs,
                )
            }
        });
        let reused = match opened {
            Ok(reused) => reused,
            Err(err) if is_transport_error(&err) => {
                last_error = Some(err);
                continue;
            }
            Err(err) => return Err(err),
        };
        let transport = supervisor
            .transport
            .as_mut()
            .expect("connected supervisor transport");
        let endpoint = transport.endpoint.clone();
        transport.conn.set_io_timeout(io_secs);
        let mut send_started = false;
        let body = request_body(!reused)?;
        let result = invoke_until_result(
            &mut engine,
            transport,
            body,
            &clock,
            attempt_deadline,
            overall_deadline,
            hard_cap,
            reused,
            &mut send_started,
        );
        match result {
            Ok(value) => {
                *snapshot = engine.session;
                supervisor.park_ready();
                return Ok(value);
            }
            Err(err) if is_transport_error(&err) => {
                // bad_msg 32/33 and clock fixes mutate engine.session; keep them
                // so the retry uses a fresh seqno instead of replaying the same one.
                *snapshot = engine.session;
                if !may_reconnect_request(replay_safe, send_started) {
                    return Err(err);
                }
                last_error = Some(MtprotoError::Message(format!(
                    "{err} via {endpoint} reconnect"
                )));
                // Never put a timed-out or partially consumed stream back into
                // the lane. Close it before retry backoff starts.
                supervisor.disconnect();
            }
            Err(err) => {
                *snapshot = engine.session;
                // A completed validation/flood RPC does not invalidate the
                // authenticated connection. Keep it only at a frame boundary.
                if failure_class(&err) == FailureClass::RpcRejected && transport.input.is_empty() {
                    supervisor.park_ready();
                }
                return Err(err);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| MtprotoError::Message("RPC timeout".into())))
}

/// Sends the whole batch on one session before awaiting, then pumps until every request
/// is matched by `rpc_result`. Per-request RPC errors come back as `Err` entries;
/// transport or timeout failures abort the batch.
pub(crate) fn invoke_batch_raw_with_retry<F>(
    snapshot: &mut Snapshot,
    replay_safe: bool,
    make_bodies: F,
) -> Result<Vec<Result<Vec<u8>, MtprotoError>>, MtprotoError>
where
    F: FnMut(bool) -> Result<Vec<Vec<u8>>, MtprotoError>,
{
    invoke_batch_raw_with_retry_streaming(snapshot, replay_safe, make_bodies, &mut |_, _| None)
}

pub(crate) fn invoke_batch_raw_with_retry_streaming<F>(
    snapshot: &mut Snapshot,
    replay_safe: bool,
    mut make_bodies: F,
    on_chunk: &mut dyn FnMut(usize, Result<&[u8], &MtprotoError>) -> Option<Vec<u8>>,
) -> Result<Vec<Result<Vec<u8>, MtprotoError>>, MtprotoError>
where
    F: FnMut(bool) -> Result<Vec<Vec<u8>>, MtprotoError>,
{
    if snapshot.auth_key.as_ref().map(|k| k.len()) != Some(256) {
        return Err(MtprotoError::Message(
            "auth key required before API RPC".into(),
        ));
    }
    let clock = SystemClock;
    let timeout_secs = rpc_timeout_secs();
    let policy = RpcRetryPolicy {
        replay_safe,
        backoff: ExponentialBackoff {
            timeout_micros: (timeout_secs as i64) * 1_000_000,
            initial_delay_micros: 0,
            max_attempts: 3,
        },
    };
    let mut last_error: Option<MtprotoError> = None;
    let started = std::time::Instant::now();
    let overall_deadline = started + std::time::Duration::from_secs(timeout_secs);
    let hard_cap = started
        + std::time::Duration::from_secs(timeout_secs.saturating_mul(2).max(timeout_secs + 8));
    let dc_for_eps = snapshot.dc_id;
    let pinned = same_ip_endpoints(dc_for_eps);
    let extras: Vec<crate::dns_txt::TxtEndpoint> = crate::dns_txt::endpoints_for(dc_for_eps)
        .into_iter()
        .filter(|e| extra_reconnect_same_host(&pinned, &e.addr))
        .take(3)
        .collect();
    let same_ip_tries = pinned.len().min(3).max(1);
    let max_attempts = same_ip_tries + extras.len();
    let mut supervisor = ConnectionSupervisor::acquire(snapshot.dc_id)?;
    for attempt in 0..max_attempts {
        let mut remaining = overall_deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        if attempt > 0 {
            let delay = reconnect_backoff(attempt);
            if remaining <= delay {
                break;
            }
            supervisor.backoff(delay)?;
            remaining = overall_deadline.saturating_duration_since(std::time::Instant::now());
        }
        let last = attempt + 1 == max_attempts;
        let budget = rpc_attempt_budget(
            attempt,
            last,
            remaining.max(std::time::Duration::from_secs(1)),
        );
        let attempt_deadline = std::time::Instant::now() + budget;
        let attempt_deadline = if attempt_deadline > overall_deadline {
            overall_deadline
        } else {
            attempt_deadline
        };
        let io_secs = budget.as_secs().max(1);
        let mut engine = Engine::new(snapshot.clone(), policy)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        let dc_id = engine.session.dc_id;
        let opened = supervisor.connect(|| {
            if attempt < same_ip_tries {
                open_live(dc_id, &engine.session, io_secs, 0)
            } else {
                let extra = &extras[attempt - same_ip_tries];
                open_live_addr(
                    extra.dc_id,
                    extra.addr.as_str(),
                    extra.secret.as_ref().map(|s| s.as_slice()),
                    io_secs,
                )
            }
        });
        let reused = match opened {
            Ok(reused) => reused,
            Err(err) if is_transport_error(&err) => {
                last_error = Some(err);
                continue;
            }
            Err(err) => return Err(err),
        };
        let transport = supervisor
            .transport
            .as_mut()
            .expect("connected supervisor transport");
        let endpoint = transport.endpoint.clone();
        transport.conn.set_io_timeout(io_secs);
        let mut send_started = false;
        // A fresh connection needs InitConnection again.
        let bodies = make_bodies(!reused)?;
        if bodies.is_empty() {
            return Ok(Vec::new());
        }
        let result = invoke_batch_until_results_streaming(
            &mut engine,
            transport,
            &bodies,
            &clock,
            attempt_deadline,
            overall_deadline,
            hard_cap,
            reused,
            &mut send_started,
            &mut *on_chunk,
        );
        match result {
            Ok(values) => {
                *snapshot = engine.session;
                supervisor.park_ready();
                return Ok(values);
            }
            Err(err) if is_transport_error(&err) => {
                *snapshot = engine.session;
                if !may_reconnect_request(replay_safe, send_started) {
                    return Err(err);
                }
                last_error = Some(MtprotoError::Message(format!(
                    "{err} via {endpoint} reconnect"
                )));
                supervisor.disconnect();
            }
            Err(err) => {
                *snapshot = engine.session;
                return Err(err);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| MtprotoError::Message("RPC timeout".into())))
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn invoke_batch_until_results<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    transport: &mut LiveTransport,
    bodies: &[Vec<u8>],
    clock: &SystemClock,
    attempt_deadline: std::time::Instant,
    overall_deadline: std::time::Instant,
    hard_cap: std::time::Instant,
    reused: bool,
    send_started: &mut bool,
) -> Result<Vec<Result<Vec<u8>, MtprotoError>>, MtprotoError> {
    invoke_batch_until_results_streaming(
        engine,
        transport,
        bodies,
        clock,
        attempt_deadline,
        overall_deadline,
        hard_cap,
        reused,
        send_started,
        |_, _| None,
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn invoke_batch_until_results_streaming<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    transport: &mut LiveTransport,
    bodies: &[Vec<u8>],
    clock: &SystemClock,
    mut attempt_deadline: std::time::Instant,
    mut overall_deadline: std::time::Instant,
    mut hard_cap: std::time::Instant,
    reused: bool,
    send_started: &mut bool,
    mut on_chunk: impl FnMut(usize, Result<&[u8], &MtprotoError>) -> Option<Vec<u8>>,
) -> Result<Vec<Result<Vec<u8>, MtprotoError>>, MtprotoError> {
    LAST_INBOUND_CTOR.with(|c| c.set(0));
    let mut pending: Vec<Option<RequestHandle<Vec<u8>>>> = Vec::with_capacity(bodies.len());
    for body in bodies {
        let method = RawMethod { body: body.clone() };
        let handle = engine
            .invoke(&method, clock)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        pending.push(Some(handle));
    }
    let mut silent = super::main_policy::SilentRequests::new(
        crate::scheduler::family(crate::scheduler::current_class())
            == crate::scheduler::LaneFamily::Media,
    );
    let mut results: Vec<Option<Result<Vec<u8>, MtprotoError>>> =
        (0..bodies.len()).map(|_| None).collect();
    let mut received = 0usize;
    let mut leftover_grace = false;
    let mut ping_inflight: Option<i64> = None;
    let mut wait_for_keepalive =
        reused && live_transport_stale(transport.last_io, std::time::Instant::now());
    if wait_for_keepalive {
        ping_inflight = Some(send_ping(
            engine,
            &mut transport.conn,
            &mut transport.framing,
            clock,
        )?);
        transport.last_io = std::time::Instant::now();
    }
    loop {
        let ids: Vec<i64> = pending
            .iter()
            .flatten()
            .map(|handle| handle.message_id())
            .collect();
        check_silent_requests(
            engine,
            transport,
            clock,
            &mut silent,
            &ids,
            *send_started && !wait_for_keepalive,
            &mut attempt_deadline,
            &mut overall_deadline,
            hard_cap,
        )?;
        let mut recv_attempt = silent
            .next_deadline()
            .map_or(attempt_deadline, |due| attempt_deadline.min(due));
        let mut recv_overall = overall_deadline;
        if std::time::Instant::now() > overall_deadline {
            match transport.framing.decode(&transport.input) {
                Ok(_) => {}
                Err(TransportError::Incomplete { needed, available }) => {
                    let now = std::time::Instant::now();
                    if available > 0 && now < hard_cap {
                        leftover_grace = true;
                        recv_overall = (now + std::time::Duration::from_secs(2)).min(hard_cap);
                        recv_attempt = recv_overall;
                    } else if leftover_frame_grace(leftover_grace, available) && now < hard_cap {
                        leftover_grace = true;
                        recv_overall = (now + std::time::Duration::from_secs(8)).min(hard_cap);
                        recv_attempt = recv_overall;
                    } else {
                        return Err(MtprotoError::Message(rpc_timeout_message(
                            received,
                            &transport.input,
                            needed,
                            available,
                        )));
                    }
                }
                Err(_) => {
                    return Err(MtprotoError::Message(rpc_timeout_message(
                        received,
                        &transport.input,
                        0,
                        transport.input.len(),
                    )));
                }
            }
        }

        flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;

        if !wait_for_keepalive {
            while let Some(outbound) = engine.next_outbound() {
                let padding = make_padding(outbound.body.len())?;
                let sealed = engine
                    .seal_outbound(&outbound, &padding)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                *send_started = true;
                send_framed(&mut transport.conn, &mut transport.framing, &sealed)?;
                transport.last_io = std::time::Instant::now();
            }

            let mut complete = true;
            let mut refills: Vec<Vec<u8>> = Vec::new();
            for (index, slot) in pending.iter_mut().enumerate() {
                if results[index].is_some() {
                    continue;
                }
                let Some(handle) = slot.as_ref() else {
                    continue;
                };
                if let Some(response) = engine
                    .take_response::<RawMethod>(handle)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?
                {
                    let mapped = match map_rpc_error(&response) {
                        Some(err) => {
                            if let Some(body) = on_chunk(index, Err(&err)) {
                                refills.push(body);
                            }
                            Err(err)
                        }
                        None => {
                            if let Some(body) = on_chunk(index, Ok(&response)) {
                                refills.push(body);
                            }
                            Ok(response)
                        }
                    };
                    results[index] = Some(mapped);
                    *slot = None;
                    extend_streaming_deadlines(
                        &mut attempt_deadline,
                        &mut overall_deadline,
                        &mut hard_cap,
                    );
                } else {
                    complete = false;
                }
            }
            for body in refills {
                let method = RawMethod { body };
                let handle = engine
                    .invoke(&method, clock)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                pending.push(Some(handle));
                results.push(None);
                complete = false;
            }
            if complete {
                flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;
                return Ok(results
                    .into_iter()
                    .map(|value| {
                        value.unwrap_or_else(|| {
                            Err(MtprotoError::Message("missing rpc result".into()))
                        })
                    })
                    .collect());
            }
        }

        let packet = match recv_framed(
            &mut transport.conn,
            &mut transport.framing,
            &mut transport.input,
            &mut received,
            recv_attempt,
            recv_overall,
            reused,
            2_000,
        ) {
            Ok(packet) => {
                note_inbound_liveness(&mut ping_inflight);
                transport.last_io = std::time::Instant::now();
                wait_for_keepalive = false;
                packet
            }
            Err(err) if wait_for_keepalive => return Err(err),
            Err(err)
                if matches!(&err, MtprotoError::Message(message) if message.starts_with("RPC timeout"))
                    && transport.input.is_empty()
                    && (std::time::Instant::now() < overall_deadline
                        || !silent.due(std::time::Instant::now(), true).is_empty()) =>
            {
                continue;
            }
            Err(err) => return Err(err),
        };
        let packet = trim_padded_mtproto_packet(&packet);
        let inbound = engine
            .open_inbound(packet, clock, true, 1024 * 1024)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        transport.ping_sent = None;
        if inbound.disposition == ReceivedMessageResult::InvalidTime {
            repair_clock_from_server_msg_id(&mut engine.session, inbound.message.message_id, clock);
            return Err(MtprotoError::Message(
                "invalid inbound message time; reconnect".into(),
            ));
        }
        if !should_process_inbound(inbound.disposition) {
            continue;
        }
        let body = inbound.message.body;
        if body.len() >= 4 {
            let ctor = u32::from_le_bytes(body[0..4].try_into().unwrap());
            LAST_INBOUND_CTOR.with(|c| c.set(ctor));
        }
        for event in parse_authenticated(
            &body,
            inbound.message.message_id,
            &mut engine.session,
            clock,
        )? {
            match event {
                InboundEvent::RpcResult { req_msg_id, body } => {
                    match engine.receive_result(req_msg_id, body, inbound.message.message_id) {
                        Ok(()) => {}
                        Err(tellers_mtproto_engine::Error::UnknownRequest(_)) => {}
                        Err(e) => {
                            return Err(MtprotoError::Message(e.to_string()));
                        }
                    }
                }
                InboundEvent::SaltUpdated { body } => {
                    apply_new_session_salt(&mut engine.session, &body)?;
                }
                InboundEvent::RetryableFailure { message_id } => {
                    replay_updates_rejection(
                        engine,
                        &mut transport.conn,
                        &mut transport.framing,
                        clock,
                        message_id,
                        Some(&mut silent),
                    )?;
                }
                InboundEvent::BadMessage {
                    bad_msg_id,
                    error_code,
                } => {
                    if engine
                        .session
                        .apply_bad_message(error_code, bad_msg_id, inbound.message.message_id)
                        .is_err()
                    {
                        engine.session.last_message_id = 0;
                    }
                    if bad_msg_should_reconnect(error_code) {
                        recreate_session_after_bad_message(&mut engine.session)?;
                        return Err(MtprotoError::Message(format!(
                            "bad_msg_notification {error_code} recv={received} last_ctor={BAD_MSG_NOTIFICATION:#x}"
                        )));
                    }
                    if matches!(error_code, 19 | 64) {
                        return Err(MtprotoError::Message(format!(
                            "bad_msg_notification {error_code}"
                        )));
                    }
                    for outbound in take_salt_resend(engine, bad_msg_id, clock)? {
                        silent.resent(bad_msg_id, outbound.message_id, std::time::Instant::now());
                        let padding = make_padding(outbound.body.len())?;
                        let sealed = engine
                            .seal_outbound(&outbound, &padding)
                            .map_err(|e| MtprotoError::Message(e.to_string()))?;
                        send_framed(&mut transport.conn, &mut transport.framing, &sealed)?;
                        transport.last_io = std::time::Instant::now();
                    }
                }
                InboundEvent::FutureSalts(windows) => {
                    remember_future_salts(&mut engine.session, &windows, clock);
                }
                InboundEvent::Pong { ping_id } => {
                    if ping_inflight == Some(ping_id) || ping_inflight.is_some() {
                        ping_inflight = None;
                    }
                }
                InboundEvent::Updates(body) => queue_update(transport, body)?,
                InboundEvent::AnswerAvailable { answer_msg_id } => {
                    recover_detailed_answer(engine, transport, answer_msg_id, clock)?;
                }
                InboundEvent::MessageStatus { req_msg_id, info } => {
                    silent.status(req_msg_id, &info)?
                }
                InboundEvent::Ignored => {}
            }
        }
        flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;
    }
}

fn replay_updates_rejection<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    conn: &mut dyn tellers_mtproto_transport::Connection,
    framing: &mut tellers_mtproto_transport::PaddedIntermediate,
    clock: &SystemClock,
    message_id: i64,
    mut silent: Option<&mut super::main_policy::SilentRequests>,
) -> Result<bool, MtprotoError> {
    let resent = take_salt_resend(engine, message_id, clock)?;
    if !resent.is_empty() {
        for outbound in resent {
            if let Some(silent) = silent.as_mut() {
                silent.resent(message_id, outbound.message_id, std::time::Instant::now());
            }
            let padding = make_padding(outbound.body.len())?;
            let sealed = engine
                .seal_outbound(&outbound, &padding)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            send_framed(conn, framing, &sealed)?;
        }
        return Ok(true);
    }
    super::framing::resend_plaintext(engine, conn, framing, clock, message_id)
}

fn ingest_update_packet<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    transport: &mut LiveTransport,
    packet: Vec<u8>,
    clock: &SystemClock,
) -> Result<Option<i64>, MtprotoError> {
    let inbound = engine
        .open_inbound(
            trim_padded_mtproto_packet(&packet),
            clock,
            true,
            MAX_UNPACKED_BYTES,
        )
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    if inbound.disposition == ReceivedMessageResult::InvalidTime {
        return Err(MtprotoError::Message("invalid inbound message time".into()));
    }
    if !should_process_inbound(inbound.disposition) {
        return Ok(None);
    }
    transport.ping_sent = None;
    transport.last_io = std::time::Instant::now();
    let mut pong = None;
    for event in parse_authenticated(
        &inbound.message.body,
        inbound.message.message_id,
        &mut engine.session,
        clock,
    )? {
        match event {
            InboundEvent::Updates(body) => queue_update(transport, body)?,
            InboundEvent::AnswerAvailable { answer_msg_id } => {
                recover_detailed_answer(engine, transport, answer_msg_id, clock)?;
            }
            InboundEvent::SaltUpdated { body } => {
                apply_new_session_salt(&mut engine.session, &body)?
            }
            InboundEvent::RetryableFailure { message_id } => {
                replay_updates_rejection(
                    engine,
                    &mut transport.conn,
                    &mut transport.framing,
                    clock,
                    message_id,
                    None,
                )?;
            }
            InboundEvent::FutureSalts(windows) => {
                remember_future_salts(&mut engine.session, &windows, clock);
            }
            InboundEvent::BadMessage { .. } => {
                if updates_session_should_abort(false) {
                    return Err(MtprotoError::Message(
                        "updates session rejected; recovery required".into(),
                    ));
                }
            }
            InboundEvent::Pong { ping_id } => pong = Some(ping_id),
            InboundEvent::RpcResult { .. }
            | InboundEvent::MessageStatus { .. }
            | InboundEvent::Ignored => {}
        }
    }
    flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;
    Ok(pong)
}

pub(crate) fn receive_updates(snapshot: &mut Snapshot) -> Result<Vec<Vec<u8>>, MtprotoError> {
    let mut owner = ConnectionSupervisor::acquire(snapshot.dc_id)?;
    let transport = owner
        .transport
        .as_mut()
        .ok_or_else(|| MtprotoError::Message("updates connection unavailable".into()))?;
    let clock = SystemClock;
    let policy = RpcRetryPolicy {
        replay_safe: false,
        backoff: ExponentialBackoff {
            timeout_micros: 20_000_000,
            initial_delay_micros: 0,
            max_attempts: 1,
        },
    };
    let mut engine =
        Engine::new(snapshot.clone(), policy).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut received = 0;
    if transport
        .ping_sent
        .map(|sent| sent.elapsed() >= std::time::Duration::from_secs(20))
        .unwrap_or(false)
    {
        return Err(MtprotoError::Message(
            "updates connection ping timeout".into(),
        ));
    }
    if transport.ping_sent.is_none()
        && live_transport_stale(transport.last_io, std::time::Instant::now())
    {
        send_ping(
            &mut engine,
            &mut transport.conn,
            &mut transport.framing,
            &clock,
        )?;
        transport.ping_sent = Some(std::time::Instant::now());
        transport.last_io = std::time::Instant::now();
    }
    // This reader shares the main RPC lane: idle polling must yield promptly.
    let deadline = subscribed_read_deadline(transport.last_io, transport.ping_sent)
        .min(std::time::Instant::now() + std::time::Duration::from_millis(100));
    let idle_wait_ms = deadline
        .saturating_duration_since(std::time::Instant::now())
        .as_millis() as u64;
    while transport.updates.is_empty() {
        let packet = match recv_framed(
            &mut transport.conn,
            &mut transport.framing,
            &mut transport.input,
            &mut received,
            deadline,
            deadline,
            false,
            idle_wait_ms,
        ) {
            Ok(packet) => packet,
            Err(MtprotoError::Message(message)) if message.starts_with("RPC timeout") => break,
            Err(error) => return Err(error),
        };
        ingest_update_packet(&mut engine, transport, packet, &clock)?;
        if std::time::Instant::now() >= deadline {
            break;
        }
    }
    // Decode additional complete frames already in `input` without another socket wait.
    while let Some(packet) = try_decode_complete(&mut transport.framing, &mut transport.input)? {
        ingest_update_packet(&mut engine, transport, packet, &clock)?;
        if std::time::Instant::now() >= deadline {
            break;
        }
    }
    let updates = std::mem::take(&mut transport.updates);
    transport.updates_bytes = 0;
    *snapshot = engine.session;
    owner.park_ready();
    Ok(updates)
}

pub(crate) fn ping_existing_rtt(
    snapshot: &mut Snapshot,
) -> Result<Option<std::time::Duration>, MtprotoError> {
    let mut owner = ConnectionSupervisor::acquire(snapshot.dc_id)?;
    let Some(transport) = owner.transport.as_mut() else {
        return Ok(None);
    };
    let clock = SystemClock;
    let mut engine = Engine::new(
        snapshot.clone(),
        ExponentialBackoff {
            timeout_micros: 2_000_000,
            initial_delay_micros: 0,
            max_attempts: 1,
        },
    )
    .map_err(|error| MtprotoError::Message(error.to_string()))?;
    let started = std::time::Instant::now();
    let deadline = started + std::time::Duration::from_secs(2);
    let ping_id = send_ping(
        &mut engine,
        &mut transport.conn,
        &mut transport.framing,
        &clock,
    )?;
    let mut received = 0;
    loop {
        let packet = recv_framed(
            &mut transport.conn,
            &mut transport.framing,
            &mut transport.input,
            &mut received,
            deadline,
            deadline,
            false,
            2_000,
        )?;
        let pong = ingest_update_packet(&mut engine, transport, packet, &clock)?;
        if pong == Some(ping_id) {
            *snapshot = engine.session;
            owner.park_ready();
            return Ok(Some(started.elapsed()));
        }
        if std::time::Instant::now() >= deadline {
            return Err(MtprotoError::Message(
                "RPC timeout measuring upload RTT".into(),
            ));
        }
    }
}

pub(crate) fn flush_pending_acks(
    snapshot: &mut Snapshot,
    transport: &mut LiveTransport,
) -> Result<(), MtprotoError> {
    let mut engine = Engine::new(
        snapshot.clone(),
        ExponentialBackoff {
            timeout_micros: 8_000_000,
            initial_delay_micros: 0,
            max_attempts: 1,
        },
    )
    .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let result = flush_acks(
        &mut engine,
        &mut transport.conn,
        &mut transport.framing,
        &SystemClock,
    );
    *snapshot = engine.session;
    if result.is_ok() {
        transport.last_io = std::time::Instant::now();
    }
    result
}

pub(crate) fn invoke_until_result<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    transport: &mut LiveTransport,
    request_body: Vec<u8>,
    clock: &SystemClock,
    mut attempt_deadline: std::time::Instant,
    mut overall_deadline: std::time::Instant,
    hard_cap: std::time::Instant,
    reused: bool,
    send_started: &mut bool,
) -> Result<Vec<u8>, MtprotoError> {
    LAST_INBOUND_CTOR.with(|c| c.set(0));
    let method = RawMethod { body: request_body };
    let handle = engine
        .invoke(&method, clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut silent = super::main_policy::SilentRequests::new(
        crate::scheduler::family(crate::scheduler::current_class())
            == crate::scheduler::LaneFamily::Media,
    );
    let mut received = 0usize;
    let mut leftover_grace = false;
    let mut ping_inflight: Option<i64> = None;
    let mut wait_for_keepalive =
        reused && live_transport_stale(transport.last_io, std::time::Instant::now());
    if wait_for_keepalive {
        ping_inflight = Some(send_ping(
            engine,
            &mut transport.conn,
            &mut transport.framing,
            clock,
        )?);
        transport.last_io = std::time::Instant::now();
    }
    loop {
        check_silent_requests(
            engine,
            transport,
            clock,
            &mut silent,
            &[handle.message_id()],
            *send_started && !wait_for_keepalive,
            &mut attempt_deadline,
            &mut overall_deadline,
            hard_cap,
        )?;
        let mut recv_attempt = silent
            .next_deadline()
            .map_or(attempt_deadline, |due| attempt_deadline.min(due));
        let mut recv_overall = overall_deadline;
        if std::time::Instant::now() > overall_deadline {
            match transport.framing.decode(&transport.input) {
                Ok(_) => {}
                Err(TransportError::Incomplete { needed, available }) => {
                    let now = std::time::Instant::now();
                    if available > 0 && now < hard_cap {
                        leftover_grace = true;
                        recv_overall = (now + std::time::Duration::from_secs(2)).min(hard_cap);
                        recv_attempt = recv_overall;
                    } else if leftover_frame_grace(leftover_grace, available) && now < hard_cap {
                        leftover_grace = true;
                        recv_overall = (now + std::time::Duration::from_secs(8)).min(hard_cap);
                        recv_attempt = recv_overall;
                    } else {
                        return Err(MtprotoError::Message(rpc_timeout_message(
                            received,
                            &transport.input,
                            needed,
                            available,
                        )));
                    }
                }
                Err(_) => {
                    return Err(MtprotoError::Message(rpc_timeout_message(
                        received,
                        &transport.input,
                        0,
                        transport.input.len(),
                    )));
                }
            }
        }

        flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;

        if !wait_for_keepalive {
            while let Some(outbound) = engine.next_outbound() {
                let padding = make_padding(outbound.body.len())?;
                let sealed = engine
                    .seal_outbound(&outbound, &padding)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                // write_all can fail after sending a prefix. The result is then
                // unknown, even when the transport reports an error.
                *send_started = true;
                send_framed(&mut transport.conn, &mut transport.framing, &sealed)?;
                transport.last_io = std::time::Instant::now();
            }

            if let Some(response) = engine
                .take_response::<RawMethod>(&handle)
                .map_err(|e| MtprotoError::Message(e.to_string()))?
            {
                flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;
                if let Some(err) = map_rpc_error(&response) {
                    return Err(err);
                }
                return Ok(response);
            }
        }

        let packet = match recv_framed(
            &mut transport.conn,
            &mut transport.framing,
            &mut transport.input,
            &mut received,
            recv_attempt,
            recv_overall,
            reused,
            2_000,
        ) {
            Ok(packet) => {
                // Any inbound is liveness.
                note_inbound_liveness(&mut ping_inflight);
                transport.last_io = std::time::Instant::now();
                wait_for_keepalive = false;
                packet
            }
            Err(err) if wait_for_keepalive => return Err(err),
            Err(err)
                if matches!(&err, MtprotoError::Message(message) if message.starts_with("RPC timeout"))
                    && transport.input.is_empty()
                    && (std::time::Instant::now() < overall_deadline
                        || !silent.due(std::time::Instant::now(), true).is_empty()) =>
            {
                continue;
            }
            Err(err) => return Err(err),
        };
        let packet = trim_padded_mtproto_packet(&packet);
        let inbound = engine
            .open_inbound(packet, clock, true, 1024 * 1024)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        transport.ping_sent = None;
        if inbound.disposition == ReceivedMessageResult::InvalidTime {
            repair_clock_from_server_msg_id(&mut engine.session, inbound.message.message_id, clock);
            // A message outside the accepted time window is never parsed on
            // this connection. Reconnect after repairing the clock so the
            // packet cannot be replayed or correlated on a stale transport.
            return Err(MtprotoError::Message(
                "invalid inbound message time; reconnect".into(),
            ));
        }
        // Security guidelines require dropping replayed, too-old, and mistimed
        // packets after authentication. Their bodies must never be correlated
        // with an outstanding RPC or interpreted as a service message.
        if !should_process_inbound(inbound.disposition) {
            continue;
        }
        let body = inbound.message.body;
        if body.len() >= 4 {
            let ctor = u32::from_le_bytes(body[0..4].try_into().unwrap());
            LAST_INBOUND_CTOR.with(|c| c.set(ctor));
        }
        for event in parse_authenticated(
            &body,
            inbound.message.message_id,
            &mut engine.session,
            clock,
        )? {
            match event {
                InboundEvent::RpcResult { req_msg_id, body } => {
                    match engine.receive_result(req_msg_id, body, inbound.message.message_id) {
                        Ok(()) => {}
                        Err(tellers_mtproto_engine::Error::UnknownRequest(_)) => {}
                        Err(e) => {
                            return Err(MtprotoError::Message(e.to_string()));
                        }
                    }
                }
                InboundEvent::SaltUpdated { body } => {
                    apply_new_session_salt(&mut engine.session, &body)?;
                }
                InboundEvent::RetryableFailure { message_id } => {
                    replay_updates_rejection(
                        engine,
                        &mut transport.conn,
                        &mut transport.framing,
                        clock,
                        message_id,
                        Some(&mut silent),
                    )?;
                }
                InboundEvent::BadMessage {
                    bad_msg_id,
                    error_code,
                } => {
                    // https://core.telegram.org/mtproto/service_messages_about_messages
                    if engine
                        .session
                        .apply_bad_message(error_code, bad_msg_id, inbound.message.message_id)
                        .is_err()
                    {
                        engine.session.last_message_id = 0;
                    }
                    if bad_msg_should_reconnect(error_code) {
                        // A sequence mismatch can be larger than one after a stale
                        // persisted snapshot. A new session_id gives it an independent
                        // seqno space while retaining this DC's authorization key.
                        recreate_session_after_bad_message(&mut engine.session)?;
                        return Err(MtprotoError::Message(format!(
                            "bad_msg_notification {error_code} recv={received} last_ctor={BAD_MSG_NOTIFICATION:#x}"
                        )));
                    }
                    if matches!(error_code, 19 | 64) {
                        return Err(MtprotoError::Message(format!(
                            "bad_msg_notification {error_code}"
                        )));
                    }
                    for outbound in take_salt_resend(engine, bad_msg_id, clock)? {
                        silent.resent(bad_msg_id, outbound.message_id, std::time::Instant::now());
                        let padding = make_padding(outbound.body.len())?;
                        let sealed = engine
                            .seal_outbound(&outbound, &padding)
                            .map_err(|e| MtprotoError::Message(e.to_string()))?;
                        send_framed(&mut transport.conn, &mut transport.framing, &sealed)?;
                        transport.last_io = std::time::Instant::now();
                    }
                    // A rejected ping/ack is not evidence that the RPC failed.
                }
                InboundEvent::FutureSalts(windows) => {
                    remember_future_salts(&mut engine.session, &windows, clock);
                }
                InboundEvent::Pong { ping_id } => {
                    if ping_inflight == Some(ping_id) || ping_inflight.is_some() {
                        ping_inflight = None;
                    }
                }
                InboundEvent::Updates(body) => queue_update(transport, body)?,
                InboundEvent::AnswerAvailable { answer_msg_id } => {
                    recover_detailed_answer(engine, transport, answer_msg_id, clock)?;
                }
                InboundEvent::MessageStatus { req_msg_id, info } => {
                    silent.status(req_msg_id, &info)?
                }
                InboundEvent::Ignored => {}
            }
        }
        flush_acks(engine, &mut transport.conn, &mut transport.framing, clock)?;
    }
}
