use super::*;
use std::sync::mpsc;

#[test]
fn lane_queue_capacity_is_bounded_and_cancelled_admission_releases_capacity() {
    let gate = LaneGate::new();
    let held = gate.acquire(RequestClass::InteractiveRead).unwrap();
    let mut waiters: Vec<_> = (0..MAX_LANE_WAITERS)
        .map(|_| gate.enqueue(RequestClass::BackgroundRead).unwrap()).collect();
    assert_eq!(gate.waiter_count(), MAX_LANE_WAITERS);
    assert!(gate.enqueue(RequestClass::InteractiveWrite).is_err());
    assert!(gate.acquire(RequestClass::InteractiveWrite).is_err());
    assert_eq!(gate.waiter_count(), MAX_LANE_WAITERS);
    drop(waiters.pop());
    let request = crate::request_control::create();
    let previous = crate::request_control::bind(request);
    crate::request_control::cancel(request);
    assert!(gate.acquire(RequestClass::InteractiveRead).is_err());
    crate::request_control::bind(previous);
    crate::request_control::release(request);
    assert_eq!(gate.waiter_count(), MAX_LANE_WAITERS - 1);
    drop(waiters);
    drop(held);
    assert_eq!(gate.waiter_count(), 0);
    assert!(gate.try_acquire(RequestClass::InteractiveRead).is_some());
}

#[test]
fn background_aging_never_overtakes_writes() {
    let waiter = Waiter { ticket: 0, priority: 4, queued_at: 0 };
    assert_eq!(waiter.order_at(0).0, 4);
    assert_eq!(waiter.order_at(5_000).0, 3);
    assert_eq!(waiter.order_at(60_000).0, 1);
    let write = Waiter { ticket: 1, priority: 0, queued_at: 0 };
    assert_eq!(write.order_at(60_000).0, 0);
}

struct ManualClock(std::sync::atomic::AtomicU64);

impl ManualClock {
    fn advance(&self, ms: u64) {
        self.0.fetch_add(ms, std::sync::atomic::Ordering::SeqCst);
    }
}

impl SchedulerClock for ManualClock {
    fn now_ms(&self) -> u64 {
        self.0.load(std::sync::atomic::Ordering::SeqCst)
    }
}

#[test]
fn injected_clock_ages_background_work_past_a_later_interactive_read_without_sleeping() {
    let clock = Arc::new(ManualClock(std::sync::atomic::AtomicU64::new(0)));
    let gate = LaneGate::with_clock(clock.clone());
    let background = gate.enqueue(RequestClass::BackgroundMedia).unwrap();
    clock.advance(4_999);
    let read = gate.enqueue(RequestClass::InteractiveRead).unwrap();
    assert!(background.try_acquire().is_none(), "a fresh interactive read still wins");
    clock.advance(20_000);
    let read_late = gate.enqueue(RequestClass::InteractiveRead).unwrap();
    assert!(read_late.try_acquire().is_none(), "ticket order decides equal effective priority");
    drop(read);
    let lease = background.try_acquire().expect("aged background request is next");
    drop(lease);
    assert!(read_late.try_acquire().is_some());
}

#[test]
fn aging_stops_below_interactive_write_priority() {
    let clock = Arc::new(ManualClock(std::sync::atomic::AtomicU64::new(0)));
    let gate = LaneGate::with_clock(clock.clone());
    let background = gate.enqueue(RequestClass::BackgroundMedia).unwrap();
    clock.advance(10 * 60_000);
    let write = gate.enqueue(RequestClass::InteractiveWrite).unwrap();
    assert!(background.try_acquire().is_none());
    assert!(write.try_acquire().is_some());
}

#[test]
fn promotion_raises_a_queued_ticket_and_never_lowers_it() {
    let gate = LaneGate::new();
    let read = gate.enqueue(RequestClass::InteractiveRead).unwrap();
    let media = gate.enqueue(RequestClass::BackgroundMedia).unwrap();
    media.promote(RequestClass::InteractiveWrite);
    assert!(read.try_acquire().is_none());
    assert!(media.try_acquire().is_some());
    let other = LaneGate::new();
    let first = other.enqueue(RequestClass::InteractiveRead).unwrap();
    let second = other.enqueue(RequestClass::InteractiveRead).unwrap();
    second.promote(RequestClass::BackgroundMedia);
    assert!(first.try_acquire().is_some(), "promotion to a lower class changes nothing");
    drop(second);
}

