//! https://core.telegram.org/api/auth
//! https://core.telegram.org/method/auth.sendCode

use tellers_mtproto_session::{OsRandom, Snapshot};

use crate::auth_rpc;
use crate::session_file::FileSessionStore;
use crate::{AuthCodeSent, AuthSignedIn, MtprotoError};

use super::*;

pub fn connect(handle: u64) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        if state.session_dead {
            // A server-revoked key must never be reused. Auth flow will create
            // a fresh key from sendCode; connect itself stays a harmless probe.
            return Ok(());
        }
        crate::rpc::set_use_test_dc(state.test_dc);
        let mut persist_needed = false;
        if !state.test_dc {
            crate::dns_txt::load_sidecar(&state.session_path);
            let home_dc = state.snapshot.dc_id;
            let cached = crate::dns_txt::apply_fresh_cache(&state.session_path, home_dc);
            ensure_ready(state)?;
            if !cached {
                crate::dns_txt::refresh_home_from_backup(
                    &state.session_path,
                    home_dc,
                    state.api_id,
                    &mut state.snapshot,
                );
                persist_needed = true;
            }
        } else {
            ensure_ready(state)?;
        }
        if state.user_id.is_some()
            && !state.test_dc
            && !crate::scheduler::main_session_allowance_known()
        {
            // One `help.getConfig` on the main session decides whether extra home-DC
            // sessions (read lanes, home-DC file lanes) are permitted at all.
            crate::dns_txt::probe_main_session_allowance(
                state.snapshot.dc_id,
                state.api_id,
                &mut state.snapshot,
            );
            persist_needed = true;
        }
        if persist_needed {
            persist(state)?;
        }
        recover_imported_user(state)?;
        Ok(())
    })
}

fn recover_imported_user(state: &mut ClientState) -> Result<(), MtprotoError> {
    if state.session_dead
        || state.user_id.is_some()
        || state.snapshot.auth_key.as_ref().map(|k| k.len()) != Some(256)
    {
        return Ok(());
    }
    match crate::profile::get_profile(
        &mut state.snapshot,
        state.api_id,
        &state.peers,
        &mut state.media,
        None,
        0,
    ) {
        Ok(profile) if profile.id > 0 => {
            state.user_id = Some(profile.id);
            persist(state)
        }
        Ok(_) => Ok(()),
        Err(_) => Ok(()),
    }
}

pub fn is_authorized(handle: u64) -> Result<bool, MtprotoError> {
    with_client_mut(handle, |state| {
        Ok(!state.session_dead
            && state.user_id.is_some()
            && state.snapshot.auth_key.as_ref().map(|k| k.len()) == Some(256))
    })
}

pub(crate) fn is_auth_restart(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(m) if m.contains("AUTH_RESTART"))
}

pub(crate) fn is_password_required(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::PasswordRequired)
        || matches!(err, MtprotoError::Message(m) if m.contains("SESSION_PASSWORD_NEEDED"))
}

pub(crate) fn apply_test_dc(
    state: &mut ClientState,
    want_test: bool,
    want_dc: i32,
) -> Result<(), MtprotoError> {
    if state.test_dc == want_test && (!want_test || state.snapshot.dc_id == want_dc) {
        crate::rpc::set_use_test_dc(want_test);
        return Ok(());
    }
    crate::rpc::drop_live_transport();
    crate::rpc::set_use_test_dc(want_test);
    state.test_dc = want_test;
    state.snapshot =
        Snapshot::new(want_dc, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
    state.session_dead = false;
    persist(state)
}

pub(crate) fn apply_test_dc_for_phone(
    state: &mut ClientState,
    phone: &str,
) -> Result<(), MtprotoError> {
    let from_phone = auth_rpc::test_dc_from_phone(phone);
    let want_test = state.test_dc || from_phone.is_some();
    let want_dc = from_phone.unwrap_or(if want_test {
        DEFAULT_DC_ID
    } else {
        state.snapshot.dc_id
    });
    apply_test_dc(state, want_test, want_dc)
}

pub fn set_client_test_dc(handle: u64, test: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| apply_test_dc(state, test, DEFAULT_DC_ID))
}

