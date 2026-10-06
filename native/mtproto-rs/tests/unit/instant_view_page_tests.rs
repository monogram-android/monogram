use crate::media::MediaIndex;
use crate::{HashMap, HashMapExt};
use serde_json::Value;
use tellers_mtproto::latest::api::{
    Document, GeoPoint, InlineButtonType, Page, PageBlock, PageButton, PageCaption, PageListItem,
    PageListOrderedItem, PageRelatedArticle, PageTableRow, Photo, RichText, WebPage,
};

use super::*;
use tellers_mtproto::latest::api::{
    PageBlockParagraphConstructor, PageCaptionConstructor, TextEmptyConstructor,
    TextPlainConstructor, Vector, VectorConstructor, WebPageConstructor,
};

#[test]
fn not_modified_reuses_requested_hash() {
    let dto = map_messages_web_page(
        &WebPage::WebPageNotModified(
            tellers_mtproto::latest::api::WebPageNotModifiedConstructor {
                flags: 0,
                cached_page_views: None,
            },
        ),
        &mut MediaIndex::new(),
        42,
    );
    assert!(dto.not_modified);
    assert_eq!(dto.hash, 42);
    assert!(!dto.has_instant_view);
}

#[test]
fn web_page_without_cached_page_has_no_iv() {
    let page = WebPage::WebPage(WebPageConstructor {
        flags: 0,
        has_large_media: None,
        video_cover_photo: None,
        id: 1,
        url: "https://example.com".into(),
        display_url: "example.com".into(),
        hash: 9,
        type_: Some("article".into()),
        site_name: Some("Example".into()),
        title: Some("Hello".into()),
        description: Some("Desc".into()),
        photo: None,
        embed_url: None,
        embed_type: None,
        embed_width: None,
        embed_height: None,
        duration: None,
        author: None,
        document: None,
        cached_page: None,
        attributes: None,
    });
    let dto = map_messages_web_page(&page, &mut MediaIndex::new(), 0);
    assert!(!dto.has_instant_view);
    assert_eq!(dto.hash, 9);
    assert_eq!(dto.url, "https://example.com");
    assert_eq!(dto.blocks_json, "[]");
}

#[test]
fn cached_page_part_flag_is_preserved() {
    let cached = Page::Page(tellers_mtproto::latest::api::PageConstructor {
        flags: tellers_mtproto::latest::api::PageConstructor::PART_FLAG,
        part: Some(Box::new(tellers_mtproto::latest::api::True::True(
            tellers_mtproto::latest::api::TrueConstructor {},
        ))),
        rtl: None,
        v2: None,
        url: "https://example.com".into(),
        blocks: Box::new(boxed_vector(Vec::<PageBlock>::new())),
        photos: Box::new(boxed_vector(Vec::<Photo>::new())),
        documents: Box::new(boxed_vector(Vec::<Document>::new())),
        views: None,
    });
    let page = WebPage::WebPage(WebPageConstructor {
        flags: 0,
        has_large_media: None,
        video_cover_photo: None,
        id: 1,
        url: "https://example.com".into(),
        display_url: "example.com".into(),
        hash: 4,
        type_: Some("article".into()),
        site_name: None,
        title: None,
        description: None,
        photo: None,
        embed_url: None,
        embed_type: None,
        embed_width: None,
        embed_height: None,
        duration: None,
        author: None,
        document: None,
        cached_page: Some(Box::new(cached)),
        attributes: None,
    });
    let dto = map_messages_web_page(&page, &mut MediaIndex::new(), 0);
    assert!(dto.has_instant_view);
    assert!(dto.part);
    assert_eq!(dto.hash, 4);
}

