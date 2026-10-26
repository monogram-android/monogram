use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn pagination_preserves_the_paired_cursor_and_revoked_filter() {
    for cursor in [
        None,
        Some(InviteCursor {
            date: 123,
            link: "fixture-link".into(),
        }),
    ] {
        for revoked in [false, true] {
            let request = list_request(
                InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
                InputUser::InputUserSelf(InputUserSelfConstructor {}),
                cursor.as_ref(),
                revoked,
                100,
            );
            let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
            let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
            assert_eq!(
                decoder.read_u32().unwrap(),
                MessagesGetExportedChatInvitesRequest::ID
            );
            let decoded = MessagesGetExportedChatInvitesRequest::decode(&mut decoder).unwrap();
            decoder.finish().unwrap();
            assert_eq!(decoded.offset_date, cursor.as_ref().map(|item| item.date));
            assert_eq!(
                decoded.offset_link,
                cursor.as_ref().map(|item| item.link.clone())
            );
            assert_eq!(decoded.revoked.is_some(), revoked);
            assert_eq!(decoded.limit, 100);
        }
    }
}

#[test]
fn export_preserves_expiry_usage_and_approval_options() {
    for approved in [false, true] {
        let options = InviteOptions {
            expire_date: Some(123),
            usage_limit: Some(7),
            title: Some("Fixture".into()),
            request_needed: approved,
            revoke_permanent: false,
        };
        let request = export_request(
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            &options,
        );
        let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
        let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
        assert_eq!(
            decoder.read_u32().unwrap(),
            MessagesExportChatInviteRequest::ID
        );
        let decoded = MessagesExportChatInviteRequest::decode(&mut decoder).unwrap();
        decoder.finish().unwrap();
        assert_eq!(decoded.expire_date, Some(123));
        assert_eq!(decoded.usage_limit, Some(7));
        assert_eq!(decoded.title.as_deref(), Some("Fixture"));
        assert_eq!(decoded.request_needed.is_some(), approved);
        assert!(decoded.legacy_revoke_permanent.is_none());
        assert!(decoded.subscription_pricing.is_none());
    }
}

#[test]
fn invalid_pages_links_and_empty_edits_fail_before_session_access() {
    for limit in [0, -1, 101] {
        assert!(
            get_invites(0, -12, None, None, false, limit)
                .unwrap_err()
                .to_string()
                .contains("limit")
        );
        assert!(
            get_importers(0, -12, None, true, None, limit)
                .unwrap_err()
                .to_string()
                .contains("limit")
        );
    }
    assert!(
        check_invite(0, " ")
            .unwrap_err()
            .to_string()
            .contains("link")
    );
    assert!(join_invite(0, "").unwrap_err().to_string().contains("link"));
    assert!(
        edit_invite(0, -12, "fixture-link", InviteChanges::default())
            .unwrap_err()
            .to_string()
            .contains("change")
    );
}
