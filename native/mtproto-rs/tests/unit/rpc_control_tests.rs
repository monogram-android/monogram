use super::*;

fn frame(payload: &[u8]) -> Vec<u8> {
    PaddedIntermediate::default().encode_with_padding(payload, &[]).unwrap()
}

#[test]
fn quick_ack_preserves_buffered_authenticated_frame_and_missing_key_is_not_revocation() {
    let ack = [vec![0xff; 4], 7_u32.to_le_bytes().to_vec()].concat();
    let payload = vec![0xab; 32];
    let mut input = [frame(&ack), frame(&payload)].concat();
    assert_eq!(try_decode_complete(&mut PaddedIntermediate::default(), &mut input).unwrap(), Some(payload));
    assert!(input.is_empty());
    let mut missing = frame(&(-404_i32).to_le_bytes());
    let error = try_decode_complete(&mut PaddedIntermediate::default(), &mut missing).unwrap_err();
    assert_eq!(error.to_string(), "MTProto transport error -404");
    assert_ne!(crate::rpc::supervisor::failure_class(&error), crate::rpc::supervisor::FailureClass::Authorization);
}

#[test]
fn quick_ack_flood_is_bounded() {
    let ack = [vec![0xff; 4], 7_u32.to_le_bytes().to_vec()].concat();
    let mut input = frame(&ack).repeat(256);
    let error = try_decode_complete(&mut PaddedIntermediate::default(), &mut input).unwrap_err();
    assert!(error.to_string().contains("control frame limit"));
    assert!(input.is_empty());
}