pub fn send_auth_code(handle: u64, phone: String) -> Result<AuthCodeSent, MtprotoError> {
    let sent = with_client_mut(handle, |state| {
        apply_test_dc_for_phone(state, &phone)?;
        if state.session_dead {
            recreate_mtproto_session(state)?;
        }
        let attempt = |state: &mut ClientState| {
            call_with_migrate(state, |state| {
                auth_rpc::send_code(
                    &mut state.snapshot,
                    state.api_id,
                    &state.api_hash,
                    &phone,
                    &state.logout_tokens,
                )
            })
        };
        let sent = match attempt(state) {
            Ok(sent) => sent,
            Err(err) if is_password_required(&err) => {
                // The previous sign-in may have left this auth key in a
                // server-side 2FA-pending state while the process/UI died.
                // sendCode is the explicit start of a new auth attempt, so
                // rotate the key and retry once instead of surfacing the stale
                // SESSION_PASSWORD_NEEDED to the phone form.
                recreate_mtproto_session(state)?;
                ensure_ready(state)?;
                attempt(state)?
            }
            Err(err) if is_auth_restart(&err) || crate::auth_rpc::is_srp_id_invalid(&err) => {
                // Stale 2FA/auth-key leftover from a previous attempt.
                recreate_mtproto_session(state)?;
                ensure_ready(state)?;
                attempt(state)?
            }
            Err(err) => return Err(err),
        };
        persist(state)?;
        Ok(sent)
    })?;
    drop_updates_transport(handle);
    Ok(sent)
}

pub fn resend_auth_code(
    handle: u64,
    phone: String,
    phone_code_hash: String,
) -> Result<AuthCodeSent, MtprotoError> {
    with_client_mut(handle, |state| {
        let sent = call_with_migrate(state, |state| {
            auth_rpc::resend_code(&mut state.snapshot, state.api_id, &phone, &phone_code_hash)
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub fn sign_in(
    handle: u64,
    phone: String,
    phone_code_hash: String,
    phone_code: String,
) -> Result<AuthSignedIn, MtprotoError> {
    let signed = with_client_mut(handle, |state| {
        let signed = match call_with_migrate(state, |state| {
            auth_rpc::sign_in(
                &mut state.snapshot,
                state.api_id,
                &phone,
                &phone_code_hash,
                &phone_code,
            )
        }) {
            Ok(signed) => signed,
            Err(err) if is_auth_restart(&err) => {
                recreate_mtproto_session(state)?;
                return Err(err);
            }
            Err(MtprotoError::PasswordRequired) => {
                // The auth key/session counters are valid and must survive a
                // process restart while the UI is on the 2FA password step.
                persist(state)?;
                return Err(MtprotoError::PasswordRequired);
            }
            Err(err) => return Err(err),
        };
        state.user_id = Some(signed.user_id);
        persist(state)?;
        Ok(signed)
    })?;
    drop_updates_transport(handle);
    Ok(signed)
}

pub fn check_password(handle: u64, password: String) -> Result<AuthSignedIn, MtprotoError> {
    let signed = with_client_mut(handle, |state| {
        let signed = match call_with_migrate(state, |state| {
            auth_rpc::check_password(&mut state.snapshot, state.api_id, &password)
        }) {
            Ok(signed) => signed,
            Err(err) if is_auth_restart(&err) => {
                // Surface restart to UI; session is reset so the next sendCode is clean.
                recreate_mtproto_session(state)?;
                return Err(err);
            }
            Err(err) => return Err(err),
        };
        state.user_id = Some(signed.user_id);
        persist(state)?;
        Ok(signed)
    })?;
    drop_updates_transport(handle);
    Ok(signed)
}

/// Revoke the server-side authorization when possible, then always tear down
/// the local session. A remote failure is returned to the bridge after the
/// local snapshot and all transports have been cleared.
pub fn logout(handle: u64) -> Result<(), MtprotoError> {
    let result = with_client_mut(handle, |state| {
        let remote = if state.user_id.is_some()
            && !state.session_dead
            && state.snapshot.auth_key.as_ref().map(|key| key.len()) == Some(256)
        {
            auth_rpc::log_out(&mut state.snapshot, state.api_id)
        } else {
            Ok(None)
        };

        let dc = state.snapshot.dc_id;
        if let Ok(Some(token)) = &remote {
            if !token.is_empty() && token.len() <= 256 {
                state.logout_tokens.push(token.clone());
                if state.logout_tokens.len() > 20 {
                    let keep_from = state.logout_tokens.len() - 20;
                    state.logout_tokens.drain(..keep_from);
                }
            }
        }
        state.user_id = None;
        state.peers.clear();
        state.updates = None;
        state.media.clear();
        state.channel_pts.clear();
        state.channel_recovery.clear();
        state.seen_messages.clear();
        state.updates_started = false;
        state.session_dead = false;
        state.new_session = None;
        state.snapshot =
            Snapshot::new(dc, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
        if state.logout_tokens.is_empty() {
            FileSessionStore::new(&state.session_path)
                .clear()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
        } else {
            persist(state)?;
        }

        remote.map(|_| ())
    });
    drop_all_transports(handle);
    result
}
