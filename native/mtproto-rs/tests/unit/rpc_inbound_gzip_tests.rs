use super::{GZIP_PACKED, gzip_if_smaller, ungzip_if_needed};

#[test]
fn repetitive_api_body_is_gzip_packed() {
    let body = vec![b'a'; 2048];
    let packed = gzip_if_smaller(&body);
    assert_eq!(
        u32::from_le_bytes(packed[0..4].try_into().unwrap()),
        GZIP_PACKED
    );
    assert_eq!(ungzip_if_needed(&packed).unwrap(), body);
}

#[test]
fn tiny_body_stays_plain() {
    let body = vec![1, 2, 3, 4];
    assert_eq!(gzip_if_smaller(&body), body);
}
