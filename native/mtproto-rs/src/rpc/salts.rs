//! Prefetched server salts. The active salt is the valid one with the latest `valid_until`.
//! https://core.telegram.org/api/optimisation

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use tellers_mtproto_session::Snapshot;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SaltWindow {
    pub valid_since: i32,
    pub valid_until: i32,
    pub salt: i64,
}

struct Book {
    windows: HashMap<i64, Vec<SaltWindow>>,
    requested_at: HashMap<i64, i32>,
}

static BOOK: LazyLock<Mutex<Book>> = LazyLock::new(|| {
    Mutex::new(Book {
        windows: HashMap::new(),
        requested_at: HashMap::new(),
    })
});

fn book() -> std::sync::MutexGuard<'static, Book> {
    BOOK.lock().unwrap_or_else(|poison| poison.into_inner())
}

pub(crate) fn choose_salt(windows: &[SaltWindow], now: i32) -> Option<i64> {
    windows
        .iter()
        .filter(|window| window.valid_since <= now && now < window.valid_until)
        .max_by_key(|window| window.valid_until)
        .map(|window| window.salt)
}

pub(crate) fn store_windows(session_id: i64, windows: &[SaltWindow]) {
    let mut book = book();
    book.windows.insert(session_id, windows.to_vec());
    if !windows.is_empty() {
        book.requested_at.remove(&session_id);
    }
}

pub(crate) fn preferred_salt(session_id: i64, now: i32) -> Option<i64> {
    let book = book();
    book.windows
        .get(&session_id)
        .and_then(|windows| choose_salt(windows, now))
}

pub(crate) fn mark_requested(session_id: i64, now: i32) {
    book().requested_at.insert(session_id, now);
}

/// Prefetch when no salt window is stored. A missed answer can be retried after 30 seconds.
pub(crate) fn should_prefetch(session_id: i64, now: i32) -> bool {
    let book = book();
    if book
        .windows
        .get(&session_id)
        .is_some_and(|windows| !windows.is_empty())
    {
        return false;
    }
    match book.requested_at.get(&session_id) {
        Some(sent_at) => now.saturating_sub(*sent_at) >= 30,
        None => true,
    }
}

pub(crate) fn apply_preferred_salt(snapshot: &mut Snapshot, now: i32) {
    if let Some(salt) = preferred_salt(snapshot.session_id, now) {
        snapshot.server_salt = salt;
    }
}

#[cfg(test)]
#[path = "../../tests/unit/rpc_salts_tests.rs"]
mod tests;
