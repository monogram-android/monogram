use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
use tellers_mtproto::latest::api::InputPeerSelfConstructor;

#[test]
fn editing_preserves_unset_false_true_and_explicit_icon_removal() {
    for closed in [None, Some(false), Some(true)] {
        for hidden in [None, Some(false), Some(true)] {
            let changes = TopicChanges {
                title: Some("General".into()),
                icon_emoji_id: Some(0),
                closed,
                hidden,
            };
            let request = edit_request(
                InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
                1,
                &changes,
            );
            let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
            let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
            assert_eq!(
                decoder.read_u32().unwrap(),
                MessagesEditForumTopicRequest::ID
            );
            let decoded = MessagesEditForumTopicRequest::decode(&mut decoder).unwrap();
            decoder.finish().unwrap();
            let value =
                |item: Option<Box<Bool>>| item.map(|value| matches!(*value, Bool::BoolTrue(_)));
            assert_eq!(value(decoded.closed), closed);
            assert_eq!(value(decoded.hidden), hidden);
            assert_eq!(decoded.icon_emoji_id, Some(0));
            assert_eq!(decoded.title.as_deref(), Some("General"));
        }
    }
}

#[test]
fn invalid_topic_operations_fail_before_accessing_a_session() {
    assert!(create_topic(0, -12, "Topic", 0, None, None).is_err());
    assert!(create_topic(0, -12, " ", 7, None, None).is_err());
    assert!(edit_topic(0, -12, 7, TopicChanges::default()).is_err());
    assert!(
        edit_topic(
            0,
            -12,
            7,
            TopicChanges {
                hidden: Some(false),
                ..Default::default()
            }
        )
        .is_err()
    );
    assert!(pin_topic(0, -12, 0, true).is_err());
    assert!(delete_topic(0, -12, 1).is_err());
    assert!(reorder_pinned_topics(0, -12, &[7, 7], false).is_err());
    assert!(reorder_pinned_topics(0, -12, &[-1], true).is_err());
}
