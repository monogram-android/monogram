use super::*;

#[test]
fn expands_compact_payload_into_jpeg() {
    let stripped = [1u8, 40, 30, 0xAA, 0xBB, 0xCC];
    let jpeg = expand_stripped_jpeg(&stripped).expect("jpeg");
    assert_eq!(&jpeg[..2], &[0xFF, 0xD8]);
    assert_eq!(&jpeg[jpeg.len() - 2..], &[0xFF, 0xD9]);
    assert_eq!(jpeg[164], 40);
    assert_eq!(jpeg[166], 30);
    assert_eq!(&jpeg[HEADER.len()..HEADER.len() + 3], &[0xAA, 0xBB, 0xCC]);
    assert_eq!(jpeg.len(), HEADER.len() + 3 + FOOTER.len());
}

#[test]
fn rejects_too_short() {
    assert!(expand_stripped_jpeg(&[1, 2]).is_none());
    assert!(expand_stripped_jpeg(&[]).is_none());
}

#[test]
fn keeps_existing_jpeg() {
    let raw = [0xFF, 0xD8, 0xFF, 0xD9];
    assert_eq!(expand_stripped_jpeg(&raw).as_deref(), Some(raw.as_slice()));
}
