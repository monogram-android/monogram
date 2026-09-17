//! Instant View mapping and `messages.getWebPage`.
//! https://core.telegram.org/constructor/page
//! https://core.telegram.org/type/PageBlock
//! https://core.telegram.org/method/messages.getWebPage
//! https://core.telegram.org/constructor/webPage

pub use crate::instant_view::*;

#[cfg(test)]
use crate::media::MediaIndex;
#[cfg(test)]
use serde_json::Value;
#[cfg(test)]
use std::collections::HashMap;
#[cfg(test)]
use tellers_mtproto::latest::api::{
    Document, GeoPoint, InlineButtonType, Page, PageBlock, PageButton, PageCaption, PageListItem,
    PageListOrderedItem, PageRelatedArticle, PageTableRow, Photo, RichText, WebPage,
};

#[cfg(test)]
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{
        PageBlockParagraphConstructor, PageCaptionConstructor, TextEmptyConstructor,
        TextPlainConstructor, Vector, VectorConstructor, WebPageConstructor,
    };

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

    #[test]
    fn maps_all_documented_block_kinds_without_drop() {
        let blocks = vec![
            PageBlock::PageBlockUnsupported(
                tellers_mtproto::latest::api::PageBlockUnsupportedConstructor {},
            ),
            PageBlock::PageBlockTitle(tellers_mtproto::latest::api::PageBlockTitleConstructor {
                text: plain("Title"),
            }),
            PageBlock::PageBlockSubtitle(
                tellers_mtproto::latest::api::PageBlockSubtitleConstructor { text: plain("Sub") },
            ),
            PageBlock::PageBlockAuthorDate(
                tellers_mtproto::latest::api::PageBlockAuthorDateConstructor {
                    author: plain("Ada"),
                    published_date: 1,
                },
            ),
            PageBlock::PageBlockHeader(tellers_mtproto::latest::api::PageBlockHeaderConstructor {
                text: plain("H"),
            }),
            PageBlock::PageBlockSubheader(
                tellers_mtproto::latest::api::PageBlockSubheaderConstructor { text: plain("SH") },
            ),
            PageBlock::PageBlockParagraph(PageBlockParagraphConstructor { text: plain("Hi") }),
            PageBlock::PageBlockPreformatted(
                tellers_mtproto::latest::api::PageBlockPreformattedConstructor {
                    text: plain("code"),
                    language: "kt".into(),
                },
            ),
            PageBlock::PageBlockFooter(tellers_mtproto::latest::api::PageBlockFooterConstructor {
                text: plain("F"),
            }),
            PageBlock::PageBlockDivider(
                tellers_mtproto::latest::api::PageBlockDividerConstructor {},
            ),
            PageBlock::PageBlockAnchor(tellers_mtproto::latest::api::PageBlockAnchorConstructor {
                name: "a".into(),
            }),
            PageBlock::PageBlockKicker(tellers_mtproto::latest::api::PageBlockKickerConstructor {
                text: plain("K"),
            }),
            PageBlock::PageBlockMath(tellers_mtproto::latest::api::PageBlockMathConstructor {
                source: "x^2".into(),
            }),
        ];
        let ctx = PageContext {
            photos: HashMap::new(),
            documents: HashMap::new(),
        };
        let mapped: Vec<String> = blocks
            .iter()
            .map(|b| map_block(b, &ctx)["k"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(
            mapped,
            vec![
                "unsupported",
                "title",
                "subtitle",
                "authorDate",
                "heading",
                "heading",
                "paragraph",
                "pre",
                "footer",
                "divider",
                "anchor",
                "kicker",
                "math",
            ]
        );
    }

    #[test]
    fn maps_remaining_official_page_blocks() {
        let caption = empty_caption();
        let blocks = vec![
            PageBlock::PageBlockList(tellers_mtproto::latest::api::PageBlockListConstructor {
                items: Box::new(boxed_vector(Vec::<PageListItem>::new())),
            }),
            PageBlock::PageBlockOrderedList(
                tellers_mtproto::latest::api::PageBlockOrderedListConstructor {
                    flags: 0,
                    reversed: None,
                    items: Box::new(boxed_vector(Vec::<PageListOrderedItem>::new())),
                    start: None,
                    type_: None,
                },
            ),
            PageBlock::PageBlockBlockquote(
                tellers_mtproto::latest::api::PageBlockBlockquoteConstructor {
                    flags: 0,
                    collapsed: None,
                    text: plain("q"),
                    caption: Box::new(RichText::TextEmpty(TextEmptyConstructor {})),
                },
            ),
            PageBlock::PageBlockPullquote(
                tellers_mtproto::latest::api::PageBlockPullquoteConstructor {
                    text: plain("p"),
                    caption: Box::new(RichText::TextEmpty(TextEmptyConstructor {})),
                },
            ),
            PageBlock::PageBlockPhoto(tellers_mtproto::latest::api::PageBlockPhotoConstructor {
                flags: 0,
                spoiler: None,
                photo_id: 1,
                caption: caption.clone(),
                url: Some("https://example.com".into()),
                webpage_id: None,
            }),
            PageBlock::PageBlockVideo(tellers_mtproto::latest::api::PageBlockVideoConstructor {
                flags: 0,
                autoplay: None,
                loop_: None,
                spoiler: None,
                video_id: 2,
                caption: caption.clone(),
            }),
            PageBlock::PageBlockAudio(tellers_mtproto::latest::api::PageBlockAudioConstructor {
                audio_id: 3,
                caption: caption.clone(),
            }),
            PageBlock::PageBlockCover(tellers_mtproto::latest::api::PageBlockCoverConstructor {
                cover: Box::new(PageBlock::PageBlockParagraph(
                    PageBlockParagraphConstructor { text: plain("c") },
                )),
            }),
            PageBlock::PageBlockEmbed(tellers_mtproto::latest::api::PageBlockEmbedConstructor {
                flags: 0,
                full_width: None,
                allow_scrolling: None,
                url: Some("https://embed".into()),
                html: None,
                poster_photo_id: None,
                w: Some(16),
                h: Some(9),
                caption: caption.clone(),
            }),
            PageBlock::PageBlockEmbedPost(
                tellers_mtproto::latest::api::PageBlockEmbedPostConstructor {
                    url: "https://post".into(),
                    webpage_id: 0,
                    author_photo_id: 0,
                    author: "Ada".into(),
                    date: 1,
                    blocks: Box::new(boxed_vector(Vec::<PageBlock>::new())),
                    caption: caption.clone(),
                },
            ),
            PageBlock::PageBlockCollage(
                tellers_mtproto::latest::api::PageBlockCollageConstructor {
                    items: Box::new(boxed_vector(Vec::<PageBlock>::new())),
                    caption: caption.clone(),
                },
            ),
            PageBlock::PageBlockSlideshow(
                tellers_mtproto::latest::api::PageBlockSlideshowConstructor {
                    items: Box::new(boxed_vector(Vec::<PageBlock>::new())),
                    caption: caption.clone(),
                },
            ),
            PageBlock::PageBlockTable(tellers_mtproto::latest::api::PageBlockTableConstructor {
                flags: 0,
                bordered: None,
                striped: None,
                compact: None,
                title: Box::new(RichText::TextEmpty(TextEmptyConstructor {})),
                rows: Box::new(boxed_vector(Vec::<PageTableRow>::new())),
            }),
            PageBlock::PageBlockDetails(
                tellers_mtproto::latest::api::PageBlockDetailsConstructor {
                    flags: 0,
                    open: None,
                    blocks: Box::new(boxed_vector(Vec::<PageBlock>::new())),
                    title: plain("d"),
                },
            ),
            PageBlock::PageBlockRelatedArticles(
                tellers_mtproto::latest::api::PageBlockRelatedArticlesConstructor {
                    title: plain("more"),
                    articles: Box::new(boxed_vector(Vec::<PageRelatedArticle>::new())),
                },
            ),
            PageBlock::PageBlockMap(tellers_mtproto::latest::api::PageBlockMapConstructor {
                geo: Box::new(GeoPoint::GeoPointEmpty(
                    tellers_mtproto::latest::api::GeoPointEmptyConstructor {},
                )),
                zoom: 10,
                w: 320,
                h: 180,
                caption: caption.clone(),
            }),
        ];
        let ctx = PageContext {
            photos: HashMap::new(),
            documents: HashMap::new(),
        };
        let mapped: Vec<String> = blocks
            .iter()
            .map(|b| map_block(b, &ctx)["k"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(
            mapped,
            vec![
                "list",
                "list",
                "quote",
                "quote",
                "photo",
                "video",
                "audio",
                "cover",
                "embed",
                "embedPost",
                "collage",
                "slideshow",
                "table",
                "details",
                "related",
                "map",
            ]
        );
        assert_eq!(map_block(&blocks[4], &ctx)["url"], "https://example.com");
    }

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

    #[test]
    fn maps_document_filename_and_text_anchor() {
        let document = Document::Document(tellers_mtproto::latest::api::DocumentConstructor {
            flags: 0,
            id: 8,
            access_hash: 1,
            file_reference: vec![1],
            date: 0,
            mime_type: "application/pdf".into(),
            size: 2048,
            thumbs: None,
            video_thumbs: None,
            dc_id: 2,
            attributes: Box::new(boxed_vector(vec![
                tellers_mtproto::latest::api::DocumentAttribute::DocumentAttributeFilename(
                    tellers_mtproto::latest::api::DocumentAttributeFilenameConstructor {
                        file_name: "notes.pdf".into(),
                    },
                ),
            ])),
        });
        let ctx = PageContext {
            photos: HashMap::new(),
            documents: HashMap::from([(8, &document)]),
        };
        let mapped = map_block(
            &PageBlock::PageBlockDocument(
                tellers_mtproto::latest::api::PageBlockDocumentConstructor {
                    document_id: 8,
                    caption: empty_caption(),
                },
            ),
            &ctx,
        );
        assert_eq!(mapped["k"], "document");
        assert_eq!(mapped["name"], "notes.pdf");
        assert_eq!(mapped["mime"], "application/pdf");
        assert_eq!(mapped["size"], 2048);
        let anchored = rich_json(&RichText::TextAnchor(
            tellers_mtproto::latest::api::TextAnchorConstructor {
                text: plain("here"),
                name: "intro".into(),
            },
        ));
        assert_eq!(anchored["t"], "here");
        assert_eq!(anchored["e"][0]["k"], "anchor");
        assert_eq!(anchored["e"][0]["u"], "#intro");
        let button = page_button(&PageButton::PageButton(
            tellers_mtproto::latest::api::PageButtonConstructor {
                flags: 0,
                text: plain("Open"),
                type_: Box::new(InlineButtonType::InlineButtonTypeUrl(
                    tellers_mtproto::latest::api::InlineButtonTypeUrlConstructor {
                        url: "https://example.com/go".into(),
                    },
                )),
                style: None,
            },
        ))
        .unwrap();
        assert_eq!(button["t"], "Open");
        assert_eq!(button["e"][0]["k"], "text_url");
        assert_eq!(button["e"][0]["u"], "https://example.com/go");
    }

    #[test]
    fn video_block_carries_document_dimensions() {
        let document = Document::Document(tellers_mtproto::latest::api::DocumentConstructor {
            flags: 0,
            id: 9,
            access_hash: 1,
            file_reference: vec![1],
            date: 0,
            mime_type: "video/mp4".into(),
            size: 4096,
            thumbs: None,
            video_thumbs: None,
            dc_id: 2,
            attributes: Box::new(boxed_vector(vec![
                tellers_mtproto::latest::api::DocumentAttribute::DocumentAttributeVideo(
                    tellers_mtproto::latest::api::DocumentAttributeVideoConstructor {
                        flags: 0,
                        round_message: None,
                        supports_streaming: None,
                        nosound: None,
                        duration: 17.0,
                        w: 1280,
                        h: 720,
                        preload_prefix_size: None,
                        video_start_ts: None,
                        video_codec: None,
                    },
                ),
            ])),
        });
        let ctx = PageContext {
            photos: HashMap::new(),
            documents: HashMap::from([(9, &document)]),
        };
        let mapped = map_block(
            &PageBlock::PageBlockVideo(tellers_mtproto::latest::api::PageBlockVideoConstructor {
                flags: 0,
                autoplay: None,
                loop_: None,
                spoiler: None,
                video_id: 9,
                caption: empty_caption(),
            }),
            &ctx,
        );
        assert_eq!(mapped["k"], "video");
        assert_eq!(mapped["w"], 1280);
        assert_eq!(mapped["h"], 720);
        assert_eq!(mapped["duration"], 17);
    }
}
