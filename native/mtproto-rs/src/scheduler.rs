//! Dispatch policy: which lane family serves a request class, and in what order a
//! freed lane is handed out. The updates lane stays FIFO (it owns `seq`/`pts`).
//! https://core.telegram.org/api/invoking

use std::collections::VecDeque;
use std::time::Duration;

use parking_lot::{Condvar, Mutex};

use crate::MtprotoError;

/// Lower [`RequestClass::priority`] wins inside a lane family.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RequestClass {
    InteractiveRead,
    BackgroundRead,
    InteractiveMedia,
    BackgroundMedia,
    InteractiveWrite,
}

impl RequestClass {
    pub fn bits(self) -> u8 {
        match self {
            RequestClass::InteractiveRead => 0,
            RequestClass::BackgroundRead => 1,
            RequestClass::InteractiveMedia => 2,
            RequestClass::BackgroundMedia => 3,
            RequestClass::InteractiveWrite => 4,
        }
    }

    pub fn priority(self) -> u8 {
        match self {
            RequestClass::InteractiveWrite => 0,
            RequestClass::InteractiveRead => 1,
            RequestClass::InteractiveMedia => 2,
            RequestClass::BackgroundRead => 3,
            RequestClass::BackgroundMedia => 4,
        }
    }

    fn from_bits(bits: u8) -> Option<RequestClass> {
        match bits {
            0 => Some(RequestClass::InteractiveRead),
            1 => Some(RequestClass::BackgroundRead),
            2 => Some(RequestClass::InteractiveMedia),
            3 => Some(RequestClass::BackgroundMedia),
            4 => Some(RequestClass::InteractiveWrite),
            _ => None,
        }
    }
}

impl std::fmt::Display for RequestClass {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let name = match self {
            RequestClass::InteractiveRead => "read",
            RequestClass::BackgroundRead => "background-read",
            RequestClass::InteractiveMedia => "media",
            RequestClass::BackgroundMedia => "background-media",
            RequestClass::InteractiveWrite => "write",
        };
        f.write_str(name)
    }
}

thread_local! {
    /// Set per request by the bridge, in the same context that carries cancellation.
    static DISPATCH_CLASS: std::cell::Cell<u8> = const { std::cell::Cell::new(0) };
}

pub fn set_current_class(class: RequestClass) {
    DISPATCH_CLASS.with(|cell| cell.set(class.bits()));
}

pub fn current_class() -> RequestClass {
    DISPATCH_CLASS
        .with(|cell| RequestClass::from_bits(cell.get()))
        .unwrap_or(RequestClass::InteractiveRead)
}

