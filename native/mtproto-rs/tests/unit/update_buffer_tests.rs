use super::*;
#[test]
fn missing_packet_closes_gap_without_difference() {
    let mut buffer = PushBuffer::default();
    let now = Instant::now();
    let mut cursor = 0;
    buffer.append(vec![vec![2]]);
    let mut apply = |body: &[u8]| -> Result<bool, ()> {
        if body[0] > cursor + 1 {
            return Ok(false);
        }
        cursor = cursor.max(body[0]);
        Ok(true)
    };
    assert!(!buffer.resolve(now, &mut apply).unwrap());
    buffer.append(vec![vec![1]]);
    assert!(
        !buffer
            .resolve(now + Duration::from_millis(400), &mut apply)
            .unwrap()
    );
    assert!(buffer.is_empty());
    assert_eq!(cursor, 2);
}

#[test]
fn unresolved_gap_expires_once_without_extending_on_more_packets() {
    let mut buffer = PushBuffer::default();
    let now = Instant::now();
    buffer.append(vec![vec![2]]);
    assert!(!buffer.resolve(now, |_| Ok::<_, ()>(false)).unwrap());
    buffer.append(vec![vec![3]]);
    assert!(
        buffer
            .resolve(now + Duration::from_millis(500), |_| Ok::<_, ()>(false))
            .unwrap()
    );
    assert!(buffer.is_empty());
    assert!(
        !buffer
            .resolve(now + Duration::from_secs(1), |_| Ok::<_, ()>(false))
            .unwrap()
    );
}

#[test]
fn queue_overflow_requires_recovery_without_applying_partial_queue() {
    let mut buffer = PushBuffer::default();
    buffer.append(vec![vec![0]; 257]);
    assert!(
        buffer
            .resolve(Instant::now(), |_| -> Result<bool, ()> {
                panic!("overflow applied")
            })
            .unwrap()
    );
    assert!(buffer.is_empty());
}
