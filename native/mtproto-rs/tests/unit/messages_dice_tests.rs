use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn dice_wire_identity_options_and_authoritative_payload_contract() {
    let peers = [(
        7,
        CachedPeer {
            kind: crate::peers::PeerKind::User,
            id: 7,
            access_hash: 99,
            min_hash: false,
        },
    )]
    .into_iter()
    .collect();
    let options = SendOptions {
        silent: true,
        no_webpage: false,
        schedule_date: Some(0x7fff_fffe),
    };
    for emoji in ["🎲", "🎯", "🏀", "⚽", "🎳", "🎰"] {
        let request = dice_request(&peers, 7, emoji, 8, 9, 123, &options).unwrap();
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), MessagesSendMediaRequest::ID);
        let request = MessagesSendMediaRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(request.random_id, 123);
        assert!(request.silent.is_some());
        assert_eq!(request.schedule_date, Some(0x7fff_fffe));
        let InputMedia::InputMediaDice(media) = *request.media else {
            panic!()
        };
        assert_eq!(media.emoticon, emoji);
        let InputReplyTo::InputReplyToMessage(reply) = *request.reply_to.unwrap() else {
            panic!()
        };
        assert_eq!(reply.reply_to_msg_id, 8);
        assert_eq!(reply.top_msg_id, Some(9));
    }
    assert!(dice_request(&peers, 7, "🎲", 0, 0, 0, &options).is_err());
    assert!(dice_request(&peers, 7, "text", 0, 0, 123, &options).is_err());
    assert!(valid_dice_payload(
        Some("dice"),
        Some(r#"{"e":"🎲","v":4}"#),
        "🎲",
        false
    ));
    assert!(!valid_dice_payload(
        Some("dice"),
        Some(r#"{"e":"🎲","v":0}"#),
        "🎲",
        false
    ));
    assert!(valid_dice_payload(
        Some("dice"),
        Some(r#"{"e":"🎲","v":0}"#),
        "🎲",
        true
    ));
    assert!(!valid_dice_payload(
        Some("dice"),
        Some(r#"{"e":"🎯","v":4}"#),
        "🎲",
        false
    ));
    assert!(!valid_dice_payload(None, None, "🎲", false));
}
