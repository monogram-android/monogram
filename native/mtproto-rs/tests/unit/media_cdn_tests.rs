use super::*;

#[test]
fn decrypt_is_self_inverse() {
    let key = [7u8; 32];
    let iv = [9u8; 32];
    let mut data = b"cdn-part-fixture!!!!".to_vec();
    let original = data.clone();
    decrypt_cdn_part(&key, &iv, 0, &mut data).unwrap();
    assert_ne!(data, original);
    decrypt_cdn_part(&key, &iv, 0, &mut data).unwrap();
    assert_eq!(data, original);
}
