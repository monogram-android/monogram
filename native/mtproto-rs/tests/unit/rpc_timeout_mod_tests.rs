use super::*;

#[test]
fn extend_streaming_deadlines_moves_forward() {
    let now = std::time::Instant::now();
    let mut attempt = now;
    let mut overall = now;
    let mut hard = now;
    extend_streaming_deadlines(&mut attempt, &mut overall, &mut hard);
    assert!(overall > now);
    assert!(hard > overall);
    assert!(attempt > now);
}
