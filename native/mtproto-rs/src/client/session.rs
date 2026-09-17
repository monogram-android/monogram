use tellers_mtproto_session::{OsRandom, Snapshot};
use tellers_mtproto_transport::PaddedIntermediate;

use crate::api_invoke;
use crate::auth_key::create_auth_key;
use crate::rpc::dc_endpoints;
use crate::tcp;
use crate::MtprotoError;

use super::*;

pub(crate) fn fork_session(home: &Snapshot) -> Snapshot {
    let mut forked = Snapshot::new(home.dc_id, &mut OsRandom).expect("updates session");
    forked.auth_key = home.auth_key.clone();
    forked.server_salt = home.server_salt;
    forked.time_offset_micros = home.time_offset_micros;
    forked
}

pub(crate) fn apply_home_auth(
    target: &mut Snapshot,
    dc: i32,
    auth: Option<Vec<u8>>,
    salt: i64,
    time_offset: i64,
) {
    let changed = target.dc_id != dc || target.auth_key != auth;
    target.dc_id = dc;
    target.auth_key = auth;
    target.server_salt = salt;
    target.time_offset_micros = time_offset;
    if changed {
        let fresh = Snapshot::new(dc, &mut OsRandom).expect("resession");
        target.session_id = fresh.session_id;
        target.last_message_id = 0;
        target.content_sequence = 0;
        target.pending_acknowledgements.clear();
        target.received_message_ids.clear();
        target.reconnect = Default::default();
    }
}

pub(crate) fn ensure_auth_key_on(snapshot: &mut Snapshot) -> Result<(), MtprotoError> {
    if snapshot.auth_key.as_ref().map(|k| k.len()) == Some(256) {
        return Ok(());
    }
    // TCP connect + DH auth-key exchange, timed apart from the lane wait.
    let span = crate::perf::span("handshake");
    let mut conn = tcp::connect_obfuscated_dc(dc_endpoints(snapshot.dc_id))
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut framing = PaddedIntermediate::default();
    create_auth_key(&mut conn, &mut framing, snapshot)?;
    drop(span);
    Ok(())
}

pub(crate) fn ensure_auth_key(state: &mut ClientState) -> Result<(), MtprotoError> {
    if state.snapshot.auth_key.as_ref().map(Vec::len) == Some(256) {
        return Ok(());
    }
    ensure_auth_key_on(&mut state.snapshot)?;
    persist(state)
}

pub(crate) fn copy_authorization_to_dc(
    home: &mut Snapshot,
    api_id: i32,
    target_dc: i32,
) -> Result<Snapshot, MtprotoError> {
    use tellers_mtproto::latest::api::{
        AuthAuthorization, AuthExportAuthorizationRequest, AuthExportedAuthorization,
        AuthImportAuthorizationRequest,
    };
    let exported: AuthExportedAuthorization = crate::api_invoke::invoke_api(
        home,
        api_id,
        AuthExportAuthorizationRequest { dc_id: target_dc },
    )?;
    let AuthExportedAuthorization::AuthExportedAuthorization(exp) = exported;
    let mut dest = fork_session(home);
    dest.dc_id = target_dc;
    dest.auth_key = None;
    dest.server_salt = 0;
    crate::rpc::drop_live_transport();
    ensure_auth_key_on(&mut dest)?;
    let _: AuthAuthorization = crate::api_invoke::invoke_api(
        &mut dest,
        api_id,
        AuthImportAuthorizationRequest {
            id: exp.id,
            bytes: exp.bytes,
        },
    )?;
    // The destination snapshot leaves this lane; never retain its TCP here.
    crate::rpc::drop_live_transport();
    Ok(dest)
}

pub(crate) fn is_unrecoverable_session(err: &MtprotoError) -> bool {
    let MtprotoError::Message(msg) = err else {
        return false;
    };
    let upper = msg.to_ascii_uppercase();
    upper.contains("AUTH_KEY_UNREGISTERED")
        || upper.contains("AUTH_KEY_INVALID")
        || upper.contains("AUTH_KEY_DUPLICATED")
        || upper.contains("SESSION_REVOKED")
        || upper.contains("SESSION_EXPIRED")
        || upper.contains("USER_DEACTIVATED")
}

pub(crate) fn mark_session_dead(state: &mut ClientState, err: &MtprotoError) {
    state.session_dead = true;
    state.session_dead_reason = Some(session_dead_token(err));
    crate::rpc::drop_live_transport();
    let _ = persist(state);
}

