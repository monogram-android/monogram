use super::*;
use crate::peers::{CachedPeer, PeerKind};
use tellers_mtproto::codec::{Decoder, Encoder, Limits, TlDecode, TlEncode};

const MENTION: &str = r#"[{"kind":"mention_name","offset":3,"length":5,"url":"42"}]"#;

fn cache() -> crate::HashMap<i64, CachedPeer> {
    [(
        42,
        CachedPeer {
            kind: PeerKind::User,
            id: 42,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect()
}

fn encoded(value: &impl TlEncode) -> Vec<u8> {
    let mut encoder = Encoder::new();
    value.encode(&mut encoder).unwrap();
    encoder.into_bytes()
}

#[test]
fn input_mentions_encode_input_user_and_preserve_utf16_offsets() {
    let (_, entities) = input_entities_from_json(Some(MENTION), &cache()).unwrap();
    let values = crate::peers::vector_items(entities.as_ref().unwrap());
    let bytes = encoded(&values[0]);
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(
        decoder.read_u32().unwrap(),
        tl::InputMessageEntityMentionNameConstructor::ID
    );
    let input = tl::InputMessageEntityMentionNameConstructor::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    assert_eq!((input.offset, input.length), (3, 5));
    let tl::InputUser::InputUser(user) = *input.user_id else {
        panic!("full input user required")
    };
    assert_eq!((user.user_id, user.access_hash), (42, 99));
    let (_, output) = entities_from_json(Some(MENTION)).unwrap();
    assert!(matches!(
        crate::peers::vector_items(output.as_ref().unwrap())[0].as_ref(),
        MessageEntity::MessageEntityMentionName(_)
    ));
}

#[test]
fn unresolved_min_zero_hash_nonuser_and_mismatched_mentions_fail_closed() {
    assert!(input_entities_from_json(Some(MENTION), &crate::HashMap::default()).is_err());
    for (kind, id, access_hash, min_hash) in [
        (PeerKind::User, 42, 99, true),
        (PeerKind::User, 42, 0, false),
        (PeerKind::Chat, 42, 0, false),
        (PeerKind::Channel, 42, 99, false),
        (PeerKind::User, 43, 99, false),
    ] {
        let peers = [(
            42,
            CachedPeer {
                kind,
                id,
                access_hash,
                min_hash,
            },
        )]
        .into_iter()
        .collect();
        assert!(input_entities_from_json(Some(MENTION), &peers).is_err());
    }
}

#[test]
fn other_entities_and_empty_payloads_do_not_require_peer_cache() {
    let json = r#"[{"kind":"bold","offset":0,"length":2},{"kind":"blockquote","offset":3,"length":5,"url":"collapsed"}]"#;
    let (_, input) = input_entities_from_json(Some(json), &crate::HashMap::default()).unwrap();
    let (_, output) = entities_from_json(Some(json)).unwrap();
    assert_eq!(
        encoded(input.as_ref().unwrap()),
        encoded(output.as_ref().unwrap())
    );
    assert!(
        input_entities_from_json(None, &cache())
            .unwrap()
            .1
            .is_none()
    );
}