#[test]
fn a_freed_sibling_lane_is_taken_without_waiting_for_the_first_lane() {
    let first = LaneGate::new();
    let second = LaneGate::new();
    let busy = first.acquire(RequestClass::InteractiveRead).unwrap();
    let waiters = [
        first.enqueue(RequestClass::InteractiveMedia).unwrap(),
        second.enqueue(RequestClass::InteractiveMedia).unwrap(),
    ];
    let acquired = waiters.iter().find_map(|waiter| waiter.try_acquire());
    assert!(acquired.is_some(), "the free lane serves the request while another is busy");
    drop(busy);
}

#[test]
fn cancelling_a_family_request_releases_every_reservation() {
    let first = LaneGate::new();
    let second = LaneGate::new();
    let waiters = [
        first.enqueue(RequestClass::BackgroundMedia).unwrap(),
        second.enqueue(RequestClass::BackgroundMedia).unwrap(),
    ];
    assert_eq!(first.waiter_count() + second.waiter_count(), 2);
    drop(waiters);
    assert_eq!(first.waiter_count() + second.waiter_count(), 0);
    assert!(first.try_acquire(RequestClass::BackgroundMedia).is_some());
}

#[test]
fn fast_admission_cannot_pass_an_equal_priority_waiter() {
    let gate = LaneGate::new();
    let waiter = gate.enqueue(RequestClass::InteractiveRead).unwrap();
    assert!(gate.try_acquire(RequestClass::InteractiveRead).is_none());
    let lease = waiter.try_acquire().expect("queued owner acquires the lane");
    assert!(gate.try_acquire(RequestClass::InteractiveRead).is_none());
    drop(lease);
    drop(waiter);
    assert!(gate.try_acquire(RequestClass::InteractiveRead).is_some());
}

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
    assert!(RequestClass::InteractiveWrite.priority() < RequestClass::InteractiveRead.priority());
    assert!(RequestClass::InteractiveRead.priority() < RequestClass::BackgroundRead.priority());
    assert!(RequestClass::InteractiveMedia.priority() < RequestClass::BackgroundMedia.priority());
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
    let _serial = ALLOWANCE_TEST_LOCK.lock().unwrap_or_else(|error| error.into_inner());
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
    let _policy = crate::transfer_policy::bind(std::sync::Arc::new(crate::transfer_policy::TransferPolicy::stock()));
    assert!(!main_session_allowance_known());
    assert_eq!(main_session_allowance(), 1);
    assert_eq!(extra_main_sessions(), 0);
    assert_eq!(read_lanes(), 0);
}

#[test]
fn tmp_sessions_bounds_extra_main_sessions() {
    let _serial = ALLOWANCE_TEST_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let _policy = crate::transfer_policy::bind(std::sync::Arc::new(crate::transfer_policy::TransferPolicy::stock()));
    set_bound_extra_sessions(0);
    // Absent `tmp_sessions` means one main session: no parallel home-DC lanes.
    assert_eq!(set_main_session_allowance(None), 1);
    assert!(main_session_allowance_known());
    assert_eq!(extra_main_sessions(), 0);
    assert_eq!(read_lanes(), 0);
    assert_eq!(set_main_session_allowance(Some(0)), 1);
    assert_eq!(set_main_session_allowance(Some(99)), MAX_MAIN_SESSIONS);
    assert_eq!(set_main_session_allowance(Some(3)), 3);
    assert_eq!(extra_main_sessions(), 0);
    assert_eq!(read_lanes(), 0);
    assert_eq!(set_main_session_allowance(Some(2)), 2);
    assert_eq!(read_lanes(), 0);
    set_bound_extra_sessions(2);
    assert_eq!(extra_main_sessions(), 1);
    assert_eq!(read_lanes(), 1);
    set_main_session_allowance(Some(8));
    set_bound_extra_sessions(2);
    assert_eq!(extra_main_sessions(), 2);
    set_bound_extra_sessions(0);
    assert_eq!(extra_main_sessions(), 0);
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
fn visible_media_precedes_queued_background_reads() {
    let gate = std::sync::Arc::new(LaneGate::new());
    let held = gate.acquire(RequestClass::InteractiveRead).unwrap();
    let (tx, rx) = mpsc::channel();
    let background = {
        let gate = gate.clone();
        let tx = tx.clone();
        std::thread::spawn(move || {
            let _guard = gate.acquire(RequestClass::BackgroundRead).unwrap();
            tx.send("background").unwrap();
        })
    };
    let media = {
        let gate = gate.clone();
        std::thread::spawn(move || {
            let _guard = gate.acquire(RequestClass::InteractiveMedia).unwrap();
            tx.send("media").unwrap();
        })
    };
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while gate.state.lock().waiters.len() != 2 {
        assert!(
            std::time::Instant::now() < deadline,
            "waiters did not register"
        );
        std::thread::yield_now();
    }
    drop(held);
    let first = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    let second = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    background.join().unwrap();
    media.join().unwrap();
    assert_eq!((first, second), ("media", "background"));
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
