use super::{InboundEvent, parse_service_or_result};

#[test]
fn bare_future_salts_do_not_fail_the_rpc() {
    let mut body = Vec::new();
    body.extend_from_slice(&0xae50_0895u32.to_le_bytes());
    body.extend_from_slice(&7i64.to_le_bytes());
    body.extend_from_slice(&1_000i32.to_le_bytes());
    body.extend_from_slice(&1u32.to_le_bytes());
    body.extend_from_slice(&900i32.to_le_bytes());
    body.extend_from_slice(&1_900i32.to_le_bytes());
    body.extend_from_slice(&99i64.to_le_bytes());
    let events = parse_service_or_result(&body).expect("future salts");
    let InboundEvent::FutureSalts(windows) = &events[0] else {
        panic!("bare future_salts was not accepted");
    };
    assert_eq!(windows[0].salt, 99);
    assert_eq!(windows[0].valid_since, 900);
    assert_eq!(windows[0].valid_until, 1_900);

    let mut boxed = Vec::new();
    boxed.extend_from_slice(&0xae50_0895u32.to_le_bytes());
    boxed.extend_from_slice(&0i64.to_le_bytes());
    boxed.extend_from_slice(&0i32.to_le_bytes());
    boxed.extend_from_slice(&0x1cb5_c415u32.to_le_bytes());
    let events = parse_service_or_result(&boxed).expect("malformed salts stay non-fatal");
    assert!(matches!(events[0], InboundEvent::Ignored));
}
