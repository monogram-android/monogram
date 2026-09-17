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
            RequestClass::BackgroundRead => 2,
            RequestClass::InteractiveMedia => 3,
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

/// Main sessions the client may open beside the single home session.
pub fn extra_main_sessions() -> usize {
    (main_session_allowance() - 1) as usize
}

/// Home-DC read lanes the current allowance pays for.
pub fn read_lanes() -> usize {
    READ_LANES.min(extra_main_sessions())
}

/// Lanes are allocated up front; only `active_media_lanes()` are used.
pub const MAX_MEDIA_LANES: usize = 8;

/// Measured peak (`docs/netcode-perf-report.md` §9.2); 8 lanes regressed.
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
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn cancelled_waiter_does_not_block_following_requests() {
        let gate = LaneGate::new();
        let held = gate.acquire(RequestClass::InteractiveRead).unwrap();
        let request = crate::request_control::create();
        let previous = crate::request_control::bind(request);
        crate::request_control::cancel(request);
        let result = gate.acquire(RequestClass::InteractiveWrite);
        crate::request_control::bind(previous);
        crate::request_control::release(request);
        assert!(result.is_err());
        assert!(gate.state.lock().waiters.is_empty());
        drop(held);
        assert!(gate.try_acquire(RequestClass::BackgroundMedia).is_some());
    }

    #[test]
    fn reads_never_use_the_media_lanes_and_media_never_uses_read_lanes() {
        assert_eq!(family(RequestClass::InteractiveWrite), LaneFamily::Read);
        assert_eq!(family(RequestClass::InteractiveRead), LaneFamily::Read);
        assert_eq!(family(RequestClass::BackgroundRead), LaneFamily::Read);
        assert_eq!(family(RequestClass::InteractiveMedia), LaneFamily::Media);
        assert_eq!(family(RequestClass::BackgroundMedia), LaneFamily::Media);
    }

    #[test]
    fn interactive_classes_outrank_background_classes() {
        assert!(
            RequestClass::InteractiveWrite.priority() < RequestClass::InteractiveRead.priority()
        );
        assert!(RequestClass::InteractiveRead.priority() < RequestClass::BackgroundRead.priority());
        assert!(
            RequestClass::InteractiveMedia.priority() < RequestClass::BackgroundMedia.priority()
        );
    }

    #[test]
    fn dispatch_class_round_trips_through_the_thread_local() {
        for class in [
            RequestClass::InteractiveRead,
            RequestClass::BackgroundRead,
            RequestClass::InteractiveMedia,
            RequestClass::BackgroundMedia,
            RequestClass::InteractiveWrite,
        ] {
            set_current_class(class);
            assert_eq!(current_class(), class);
        }
        set_current_class(RequestClass::InteractiveRead);
    }

    #[test]
    fn lane_orders_cover_every_lane_exactly_once() {
        let reads: Vec<usize> = read_lane_order().collect();
        assert_eq!(reads.len(), read_lanes());
        let mut sorted = reads.clone();
        sorted.sort_unstable();
        sorted.dedup();
        assert_eq!(sorted.len(), read_lanes());
        assert_eq!(media_lane_order().count(), active_media_lanes());
    }

    #[test]
    fn unknown_allowance_keeps_a_single_main_session() {
        let _serial = ALLOWANCE_TEST_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let original = MAIN_SESSION_ALLOWANCE.load(std::sync::atomic::Ordering::Relaxed);
        MAIN_SESSION_ALLOWANCE.store(-1, std::sync::atomic::Ordering::Relaxed);
        assert!(!main_session_allowance_known());
        assert_eq!(main_session_allowance(), 1);
        assert_eq!(extra_main_sessions(), 0);
        assert_eq!(read_lanes(), 0);
        MAIN_SESSION_ALLOWANCE.store(original, std::sync::atomic::Ordering::Relaxed);
    }

    #[test]
    fn tmp_sessions_bounds_extra_main_sessions() {
        let _serial = ALLOWANCE_TEST_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let original = MAIN_SESSION_ALLOWANCE.load(std::sync::atomic::Ordering::Relaxed);
        // Absent `tmp_sessions` means one main session: no parallel home-DC lanes.
        assert_eq!(set_main_session_allowance(None), 1);
        assert!(main_session_allowance_known());
        assert_eq!(extra_main_sessions(), 0);
        assert_eq!(read_lanes(), 0);
        assert_eq!(set_main_session_allowance(Some(0)), 1);
        assert_eq!(set_main_session_allowance(Some(99)), MAX_MAIN_SESSIONS);
        assert_eq!(set_main_session_allowance(Some(3)), 3);
        assert_eq!(extra_main_sessions(), 2);
        assert_eq!(read_lanes(), READ_LANES);
        assert_eq!(set_main_session_allowance(Some(2)), 2);
        assert_eq!(read_lanes(), 1);
        MAIN_SESSION_ALLOWANCE.store(original, std::sync::atomic::Ordering::Relaxed);
    }

    #[test]
    fn active_media_lanes_is_clamped_and_restored() {
        let original = active_media_lanes();
        set_active_media_lanes(0);
        assert_eq!(
            active_media_lanes(),
            1,
            "at least one lane must stay usable"
        );
        set_active_media_lanes(999);
        assert_eq!(active_media_lanes(), MAX_MEDIA_LANES);
        set_active_media_lanes(3);
        assert_eq!(media_lane_order().count(), 3);
        set_active_media_lanes(original);
    }

    #[test]
    fn with_class_scopes_and_restores_the_thread_class() {
        set_current_class(RequestClass::InteractiveRead);
        assert_eq!(
            with_class(RequestClass::InteractiveMedia, current_class),
            RequestClass::InteractiveMedia
        );
        assert_eq!(current_class(), RequestClass::InteractiveRead);
        let nested = with_class(RequestClass::BackgroundRead, || {
            with_class(RequestClass::InteractiveMedia, current_class)
        });
        assert_eq!(nested, RequestClass::InteractiveMedia);
        assert_eq!(current_class(), RequestClass::InteractiveRead);
    }

    #[test]
    fn gate_admits_the_highest_priority_waiter_first() {
        let gate = std::sync::Arc::new(LaneGate::new());
        let held = gate
            .acquire(RequestClass::InteractiveRead)
            .expect("hold the lane");
        let (done_tx, done_rx) = mpsc::channel::<&'static str>();

        // Background asks first, interactive second: interactive must still win.
        let background = {
            let gate = gate.clone();
            let done_tx = done_tx.clone();
            std::thread::spawn(move || {
                let _guard = gate
                    .acquire(RequestClass::BackgroundRead)
                    .expect("background");
                let _ = done_tx.send("background");
            })
        };
        std::thread::sleep(Duration::from_millis(60));
        let interactive = {
            let gate = gate.clone();
            let done_tx = done_tx.clone();
            std::thread::spawn(move || {
                let _guard = gate
                    .acquire(RequestClass::InteractiveRead)
                    .expect("interactive");
                let _ = done_tx.send("interactive");
            })
        };
        std::thread::sleep(Duration::from_millis(60));
        drop(held);
        let first = done_rx
            .recv_timeout(Duration::from_secs(5))
            .expect("first admission");
        let second = done_rx
            .recv_timeout(Duration::from_secs(5))
            .expect("second admission");
        interactive.join().expect("interactive thread");
        background.join().expect("background thread");
        assert_eq!(
            first, "interactive",
            "background work was admitted before an interactive read"
        );
        assert_eq!(second, "background");
    }

    #[test]
    fn a_higher_class_waiter_never_preempts_the_running_work() {
        let gate = std::sync::Arc::new(LaneGate::new());
        let held = gate
            .acquire(RequestClass::BackgroundMedia)
            .expect("hold the lane");
        let (done_tx, done_rx) = mpsc::channel::<&'static str>();
        let interactive = {
            let gate = gate.clone();
            std::thread::spawn(move || {
                let _guard = gate
                    .acquire(RequestClass::InteractiveMedia)
                    .expect("interactive media");
                let _ = done_tx.send("interactive");
            })
        };
        assert!(
            done_rx.recv_timeout(Duration::from_millis(150)).is_err(),
            "a higher class preempted work that was already running"
        );
        drop(held);
        assert_eq!(
            done_rx
                .recv_timeout(Duration::from_secs(5))
                .expect("admission after release"),
            "interactive"
        );
        interactive.join().expect("interactive thread");
    }

    #[test]
    fn gate_is_fifo_inside_one_class() {
        let gate = std::sync::Arc::new(LaneGate::new());
        let held = gate
            .acquire(RequestClass::InteractiveRead)
            .expect("hold the lane");
        let (order_tx, order_rx) = mpsc::channel::<u8>();
        let mut threads = Vec::new();
        for index in 0..3_u8 {
            let gate = gate.clone();
            let order_tx = order_tx.clone();
            threads.push(std::thread::spawn(move || {
                let _guard = gate.acquire(RequestClass::InteractiveRead).expect("waiter");
                let _ = order_tx.send(index);
            }));
            std::thread::sleep(Duration::from_millis(40));
        }
        drop(held);
        let order: Vec<u8> = (0..3)
            .map(|_| {
                order_rx
                    .recv_timeout(Duration::from_secs(5))
                    .expect("admission")
            })
            .collect();
        for thread in threads {
            thread.join().expect("waiter thread");
        }
        assert_eq!(
            order,
            vec![0, 1, 2],
            "same-class waiters must keep FIFO order"
        );
    }
}
