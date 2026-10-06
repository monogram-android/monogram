use super::{fill_missing_reply_quotes, fwd_from_label, reply_meta, via_bot_label};
use crate::MessageDto;
use crate::{HashMap, HashMapExt};
use tellers_mtproto::latest::api::{
    MessageFwdHeader, MessageFwdHeaderConstructor, MessageReplyHeader,
    MessageReplyHeaderConstructor, True, TrueConstructor,
};

fn dto(id: i32, text: &str, reply_to: Option<i32>, quote: Option<&str>) -> MessageDto {
    MessageDto {
        chat_id: 1,
        id,
        sender_id: None,
        text: Some(text.into()),
        date: 0,
        edit_date: None,
        outgoing: false,
        media_kind: None,
        media_cache_key: None,
        thumb_cache_key: None,
        media_duration: None,
        media_width: None,
        media_height: None,
        reply_quote: quote.map(str::to_string),
        entities_json: None,
        noforwards: false,
        reply_to_msg_id: reply_to,
        reply_to_top_id: None,
        fwd_from: None,
        fwd_from_id: None,
        fwd_date: None,
        via_bot: None,
        sender_name: None,
        sender_emoji_status_document_id: None,
        grouped_id: None,
        file_name: None,
        file_size: None,
        supports_streaming: false,
        reactions_json: None,
        replies_count: 0,
        discussion_peer_id: None,
        reply_markup_json: None,
        forum_topic: false,
    }
}

#[test]
fn reply_meta_reads_id_and_quote() {
    let header = MessageReplyHeader::MessageReplyHeader(MessageReplyHeaderConstructor {
        flags: 0,
        reply_to_scheduled: None,
        forum_topic: None,
        quote: None,
        reply_to_msg_id: Some(9),
        reply_to_peer_id: None,
        reply_from: None,
        reply_media: None,
        reply_to_top_id: Some(42),
        quote_text: Some("quoted".into()),
        quote_entities: None,
        quote_offset: None,
        todo_item_id: None,
        poll_option: None,
        reply_to_ephemeral: None,
    });
    assert_eq!(
        reply_meta(Some(&header)),
        (Some(9), Some(42), Some("quoted".into()), false),
    );
}

#[test]
fn reply_meta_reads_forum_topic_flag() {
    let header = MessageReplyHeader::MessageReplyHeader(MessageReplyHeaderConstructor {
        flags: 0,
        reply_to_scheduled: None,
        forum_topic: Some(Box::new(True::True(TrueConstructor {}))),
        quote: None,
        reply_to_msg_id: Some(9),
        reply_to_peer_id: None,
        reply_from: None,
        reply_media: None,
        reply_to_top_id: Some(42),
        quote_text: None,
        quote_entities: None,
        quote_offset: None,
        todo_item_id: None,
        poll_option: None,
        reply_to_ephemeral: None,
    });
    assert_eq!(reply_meta(Some(&header)).3, true);
}

#[test]
fn fwd_prefers_from_name() {
    let header = MessageFwdHeader::MessageFwdHeader(MessageFwdHeaderConstructor {
        flags: 0,
        imported: None,
        saved_out: None,
        from_id: None,
        from_name: Some("Alice".into()),
        date: 1,
        channel_post: None,
        post_author: Some("Bob".into()),
        saved_from_peer: None,
        saved_from_msg_id: None,
        saved_from_id: None,
        saved_from_name: None,
        saved_date: None,
        psa_type: None,
    });
    assert_eq!(
        fwd_from_label(Some(&header), &HashMap::new()).as_deref(),
        Some("Alice")
    );
    assert_eq!(super::fwd_origin(Some(&header)), (None, Some(1)));
    let MessageFwdHeader::MessageFwdHeader(mut visible) = header else {
        panic!("expected forward header");
    };
    visible.from_id = Some(Box::new(tellers_mtproto::latest::api::Peer::PeerUser(
        tellers_mtproto::latest::api::PeerUserConstructor { user_id: 7 },
    )));
    assert_eq!(
        super::fwd_origin(Some(&MessageFwdHeader::MessageFwdHeader(visible))),
        (Some(7), Some(1))
    );
}

#[test]
fn via_bot_uses_username() {
    let mut names = HashMap::new();
    names.insert(7_i64, "Helper Bot".into());
    let mut users = HashMap::new();
    users.insert(7_i64, "helperbot".into());
    assert_eq!(
        via_bot_label(Some(7), &users, &names).as_deref(),
        Some("helperbot")
    );
}

#[test]
fn fill_reply_quote_from_sibling() {
    let mut dtos = vec![
        dto(1, "original", None, None),
        dto(2, "reply", Some(1), None),
    ];
    fill_missing_reply_quotes(&mut dtos);
    assert_eq!(dtos[1].reply_quote.as_deref(), Some("original"));
}

use super::entity_json;

#[test]
fn entity_json_roundtrip_shape() {
    let payload = serde_json::to_string(&vec![entity_json("bold", 0, 4, None)]).expect("json");
    assert!(payload.contains("\"kind\":\"bold\""));
    assert!(payload.contains("\"offset\":0"));
    assert!(payload.contains("\"length\":4"));
}