pub fn with_class<T>(class: RequestClass, body: impl FnOnce() -> T) -> T {
    struct Restore(u8);
    impl Drop for Restore {
        fn drop(&mut self) {
            DISPATCH_CLASS.with(|cell| cell.set(self.0));
        }
    }
    let previous = DISPATCH_CLASS.with(|cell| cell.replace(class.bits()));
    let _restore = Restore(previous);
    body()
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LaneFamily {
    Read,
    Media,
}

/// Home-DC forks (`invokeWithoutUpdates`) so one read never blocks another.
/// Only used when the server grants extra main sessions; see
/// [`extra_main_sessions`].
pub const READ_LANES: usize = 2;

/// Most parallel main sessions we will ever open on one non-media DC.
pub const MAX_MAIN_SESSIONS: i32 = 8;

/// `-1` until `config.tmp_sessions` has been seen.
static MAIN_SESSION_ALLOWANCE: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(-1);

/// Serializes tests that mutate the process-wide allowance.
#[cfg(test)]
pub(crate) static ALLOWANCE_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Records the server's permission for parallel main sessions.
///
/// A non-media DC detects parallel requests from two TCP connections of the same
/// authorization and answers `406 AUTH_KEY_DUPLICATED`, which **invalidates** the
/// authorization. `tmp_sessions` absent or ≤1 therefore means: a single main
/// session. Dedicated file-transfer sessions on media DCs are exempt.
/// <https://core.telegram.org/api/errors#406-not-acceptable>
pub fn set_main_session_allowance(tmp_sessions: Option<i32>) -> i32 {
    let allowance = tmp_sessions.unwrap_or(1).clamp(1, MAX_MAIN_SESSIONS);
    MAIN_SESSION_ALLOWANCE.store(allowance, std::sync::atomic::Ordering::Relaxed);
    allowance
}

/// Parallel main sessions the server allows; unknown counts as the safe single one.
pub fn main_session_allowance() -> i32 {
    match MAIN_SESSION_ALLOWANCE.load(std::sync::atomic::Ordering::Relaxed) {
        known if known >= 1 => known,
        _ => 1,
    }
}

pub fn main_session_allowance_known() -> bool {
    MAIN_SESSION_ALLOWANCE.load(std::sync::atomic::Ordering::Relaxed) >= 1
}

/// Extra main sessions remain disabled until each has a bound temporary PFS key.
/// A server allowance alone does not authorize reusing the permanent key across
/// parallel main sessions: https://core.telegram.org/api/datacenter#parallel-sessions
pub fn extra_main_sessions() -> usize {
    0
}

/// Home-DC read lanes the current allowance pays for.
pub fn read_lanes() -> usize {
    READ_LANES.min(extra_main_sessions())
}

/// Lanes are allocated up front; only `active_media_lanes()` are used.
pub const MAX_MEDIA_LANES: usize = 8;

pub const DEFAULT_MEDIA_LANES: usize = 6;

static ACTIVE_MEDIA_LANES: std::sync::atomic::AtomicUsize =
    std::sync::atomic::AtomicUsize::new(DEFAULT_MEDIA_LANES);

pub fn active_media_lanes() -> usize {
    ACTIVE_MEDIA_LANES
        .load(std::sync::atomic::Ordering::Relaxed)
        .clamp(1, MAX_MEDIA_LANES)
}

/// Runtime knob for the "Faster downloads" setting; applies to the next acquisition.
pub fn set_active_media_lanes(lanes: usize) {
    ACTIVE_MEDIA_LANES.store(
        lanes.clamp(1, MAX_MEDIA_LANES),
        std::sync::atomic::Ordering::Relaxed,
    );
}

pub fn family(class: RequestClass) -> LaneFamily {
    match class {
        RequestClass::InteractiveWrite
        | RequestClass::InteractiveRead
        | RequestClass::BackgroundRead => LaneFamily::Read,
        RequestClass::InteractiveMedia | RequestClass::BackgroundMedia => LaneFamily::Media,
    }
}

/// Read-lane acquisition order (sibling last). Concrete type so callers can clone it.
pub fn read_lane_order() -> std::iter::Rev<std::ops::Range<usize>> {
    (0..read_lanes()).rev()
}

/// Media-lane acquisition order over the *active* lanes. The gate decides who gets a
/// freed lane.
pub fn media_lane_order() -> std::ops::Range<usize> {
    0..active_media_lanes()
}

/// Priority gate for one lane, used by the read and media families.
///
/// Waiters are admitted by class priority, FIFO inside a class. Waits are timed so
/// cancellation (`request_control`) is honoured while queued.
pub struct LaneGate {
    state: Mutex<GateState>,
    wake: Condvar,
}

#[derive(Default)]
struct GateState {
    busy: bool,
    waiters: VecDeque<Waiter>,
    next_ticket: u64,
}

struct Waiter {
    ticket: u64,
    priority: u8,
}

/// Holds a lane; releases it (and wakes the best waiter) on drop.
pub struct LaneGuard<'a> {
    gate: &'a LaneGate,
}

impl Default for LaneGate {
    fn default() -> Self {
        Self {
            state: Mutex::new(GateState::default()),
            wake: Condvar::new(),
        }
    }
}

impl LaneGate {
    pub fn new() -> Self {
        Self::default()
    }

    /// Non-blocking admission. Fails when the lane is busy *or* when a higher-priority
    /// waiter is already queued, so a fast path can never jump the queue.
    pub fn try_acquire(&self, class: RequestClass) -> Option<LaneGuard<'_>> {
        let priority = class.priority();
        let mut state = self.state.lock();
        if state.busy
            || state
                .waiters
                .iter()
                .any(|waiter| waiter.priority < priority)
        {
            return None;
        }
        state.busy = true;
        Some(LaneGuard { gate: self })
    }

    /// Blocks until this request's class holds the lane.
    pub fn acquire(&self, class: RequestClass) -> Result<LaneGuard<'_>, MtprotoError> {
        let priority = class.priority();
        let ticket = {
            let mut state = self.state.lock();
            let ticket = state.next_ticket;
            state.next_ticket += 1;
            state.waiters.push_back(Waiter { ticket, priority });
            ticket
        };
        loop {
            if let Err(error) = crate::request_control::check() {
                self.state
                    .lock()
                    .waiters
                    .retain(|waiter| waiter.ticket != ticket);
                self.wake.notify_all();
                return Err(MtprotoError::Message(error.to_string()));
            }
            let mut state = self.state.lock();
            let best = state
                .waiters
                .iter()
                .min_by_key(|waiter| (waiter.priority, waiter.ticket))
                .map(|waiter| waiter.ticket);
            if !state.busy && best == Some(ticket) {
                state.busy = true;
                state.waiters.retain(|waiter| waiter.ticket != ticket);
                return Ok(LaneGuard { gate: self });
            }
            self.wake.wait_for(&mut state, Duration::from_millis(5));
        }
    }
}

impl Drop for LaneGuard<'_> {
    fn drop(&mut self) {
        {
            let mut state = self.gate.state.lock();
            state.busy = false;
        }
        self.gate.wake.notify_all();
    }
}

#[cfg(test)]
#[path = "../tests/unit/scheduler_tests.rs"]
mod tests;
