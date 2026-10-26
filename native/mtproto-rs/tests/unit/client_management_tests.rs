use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
use tellers_mtproto::latest::api::InputPeerSelfConstructor;

#[test]
fn message_pin_options_survive_wire_encoding_independently() {
    for pinned in [false, true] {
        for silent in [false, true] {
            for one_sided in [false, true] {
                let request = pin_request(
                    InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
                    37,
                    pinned,
                    silent,
                    one_sided,
                );
                let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
                let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
                assert_eq!(
                    decoder.read_u32().unwrap(),
                    MessagesUpdatePinnedMessageRequest::ID
                );
                let decoded = MessagesUpdatePinnedMessageRequest::decode(&mut decoder).unwrap();
                decoder.finish().unwrap();
                assert_eq!(decoded.id, 37);
                assert_eq!(decoded.unpin.is_some(), !pinned);
                assert_eq!(decoded.silent.is_some(), silent);
                assert_eq!(decoded.pm_oneside.is_some(), one_sided);
            }
        }
    }
}

#[test]
fn history_clear_and_revoke_scopes_survive_wire_encoding() {
    for clear in [false, true] {
        for revoke in [false, true] {
            let request = history_request(
                InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
                512,
                revoke,
                clear,
            );
            let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
            let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
            assert_eq!(
                decoder.read_u32().unwrap(),
                MessagesDeleteHistoryRequest::ID
            );
            let decoded = MessagesDeleteHistoryRequest::decode(&mut decoder).unwrap();
            decoder.finish().unwrap();
            assert_eq!(decoded.max_id, 512);
            assert_eq!(decoded.just_clear.is_some(), clear);
            assert_eq!(decoded.revoke.is_some(), revoke);
            assert!(decoded.min_date.is_none());
            assert!(decoded.max_date.is_none());
        }
    }
}

#[test]
fn missing_history_boundary_is_rejected_before_accessing_a_session() {
    for max_id in [0, -1] {
        assert!(clear_history(0, 7, max_id, false).is_err());
        assert!(delete_dialog(0, 7, max_id, false).is_err());
    }
}

#[test]
fn invalid_message_id_is_rejected_before_accessing_a_session() {
    assert!(pin_message(0, 7, 0, true, false, false).is_err());
    assert!(pin_message(0, 7, -1, true, false, false).is_err());
}
