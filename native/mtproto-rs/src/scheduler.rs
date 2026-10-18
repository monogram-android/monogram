//! Dispatch policy: which lane family serves a request class, and in what order a
//! freed lane is handed out. The updates lane stays FIFO (it owns `seq`/`pts`).
//! https://core.telegram.org/api/invoking

use std::collections::VecDeque;
use std::sync::Arc;
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

    pub(crate) fn from_bits(bits: u8) -> Option<RequestClass> {
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

pub const UPLOAD_LANES: usize = 4;

/// Most parallel main sessions we will ever open on one non-media DC.
pub const MAX_MAIN_SESSIONS: i32 = 8;

/// Serializes tests that change unbound policy defaults.
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
    crate::transfer_policy::current().main_session_allowance.store(allowance, std::sync::atomic::Ordering::Relaxed);
    allowance
}

/// Parallel main sessions the server allows; unknown counts as the safe single one.
pub fn main_session_allowance() -> i32 {
    match crate::transfer_policy::current().main_session_allowance.load(std::sync::atomic::Ordering::Relaxed) {
        known if known >= 1 => known,
        _ => 1,
    }
}

pub fn main_session_allowance_known() -> bool {
    crate::transfer_policy::current().main_session_allowance.load(std::sync::atomic::Ordering::Relaxed) >= 1
}

/// Records how many extra home sessions have a bound temporary key.
///
/// A server allowance alone does not authorize reusing the permanent key.
/// https://core.telegram.org/api/datacenter#parallel-sessions
pub fn set_bound_extra_sessions(count: usize) {
    crate::transfer_policy::current().bound_extra_sessions.store(count.min(READ_LANES), std::sync::atomic::Ordering::Relaxed);
}

/// Extra main sessions that may be used. Stays zero until a temp key is bound.
pub fn extra_main_sessions() -> usize {
    let bound = crate::transfer_policy::current().bound_extra_sessions.load(std::sync::atomic::Ordering::Relaxed);
    if bound == 0 {
        return 0;
    }
    let extras = usize::try_from(main_session_allowance().saturating_sub(1)).unwrap_or(0);
    bound.min(extras).min(READ_LANES)
}

/// Home-DC read lanes the current allowance pays for.
pub fn read_lanes() -> usize {
    READ_LANES.min(extra_main_sessions())
}

/// Lanes are allocated up front; only `active_media_lanes()` are used.
pub const MAX_MEDIA_LANES: usize = 8;

pub const DEFAULT_MEDIA_LANES: usize = 2;

pub fn active_media_lanes() -> usize {
    crate::transfer_policy::current().media_lanes()
}

/// Runtime knob for the "Faster downloads" setting; applies to the next acquisition.
pub fn set_active_media_lanes(lanes: usize) {
    let lanes = lanes.clamp(1, MAX_MEDIA_LANES);
    crate::transfer_policy::default_policy().assign_media_lanes(lanes);
    crate::client::visit_policies(|policy| policy.follow_media_lanes(lanes));
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
    clock: Arc<dyn SchedulerClock>,
}

pub trait SchedulerClock: Send + Sync {
    fn now_ms(&self) -> u64;
}

pub struct SystemSchedulerClock {
    origin: std::time::Instant,
}

impl Default for SystemSchedulerClock {
    fn default() -> Self {
        Self {
            origin: std::time::Instant::now(),
        }
    }
}

impl SchedulerClock for SystemSchedulerClock {
    fn now_ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }
}

pub(crate) const MAX_LANE_WAITERS: usize = 256;

const AGING_INTERVAL_MS: u64 = 5_000;

#[derive(Default)]
struct GateState {
    busy: bool,
    waiters: VecDeque<Waiter>,
    next_ticket: u64,
}

struct Waiter {
    ticket: u64,
    priority: u8,
    queued_at: u64,
}

impl Waiter {
    fn order_at(&self, now: u64) -> (u8, u64) {
        let promotion = (now.saturating_sub(self.queued_at) / AGING_INTERVAL_MS).min(4) as u8;
        let priority = if self.priority > 2 { self.priority.saturating_sub(promotion).max(1) } else { self.priority };
        (priority, self.ticket)
    }
}

