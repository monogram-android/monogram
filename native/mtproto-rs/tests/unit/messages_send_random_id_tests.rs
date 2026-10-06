use super::outgoing_random_id;

#[test]
fn pending_random_id_is_kept() {
    assert_eq!(outgoing_random_id(42), 42);
    assert_ne!(outgoing_random_id(0), 0);
}

#[test]
fn pending_random_id_is_encoded_in_send_message() {
    use tellers_mtproto::codec::{Boxed, Encoder};
    use tellers_mtproto::latest::api::{InputPeer, InputPeerSelfConstructor};
    let random_id = 0x0102_0304_0506_0708;
    let request = super::send_message_request(
        0,
        Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
        None,
        "hi".into(),
        random_id,
        None,
    );
    assert_eq!(request.random_id, random_id);
    let mut encoder = Encoder::new();
    request.encode_boxed(&mut encoder).expect("encode");
    let bytes = encoder.into_bytes();
    assert!(
        bytes
            .windows(8)
            .any(|window| window == random_id.to_le_bytes()),
        "sendMessage must carry the pending random_id"
    );
}