#[test]
fn cached_page_indexes_photo_and_maps_paragraph() {
    let photo = Photo::Photo(tellers_mtproto::latest::api::PhotoConstructor {
        flags: 0,
        has_stickers: None,
        id: 77,
        access_hash: 2,
        file_reference: vec![1],
        date: 0,
        sizes: Box::new(boxed_vector(vec![
            tellers_mtproto::latest::api::PhotoSize::PhotoSize(
                tellers_mtproto::latest::api::PhotoSizeConstructor {
                    type_: "y".into(),
                    w: 800,
                    h: 450,
                    size: 10,
                },
            ),
        ])),
        video_sizes: None,
        dc_id: 2,
    });
    let blocks = vec![
        PageBlock::PageBlockParagraph(PageBlockParagraphConstructor {
            text: plain("Body"),
        }),
        PageBlock::PageBlockPhoto(tellers_mtproto::latest::api::PageBlockPhotoConstructor {
            flags: 0,
            spoiler: None,
            photo_id: 77,
            caption: empty_caption(),
            url: None,
            webpage_id: None,
        }),
    ];
    let cached = Page::Page(tellers_mtproto::latest::api::PageConstructor {
        flags: 0,
        part: None,
        rtl: None,
        v2: None,
        url: "https://example.com".into(),
        blocks: Box::new(boxed_vector(blocks)),
        photos: Box::new(boxed_vector(vec![photo])),
        documents: Box::new(boxed_vector(Vec::<Document>::new())),
        views: None,
    });
    let page = WebPage::WebPage(WebPageConstructor {
        flags: 0,
        has_large_media: None,
        video_cover_photo: None,
        id: 1,
        url: "https://example.com".into(),
        display_url: "example.com".into(),
        hash: 3,
        type_: Some("article".into()),
        site_name: None,
        title: Some("Hello".into()),
        description: None,
        photo: None,
        embed_url: None,
        embed_type: None,
        embed_width: None,
        embed_height: None,
        duration: None,
        author: None,
        document: None,
        cached_page: Some(Box::new(cached)),
        attributes: None,
    });
    let mut media = MediaIndex::new();
    let dto = map_messages_web_page(&page, &mut media, 0);
    assert!(dto.has_instant_view);
    assert!(media.contains_key(&(77, INSTANT_VIEW_MEDIA_MSG)));
    assert_eq!(
        media
            .get(&(77, INSTANT_VIEW_MEDIA_MSG))
            .unwrap()
            .source_url
            .as_deref(),
        Some("https://example.com"),
    );
    assert_eq!(
        media_refresh_url(&media, 77, INSTANT_VIEW_MEDIA_MSG).as_deref(),
        Some("https://example.com"),
    );
    assert_eq!(media_refresh_url(&media, 77, 12), None);
    let parsed: Value = serde_json::from_str(&dto.blocks_json).unwrap();
    assert_eq!(parsed[0]["k"], "paragraph");
    assert_eq!(parsed[0]["t"], "Body");
    assert_eq!(parsed[1]["k"], "photo");
    assert_eq!(parsed[1]["cache"], "photo:77");
}

#[test]
fn webpage_cover_photo_is_indexed_for_article_blocks() {
    let cover = Photo::Photo(tellers_mtproto::latest::api::PhotoConstructor {
        flags: 0,
        has_stickers: None,
        id: 88,
        access_hash: 2,
        file_reference: vec![1],
        date: 0,
        sizes: Box::new(boxed_vector(vec![
            tellers_mtproto::latest::api::PhotoSize::PhotoSize(
                tellers_mtproto::latest::api::PhotoSizeConstructor {
                    type_: "y".into(),
                    w: 1200,
                    h: 675,
                    size: 10,
                },
            ),
        ])),
        video_sizes: None,
        dc_id: 2,
    });
    let cached = Page::Page(tellers_mtproto::latest::api::PageConstructor {
        flags: 0,
        part: None,
        rtl: None,
        v2: None,
        url: "https://example.com/post".into(),
        blocks: Box::new(boxed_vector(vec![PageBlock::PageBlockPhoto(
            tellers_mtproto::latest::api::PageBlockPhotoConstructor {
                flags: 0,
                spoiler: None,
                photo_id: 88,
                caption: empty_caption(),
                url: None,
                webpage_id: None,
            },
        )])),
        photos: Box::new(boxed_vector(Vec::<Photo>::new())),
        documents: Box::new(boxed_vector(Vec::<Document>::new())),
        views: None,
    });
    let page = WebPage::WebPage(WebPageConstructor {
        flags: 0,
        has_large_media: None,
        video_cover_photo: None,
        id: 1,
        url: "https://example.com/post".into(),
        display_url: "example.com".into(),
        hash: 3,
        type_: Some("article".into()),
        site_name: None,
        title: Some("Hello".into()),
        description: None,
        photo: Some(Box::new(cover)),
        embed_url: None,
        embed_type: None,
        embed_width: None,
        embed_height: None,
        duration: None,
        author: None,
        document: None,
        cached_page: Some(Box::new(cached)),
        attributes: None,
    });
    let mut media = MediaIndex::new();
    let dto = map_messages_web_page(&page, &mut media, 0);
    assert!(media.contains_key(&(88, INSTANT_VIEW_MEDIA_MSG)));
    assert_eq!(
        media_refresh_url(&media, 88, INSTANT_VIEW_MEDIA_MSG).as_deref(),
        Some("https://example.com/post"),
    );
    let parsed: Value = serde_json::from_str(&dto.blocks_json).unwrap();
    assert_eq!(parsed[0]["cache"], "photo:88");
    assert_eq!(parsed[0]["w"], 1200);
    assert_eq!(parsed[0]["h"], 675);
}

fn plain(text: &str) -> Box<RichText> {
    Box::new(RichText::TextPlain(TextPlainConstructor {
        text: text.to_string(),
    }))
}

fn boxed_vector<T>(items: Vec<T>) -> Vector<Box<T>> {
    Vector::Vector(VectorConstructor {
        field_0: VectorConstructor::<Box<T>>::ID,
        field_1: items.into_iter().map(Box::new).collect(),
    })
}

fn empty_caption() -> Box<PageCaption> {
    Box::new(PageCaption::PageCaption(PageCaptionConstructor {
        text: Box::new(RichText::TextEmpty(TextEmptyConstructor {})),
        credit: Box::new(RichText::TextEmpty(TextEmptyConstructor {})),
    }))
}