/// Holds a lane; releases it (and wakes the best waiter) on drop.
pub struct LaneGuard<'a> {
    gate: &'a LaneGate,
}

pub(crate) struct LaneWaiter<'a> {
    gate: &'a LaneGate,
    ticket: u64,
}

impl<'a> LaneWaiter<'a> {
    pub(crate) fn try_acquire(&self) -> Option<LaneGuard<'a>> {
        let mut state = self.gate.state.lock();
        let now = self.gate.clock.now_ms();
        let best = state.waiters.iter().min_by_key(|waiter| waiter.order_at(now));
        if state.busy || !best.is_some_and(|waiter| waiter.ticket == self.ticket) {
            return None;
        }
        state.busy = true;
        state.waiters.retain(|waiter| waiter.ticket != self.ticket);
        Some(LaneGuard { gate: self.gate })
    }

    pub(crate) fn is_next(&self) -> bool {
        self.gate
            .state
            .lock()
            .waiters
            .iter()
            .min_by_key(|waiter| waiter.order_at(self.gate.clock.now_ms()))
            .is_some_and(|waiter| waiter.ticket == self.ticket)
    }

    pub(crate) fn promote(&self, class: RequestClass) {
        self.gate.promote(self.ticket, class);
    }

    pub(crate) fn wait(&self) {
        self.gate
            .wake
            .wait_for(&mut self.gate.state.lock(), Duration::from_millis(5));
    }
}

impl Drop for LaneWaiter<'_> {
    fn drop(&mut self) {
        self.gate
            .state
            .lock()
            .waiters
            .retain(|waiter| waiter.ticket != self.ticket);
        self.gate.wake.notify_all();
    }
}

impl Default for LaneGate {
    fn default() -> Self {
        Self {
            state: Mutex::new(GateState::default()),
            wake: Condvar::new(),
            clock: Arc::new(SystemSchedulerClock::default()),
        }
    }
}

impl LaneGate {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_clock(clock: Arc<dyn SchedulerClock>) -> Self {
        Self {
            state: Mutex::new(GateState::default()),
            wake: Condvar::new(),
            clock,
        }
    }

    pub(crate) fn promote(&self, ticket: u64, class: RequestClass) {
        let mut state = self.state.lock();
        if let Some(waiter) = state.waiters.iter_mut().find(|w| w.ticket == ticket) {
            waiter.priority = waiter.priority.min(class.priority());
        }
        drop(state);
        self.wake.notify_all();
    }

    pub(crate) fn enqueue(&self, class: RequestClass) -> Result<LaneWaiter<'_>, MtprotoError> {
        let mut state = self.state.lock();
        if state.waiters.len() >= MAX_LANE_WAITERS {
            return Err(MtprotoError::Message("request queue capacity exceeded".into()));
        }
        let ticket = state.next_ticket;
        state.next_ticket = ticket.checked_add(1).ok_or_else(|| MtprotoError::Message("request queue ticket exhausted".into()))?;
        state.waiters.push_back(Waiter {
            ticket,
            priority: class.priority(),
            queued_at: self.clock.now_ms(),
        });
        self.wake.notify_all();
        Ok(LaneWaiter { gate: self, ticket })
    }

    #[cfg(test)]
    pub(crate) fn waiter_count(&self) -> usize {
        self.state.lock().waiters.len()
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
                .any(|waiter| waiter.order_at(self.clock.now_ms()).0 <= priority)
        {
            return None;
        }
        state.busy = true;
        Some(LaneGuard { gate: self })
    }

    /// Blocks until this request's class holds the lane.
    pub fn acquire(&self, class: RequestClass) -> Result<LaneGuard<'_>, MtprotoError> {
        let waiter = self.enqueue(class)?;
        loop {
            crate::request_control::check().map_err(|e| MtprotoError::Message(e.to_string()))?;
            if let Some(promoted) = crate::request_control::promoted_class() {
                waiter.promote(promoted);
            }
            if let Some(guard) = waiter.try_acquire() {
                return Ok(guard);
            }
            waiter.wait();
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
