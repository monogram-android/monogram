use super::*;

fn gzip_body(bytes: &[u8]) -> Vec<u8> {
    use std::io::Write;
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
    encoder.write_all(bytes).unwrap();
    encode_boxed_bytes(&GzipPackedConstructor {
        packed_data: encoder.finish().unwrap(),
    })
    .unwrap()
}

#[test]
fn compressed_payloads_are_bounded_and_validate_checksum() {
    let normal = gzip_body(b"bounded payload");
    assert_eq!(ungzip_if_needed(&normal).unwrap(), b"bounded payload");
    let bomb = gzip_body(&vec![0; MAX_UNPACKED_BYTES + 1]);
    assert!(ungzip_if_needed(&bomb).is_err());
    let mut broken = normal;
    broken[8] ^= 0xff;
    assert!(ungzip_if_needed(&broken).is_err());
}

#[test]
fn nested_wrappers_and_short_container_entries_cannot_panic() {
    let mut body = MSGS_ACK.to_le_bytes().to_vec();
    for _ in 0..=MAX_WRAPPER_DEPTH {
        body = gzip_body(&body);
    }
    assert!(parse_service_or_result(&body).is_err());
    let mut container = MSG_CONTAINER.to_le_bytes().to_vec();
    container.extend_from_slice(&1_i32.to_le_bytes());
    container.extend(pack_container_message(3, 1, &[0; 4]));
    assert!(parse_service_or_result(&container).is_err());
}

fn pack_container_message(msg_id: i64, seqno: i32, inner: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&msg_id.to_le_bytes());
    out.extend_from_slice(&seqno.to_le_bytes());
    out.extend_from_slice(&(inner.len() as i32).to_le_bytes());
    out.extend_from_slice(inner);
    out
}
