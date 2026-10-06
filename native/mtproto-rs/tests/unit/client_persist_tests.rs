use super::*;

#[test]
fn generation_lookup_does_not_wait_for_disk_write() {
    let write = PERSIST_WRITE.lock();
    let (tx, rx) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let clock = persist_generation(Path::new("generation-lookup-test"));
        tx.send(clock.load(Ordering::Acquire)).unwrap();
    });
    let result = rx.recv_timeout(std::time::Duration::from_millis(500));
    drop(write);
    worker.join().unwrap();
    assert_eq!(result.ok(), Some(0));
}

#[test]
fn generations_are_per_file_and_visible_to_existing_captures() {
    let first = persist_generation(Path::new("generation-first-test"));
    let same = persist_generation(Path::new("generation-first-test"));
    let other = persist_generation(Path::new("generation-other-test"));
    let previous = first.load(Ordering::Acquire);
    let unrelated = other.load(Ordering::Acquire);
    bump_persist_clock(&first);
    assert_eq!(same.load(Ordering::Acquire), previous + 1);
    assert_eq!(other.load(Ordering::Acquire), unrelated);
    assert!(captured_session_is_stale(
        previous,
        same.load(Ordering::Acquire),
        1,
        1
    ));
}
