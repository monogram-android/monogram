use super::*;
use tellers_mtproto::latest::api::{
    BotInlineResultConstructor, MessagesBotResultsConstructor, Vector, VectorConstructor,
};

#[test]
fn inline_peer_rejected_matches_406() {
    assert!(super::inline_peer_rejected(&MtprotoError::Message(
        "RPC 406: unknown".into()
    )));
    assert!(super::inline_peer_rejected(&MtprotoError::Message(
        "RPC 400: PEER_ID_INVALID".into()
    )));
    assert!(!super::inline_peer_rejected(&MtprotoError::Message(
        "RPC 400: BOT_INLINE_DISABLED".into()
    )));
}

#[test]
fn strip_username_drops_at() {
    assert_eq!(strip_username(" @gif "), "gif");
    assert_eq!(strip_username("pic"), "pic");
    assert!(strip_username("   ").is_empty());
}

#[test]
fn web_result_maps_article_fields() {
    let item = BotInlineResult::BotInlineResult(BotInlineResultConstructor {
        flags: 0,
        id: "1".into(),
        type_: "article".into(),
        title: Some("Cat".into()),
        description: Some("desc".into()),
        url: Some("https://t.me".into()),
        thumb: None,
        content: None,
        send_message: Box::new(
            tellers_mtproto::latest::api::BotInlineMessage::BotInlineMessageText(
                tellers_mtproto::latest::api::BotInlineMessageTextConstructor {
                    flags: 0,
                    no_webpage: None,
                    invert_media: None,
                    message: "hi".into(),
                    entities: None,
                    reply_markup: None,
                },
            ),
        ),
    });
    let mut media = MediaIndex::default();
    let dto = map_inline_result(&item, &mut media);
    assert_eq!(dto.id, "1");
    assert_eq!(dto.kind, "article");
    assert_eq!(dto.title.as_deref(), Some("Cat"));
    assert_eq!(dto.url.as_deref(), Some("https://t.me"));
    assert!(dto.document_id.is_none());
    assert!(dto.thumb_cache_key.is_none());
}

#[test]
fn web_thumb_url_maps_http_cache_key() {
    use tellers_mtproto::latest::api::{Vector, VectorConstructor, WebDocumentNoProxyConstructor};
    let item = BotInlineResult::BotInlineResult(BotInlineResultConstructor {
        flags: BotInlineResultConstructor::THUMB_FLAG,
        id: "2".into(),
        type_: "photo".into(),
        title: None,
        description: None,
        url: Some("https://example.com/cat".into()),
        thumb: Some(Box::new(WebDocument::WebDocumentNoProxy(
            WebDocumentNoProxyConstructor {
                url: "https://cdn.example/t.jpg".into(),
                size: 12,
                mime_type: "image/jpeg".into(),
                attributes: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 0,
                    field_1: Vec::new(),
                })),
            },
        ))),
        content: None,
        send_message: Box::new(
            tellers_mtproto::latest::api::BotInlineMessage::BotInlineMessageText(
                tellers_mtproto::latest::api::BotInlineMessageTextConstructor {
                    flags: 0,
                    no_webpage: None,
                    invert_media: None,
                    message: "hi".into(),
                    entities: None,
                    reply_markup: None,
                },
            ),
        ),
    });
    let dto = map_inline_result(&item, &mut MediaIndex::default());
    assert_eq!(
        dto.thumb_cache_key.as_deref(),
        Some("https://cdn.example/t.jpg")
    );
}

#[test]
fn results_body_maps_gallery_and_offset() {
    let body = MessagesBotResultsConstructor {
        flags: 1,
        gallery: Some(Box::new(True::True(TrueConstructor {}))),
        query_id: 99,
        next_offset: Some("abc".into()),
        switch_pm: None,
        switch_webview: None,
        results: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: Vec::new(),
        })),
        cache_time: 30,
        users: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: Vec::new(),
        })),
    };
    let mut media = MediaIndex::default();
    let dto = results_from_body(&body, &mut media);
    assert_eq!(dto.query_id, 99);
    assert!(dto.gallery);
    assert_eq!(dto.next_offset.as_deref(), Some("abc"));
    assert_eq!(dto.cache_time, 30);
    assert!(dto.results.is_empty());
}
