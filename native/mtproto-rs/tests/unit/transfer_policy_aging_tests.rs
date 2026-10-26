use super::*;

#[test]
fn aging_preserves_fifo_and_never_overtakes_writes() {
    let start = std::time::Instant::now();
    let waiter = DownloadWaiter {
        ticket: 7,
        priority: 4,
        dc: 2,
        large: false,
        queued_at: start,
    };
    assert_eq!(
        waiter.order_at(start + std::time::Duration::from_secs(4)),
        (4, 7)
    );
    assert_eq!(
        waiter.order_at(start + std::time::Duration::from_secs(5)),
        (3, 7)
    );
    assert_eq!(
        waiter.order_at(start + std::time::Duration::from_secs(100)),
        (1, 7)
    );
}
