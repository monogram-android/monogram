use super::{fill_missing_reply_quotes, fwd_from_label, message_to_dto, reply_meta, via_bot_label};
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
        sender_photo_cache_key: None,
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

fn photo_slot(cache_key: &str) -> crate::media::MediaRef {
    crate::media::MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: cache_key.into(),
        location: crate::media::MediaLocation::Inline {
            bytes: Vec::new(),
            extension: "jpg".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    }
}

fn text_message(sender_id: i64, id: i32) -> tellers_mtproto::latest::api::Message {
    use tellers_mtproto::latest::api::{
        Message, MessageConstructor, Peer, PeerChatConstructor, PeerUserConstructor,
    };
    Message::Message(MessageConstructor {
        flags: 0,
        out: None,
        mentioned: None,
        media_unread: None,
        silent: None,
        post: None,
        from_scheduled: None,
        legacy: None,
        edit_hide: None,
        pinned: None,
        noforwards: None,
        invert_media: None,
        flags2: 0,
        offline: None,
        video_processing_pending: None,
        paid_suggested_post_stars: None,
        paid_suggested_post_ton: None,
        id,
        from_id: Some(Box::new(Peer::PeerUser(PeerUserConstructor {
            user_id: sender_id,
        }))),
        from_boosts_applied: None,
        from_rank: None,
        peer_id: Box::new(Peer::PeerChat(PeerChatConstructor { chat_id: 3 })),
        saved_peer_id: None,
        fwd_from: None,
        via_bot_id: None,
        via_business_bot_id: None,
        guestchat_via_from: None,
        reply_to: None,
        date: 1,
        message: "hi".into(),
        media: None,
        reply_markup: None,
        entities: None,
        views: None,
        forwards: None,
        replies: None,
        edit_date: None,
        post_author: None,
        grouped_id: None,
        reactions: None,
        restriction_reason: None,
        ttl_period: None,
        quick_reply_shortcut_id: None,
        effect: None,
        factcheck: None,
        report_delivery_until_date: None,
        paid_message_stars: None,
        suggested_post: None,
        schedule_repeat_period: None,
        summary_from_language: None,
        rich_message: None,
    })
}

fn service_message(sender_id: i64, id: i32) -> tellers_mtproto::latest::api::Message {
    use tellers_mtproto::latest::api::{
        Message, MessageAction, MessageActionHistoryClearConstructor, MessageServiceConstructor,
        Peer, PeerChatConstructor, PeerUserConstructor,
    };
    Message::MessageService(MessageServiceConstructor {
        flags: 0,
        out: None,
        mentioned: None,
        media_unread: None,
        reactions_are_possible: None,
        silent: None,
        post: None,
        legacy: None,
        id,
        from_id: Some(Box::new(Peer::PeerUser(PeerUserConstructor {
            user_id: sender_id,
        }))),
        peer_id: Box::new(Peer::PeerChat(PeerChatConstructor { chat_id: 3 })),
        saved_peer_id: None,
        reply_to: None,
        date: 1,
        action: Box::new(MessageAction::MessageActionHistoryClear(
            MessageActionHistoryClearConstructor {},
        )),
        reactions: None,
        ttl_period: None,
    })
}

#[test]
fn sender_photo_cache_key_defaults_and_copies_avatar_slots_only() {
    let mut value = serde_json::to_value(dto(1, "hi", None, None)).expect("json");
    value
        .as_object_mut()
        .expect("object")
        .remove("sender_photo_cache_key");
    let parsed: MessageDto = serde_json::from_value(value).expect("old json");
    assert!(parsed.sender_photo_cache_key.is_none());

    let mut media = crate::media::MediaIndex::default();
    media.insert((7, 0), photo_slot("avatar:7"));
    let mapped = message_to_dto(&text_message(7, 3), &mut media).expect("text");
    assert_eq!(mapped.sender_photo_cache_key.as_deref(), Some("avatar:7"));

    media.insert((8, 0), photo_slot("avatar:8:video"));
    let video = message_to_dto(&text_message(8, 4), &mut media).expect("video");
    assert_eq!(
        video.sender_photo_cache_key.as_deref(),
        Some("avatar:8:video")
    );

    media.insert((9, 0), photo_slot("doc:9"));
    let document = message_to_dto(&text_message(9, 5), &mut media).expect("doc");
    assert!(document.sender_photo_cache_key.is_none());

    let service = message_to_dto(&service_message(7, 6), &mut media).expect("service");
    assert_eq!(
        service.sender_photo_cache_key.as_deref(),
        Some("avatar:7")
    );
}
