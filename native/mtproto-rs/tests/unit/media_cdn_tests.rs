use super::*;

#[test]
fn cdn_hash_rejects_truncated_hash_ranges() {
    use tellers_mtproto::latest::api::FileHashConstructor;
    let data = b"short";
    let hash = FileHash::FileHash(FileHashConstructor {
        offset: 0,
        limit: 128,
        hash: Sha256::digest(data).to_vec(),
    });
    assert!(!hash_matches(&hash, 0, data));
}

#[test]
fn cdn_part_requires_complete_matching_hash_coverage() {
    use tellers_mtproto::latest::api::FileHashConstructor;
    let hashes: Vec<_> = [b"abcd", b"efgh"]
        .iter()
        .enumerate()
        .map(|(index, bytes)| {
            FileHash::FileHash(FileHashConstructor {
                offset: (index * 4) as i64,
                limit: 4,
                hash: Sha256::digest(bytes).to_vec(),
            })
        })
        .collect();
    assert!(verify_cdn_part(&hashes, 0, b"abcdefgh"));
    assert!(!verify_cdn_part(&hashes[..1], 0, b"abcdefgh"));
    assert!(!verify_cdn_part(&hashes, 0, b"abcdEfgh"));
    assert!(!verify_cdn_part(&hashes, 0, b"abcdefg"));
    assert!(!verify_cdn_part(&hashes, 1, b"abcdefgh"));
    assert!(!verify_cdn_part(&hashes, 0, b""));
}

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