/// `RPC 406: AUTH_KEY_DUPLICATED` → `AUTH_KEY_DUPLICATED`. Only fixed uppercase
/// tokens travel to the bridge; anything else is `UNKNOWN`, so no payload can leak.
pub(crate) fn session_dead_token(err: &MtprotoError) -> String {
    let MtprotoError::Message(msg) = err else {
        return "UNKNOWN".into();
    };
    let body = msg
        .split_once(": ")
        .map(|(_, rest)| rest)
        .unwrap_or(msg.as_str());
    let token: String = body
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || *c == '_')
        .collect();
    if token.len() >= 3 {
        token
    } else {
        "UNKNOWN".into()
    }
}

/// Bridge-visible form of a locally known dead session.
///
/// Deliberately *not* shaped like an `rpc_error`: the authorization was already
/// invalidated server-side (406), and a synthetic `401 AUTH_KEY_UNREGISTERED` both
/// hides that cause from the logs and makes the bridge treat a local gate as a
/// fresh server rejection.
pub(crate) fn session_invalidated_error(state: &ClientState) -> MtprotoError {
    let reason = state.session_dead_reason.as_deref().unwrap_or("UNKNOWN");
    MtprotoError::Message(format!("session invalidated: {reason}"))
}

pub(crate) fn reject_if_dead(state: &ClientState) -> Result<(), MtprotoError> {
    if state.session_dead {
        return Err(session_invalidated_error(state));
    }
    Ok(())
}

pub(crate) fn ensure_ready_after_migrate(
    state: &mut ClientState,
    err: MtprotoError,
) -> Result<(), MtprotoError> {
    if let MtprotoError::Message(msg) = &err {
        if let Some(rest) = msg.strip_prefix("MIGRATE_") {
            if let Ok(dc) = rest.parse::<i32>() {
                if state.user_id.is_some()
                    && state.snapshot.auth_key.as_ref().map(|key| key.len()) == Some(256)
                {
                    // An authorized account must move its authorization with
                    // exportAuthorization/importAuthorization. Dropping the
                    // home auth key here turns a normal USER/NETWORK_MIGRATE
                    // into an unnecessary logout and breaks all update lanes.
                    let mut home = state.snapshot.clone();
                    let destination = copy_authorization_to_dc(&mut home, state.api_id, dc)?;
                    state.snapshot = destination;
                    // Authorization moved, but these are still the same
                    // account's applied update sequences.
                    state.session_dead = false;
                    return Ok(());
                }
                state.snapshot.dc_id = dc;
                state.snapshot.auth_key = None;
                state.snapshot.server_salt = 0;
                ensure_auth_key(state)?;
                return Ok(());
            }
        }
    }
    Err(err)
}

pub(crate) fn call_with_migrate<T>(
    state: &mut ClientState,
    mut op: impl FnMut(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    reject_if_dead(state)?;
    ensure_ready(state)?;
    let before = state.snapshot.clone();
    match op(state) {
        Ok(v) => Ok(v),
        Err(err) if is_unrecoverable_session(&err) => {
            mark_session_dead(state, &err);
            Err(err)
        }
        Err(err) => {
            // api_invoke normalizes all non-file MIGRATE errors to
            // `MIGRATE_<dc>`. Restore the home snapshot before exporting
            // authorization in case the failed RPC changed session metadata.
            if api_invoke::migrate_dc(&err).is_some() {
                state.snapshot = before;
            }
            ensure_ready_after_migrate(state, err)?;
            match op(state) {
                Ok(v) => Ok(v),
                Err(err) if is_unrecoverable_session(&err) => {
                    mark_session_dead(state, &err);
                    Err(err)
                }
                Err(err) => Err(err),
            }
        }
    }
}

pub(crate) fn drop_updates_transport(handle: u64) {
    crate::rpc::drop_live_transport();
    if let Ok(client) = get_client(handle) {
        let mut main = client.main.lock();
        main.transport = None;
        main.last_difference = None;
        main.pending_push = Default::default();
    }
}

pub(crate) fn drop_all_transports(handle: u64) {
    crate::rpc::drop_live_transport();
    if let Ok(client) = get_client(handle) {
        client.main.lock().transport = None;
        for lane in client.rpc.iter() {
            lane.io.lock().transport = None;
        }
        for lane in client.media.iter() {
            lane.io.lock().transport = None;
        }
    }
}

pub(crate) fn recreate_mtproto_session(state: &mut ClientState) -> Result<(), MtprotoError> {
    let dc = state.snapshot.dc_id;
    state.user_id = None;
    state.updates = None;
    state.updates_started = false;
    state.session_dead = false;
    crate::rpc::drop_live_transport();
    state.media.clear();
    // Keep peer cache; drop auth key so the next RPC builds a fresh session.
    state.snapshot =
        Snapshot::new(dc, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
    persist(state)
}
