use super::*;
use crate::client::pipeline_parts;

use crate::client::set_pipeline_parts;
use tellers_mtproto::latest::api::{
    GeoPointConstructor, GeoPointEmptyConstructor, MessageEntity, MessageMediaContactConstructor,
    MessageMediaDiceConstructor, MessageMediaGeoConstructor, MessageMediaGeoLiveConstructor,
    MessageMediaPollConstructor, MessageMediaVenueConstructor, PollAnswerConstructor,
    PollAnswerVotersConstructor, PollConstructor, PollResultsConstructor,
    TextWithEntitiesConstructor,
};

fn plain(text: &str) -> Box<RichText> {
    Box::new(RichText::TextPlain(
        tellers_mtproto::latest::api::TextPlainConstructor {
            text: text.to_string(),
        },
    ))
}

fn boxed_vector<T>(items: Vec<T>) -> Vector<Box<T>> {
    Vector::Vector(tellers_mtproto::latest::api::VectorConstructor {
        field_0: tellers_mtproto::latest::api::VectorConstructor::<Box<T>>::ID,
        field_1: items.into_iter().map(Box::new).collect(),
    })
}

fn paragraph(text: &str) -> PageBlock {
    PageBlock::PageBlockParagraph(
        tellers_mtproto::latest::api::PageBlockParagraphConstructor { text: plain(text) },
    )
}

#[test]
fn nested_blockquote_blocks_overlap() {
    let inner = PageBlock::PageBlockBlockquoteBlocks(
        tellers_mtproto::latest::api::PageBlockBlockquoteBlocksConstructor {
            blocks: Box::new(boxed_vector(vec![paragraph("inner")])),
            caption: Box::new(RichText::TextEmpty(
                tellers_mtproto::latest::api::TextEmptyConstructor {},
            )),
        },
    );
    let outer = PageBlock::PageBlockBlockquoteBlocks(
        tellers_mtproto::latest::api::PageBlockBlockquoteBlocksConstructor {
            blocks: Box::new(boxed_vector(vec![paragraph("outer"), inner])),
            caption: Box::new(RichText::TextEmpty(
                tellers_mtproto::latest::api::TextEmptyConstructor {},
            )),
        },
    );
    let formatted = page_blocks_formatted(std::iter::once(&outer));
    assert_eq!(formatted.text, "outer\ninner");
    let quotes: Vec<_> = formatted
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(quotes.len(), 2);
    assert_eq!(quotes[0].offset, utf16_len("outer\n"));
    assert_eq!(quotes[0].length, utf16_len("inner"));
    assert_eq!(quotes[1].offset, 0);
    assert_eq!(quotes[1].length, utf16_len("outer\ninner"));
}

#[test]
fn photo_caption_is_kept() {
    let block =
        PageBlock::PageBlockPhoto(tellers_mtproto::latest::api::PageBlockPhotoConstructor {
            flags: 0,
            spoiler: None,
            photo_id: 1,
            caption: Box::new(PageCaption::PageCaption(
                tellers_mtproto::latest::api::PageCaptionConstructor {
                    text: plain("Image title"),
                    credit: Box::new(RichText::TextEmpty(
                        tellers_mtproto::latest::api::TextEmptyConstructor {},
                    )),
                },
            )),
            url: None,
            webpage_id: None,
        });
    let formatted = page_blocks_formatted(std::iter::once(&block));
    assert_eq!(formatted.text, "Image title");
    let photo = Photo::Photo(tellers_mtproto::latest::api::PhotoConstructor {
        flags: 0,
        has_stickers: None,
        id: 1,
        access_hash: 1,
        file_reference: vec![1],
        date: 0,
        sizes: Box::new(boxed_vector(vec![PhotoSize::PhotoSize(
            tellers_mtproto::latest::api::PhotoSizeConstructor {
                type_: "y".into(),
                w: 800,
                h: 450,
                size: 100,
            },
        )])),
        video_sizes: None,
        dc_id: 2,
    });
    let with_photo = page_blocks_formatted_media(std::iter::once(&block), &[photo]);
    assert!(with_photo.text.contains('\u{FFFC}'));
    assert!(with_photo.text.contains("Image title"));
    let photo_entity = with_photo
        .entities
        .iter()
        .find(|entity| entity.kind == "photo")
        .expect("photo entity");
    assert_eq!(photo_entity.url.as_deref(), Some("photo:1:800x450"));
}

fn empty_rich() -> Box<RichText> {
    Box::new(RichText::TextEmpty(
        tellers_mtproto::latest::api::TextEmptyConstructor {},
    ))
}

fn empty_caption() -> Box<PageCaption> {
    Box::new(PageCaption::PageCaption(
        tellers_mtproto::latest::api::PageCaptionConstructor {
            text: empty_rich(),
            credit: empty_rich(),
        },
    ))
}

fn paragraph_of(text: RichText) -> PageBlock {
    PageBlock::PageBlockParagraph(
        tellers_mtproto::latest::api::PageBlockParagraphConstructor {
            text: Box::new(text),
        },
    )
}

#[test]
fn rich_message_fields_survive_flattening() {
    let marked = paragraph_of(RichText::TextMarked(
        tellers_mtproto::latest::api::TextMarkedConstructor { text: plain("secret") },
    ));
    let marked_text = page_blocks_formatted(std::iter::once(&marked));
    assert_eq!(marked_text.text, "secret");
    assert!(marked_text.entities.iter().any(|entity| entity.kind == "marked"));
    assert!(marked_text.entities.iter().all(|entity| entity.kind != "spoiler"));

    let email = paragraph_of(RichText::TextEmail(
        tellers_mtproto::latest::api::TextEmailConstructor {
            text: plain("Email"),
            email: "ada@example.com".into(),
        },
    ));
    let email_text = page_blocks_formatted(std::iter::once(&email));
    assert_eq!(email_text.text, "Email");
    assert_eq!(
        email_text.entities[0].url.as_deref(),
        Some("ada@example.com")
    );

    let phone = paragraph_of(RichText::TextPhone(
        tellers_mtproto::latest::api::TextPhoneConstructor {
            text: plain("Phone"),
            phone: "+15551212".into(),
        },
    ));
    let phone_text = page_blocks_formatted(std::iter::once(&phone));
    assert_eq!(phone_text.entities[0].kind, "phone");
    assert_eq!(phone_text.entities[0].url.as_deref(), Some("+15551212"));

    let mention = paragraph_of(RichText::TextMentionName(
        tellers_mtproto::latest::api::TextMentionNameConstructor {
            text: plain("Ada"),
            user_id: 42,
        },
    ));
    let mention_text = page_blocks_formatted(std::iter::once(&mention));
    assert_eq!(mention_text.entities[0].kind, "mention_name");
    assert_eq!(mention_text.entities[0].url.as_deref(), Some("42"));

    let emoji = paragraph_of(RichText::TextCustomEmoji(
        tellers_mtproto::latest::api::TextCustomEmojiConstructor {
            document_id: 99,
            alt: "©".into(),
        },
    ));
    let emoji_text = page_blocks_formatted(std::iter::once(&emoji));
    assert_eq!(emoji_text.text, "©");
    assert_eq!(emoji_text.entities[0].kind, "custom_emoji");
    assert_eq!(emoji_text.entities[0].url.as_deref(), Some("99"));

    let anchor = paragraph_of(RichText::TextAnchor(
        tellers_mtproto::latest::api::TextAnchorConstructor {
            text: plain("here"),
            name: "intro".into(),
        },
    ));
    let anchor_text = page_blocks_formatted(std::iter::once(&anchor));
    assert_eq!(anchor_text.entities[0].kind, "anchor");
    assert_eq!(anchor_text.entities[0].url.as_deref(), Some("intro"));

    let math = paragraph_of(RichText::TextMath(
        tellers_mtproto::latest::api::TextMathConstructor {
            source: "E=mc^2".into(),
        },
    ));
    let math_text = page_blocks_formatted(std::iter::once(&math));
    assert_eq!(math_text.text, "E=mc^2");
    assert_eq!(math_text.entities[0].kind, "math");
    assert_eq!(math_text.entities[0].url.as_deref(), Some("inline"));

    let date = paragraph_of(RichText::TextDate(
        tellers_mtproto::latest::api::TextDateConstructor {
            flags: tellers_mtproto::latest::api::TextDateConstructor::SHORT_DATE_FLAG,
            relative: None,
            short_time: None,
            long_time: None,
            short_date: Some(Box::new(tellers_mtproto::latest::api::True::True(
                tellers_mtproto::latest::api::TrueConstructor {},
            ))),
            long_date: None,
            day_of_week: None,
            text: plain("fallback"),
            date: 1_710_000_000,
        },
    ));
    let date_text = page_blocks_formatted(std::iter::once(&date));
    assert_eq!(date_text.text, "fallback");
    assert_eq!(date_text.entities[0].kind, "date");
    assert_eq!(
        date_text.entities[0].url.as_deref(),
        Some("1710000000|8")
    );
}

#[test]
fn divider_table_and_block_math_keep_structure() {
    let divider = PageBlock::PageBlockDivider(
        tellers_mtproto::latest::api::PageBlockDividerConstructor {},
    );
    let divided = page_blocks_formatted(std::iter::once(&divider));
    assert_eq!(divided.text, "---");
    assert_eq!(divided.entities[0].kind, "rule");

    let cell = |text: &str, header: bool| {
        tellers_mtproto::latest::api::PageTableCell::PageTableCell(
            tellers_mtproto::latest::api::PageTableCellConstructor {
                flags: 0,
                header: header.then(|| {
                    Box::new(tellers_mtproto::latest::api::True::True(
                        tellers_mtproto::latest::api::TrueConstructor {},
                    ))
                }),
                align_center: None,
                align_right: None,
                valign_middle: None,
                valign_bottom: None,
                text: Some(plain(text)),
                colspan: None,
                rowspan: None,
            },
        )
    };
    let row = |cells: Vec<tellers_mtproto::latest::api::PageTableCell>| {
        tellers_mtproto::latest::api::PageTableRow::PageTableRow(
            tellers_mtproto::latest::api::PageTableRowConstructor {
                cells: Box::new(boxed_vector(cells)),
            },
        )
    };
    let table = PageBlock::PageBlockTable(
        tellers_mtproto::latest::api::PageBlockTableConstructor {
            flags: 0,
            bordered: None,
            striped: None,
            compact: None,
            title: empty_rich(),
            rows: Box::new(boxed_vector(vec![
                row(vec![cell("A", true), cell("B", true)]),
                row(vec![cell("1", false), cell("2", false)]),
            ])),
        },
    );
    let formatted = page_blocks_formatted(std::iter::once(&table));
    assert!(formatted.text.contains("| A | B |"));
    assert!(formatted.text.contains("| 1 | 2 |"));
    let table_entity = formatted
        .entities
        .iter()
        .find(|entity| entity.kind == "table")
        .expect("table entity");
    assert_eq!(
        table_entity.url.as_deref(),
        Some("A\u{1f}B\u{1e}1\u{1f}2")
    );
    assert!(formatted.entities.iter().all(|entity| entity.kind != "pre"));

    let block_math = PageBlock::PageBlockMath(
        tellers_mtproto::latest::api::PageBlockMathConstructor {
            source: "\\frac{1}{2}".into(),
        },
    );
    let math = page_blocks_formatted(std::iter::once(&block_math));
    assert_eq!(math.entities[0].kind, "math");
    assert_eq!(math.entities[0].url.as_deref(), Some("block"));
    assert!(math.entities.iter().all(|entity| entity.kind != "code"));
}

#[test]
fn unsupported_rich_media_is_not_invented() {
    let video = PageBlock::PageBlockVideo(
        tellers_mtproto::latest::api::PageBlockVideoConstructor {
            flags: 0,
            autoplay: None,
            loop_: None,
            spoiler: None,
            video_id: 7,
            caption: empty_caption(),
        },
    );
    let audio = PageBlock::PageBlockAudio(
        tellers_mtproto::latest::api::PageBlockAudioConstructor {
            audio_id: 8,
            caption: empty_caption(),
        },
    );
    let collage = PageBlock::PageBlockCollage(
        tellers_mtproto::latest::api::PageBlockCollageConstructor {
            items: Box::new(boxed_vector(Vec::<PageBlock>::new())),
            caption: empty_caption(),
        },
    );
    let slideshow = PageBlock::PageBlockSlideshow(
        tellers_mtproto::latest::api::PageBlockSlideshowConstructor {
            items: Box::new(boxed_vector(Vec::<PageBlock>::new())),
            caption: empty_caption(),
        },
    );
    let map = PageBlock::PageBlockMap(
        tellers_mtproto::latest::api::PageBlockMapConstructor {
            geo: Box::new(tellers_mtproto::latest::api::GeoPoint::GeoPointEmpty(
                tellers_mtproto::latest::api::GeoPointEmptyConstructor {},
            )),
            zoom: 1,
            w: 10,
            h: 10,
            caption: empty_caption(),
        },
    );
    for block in [&video, &audio, &collage, &slideshow, &map] {
        let formatted = page_blocks_formatted(std::iter::once(block));
        assert!(formatted.text.is_empty(), "{}", formatted.text);
        assert!(
            formatted.entities.iter().all(|entity| {
                entity.kind != "photo" && entity.kind != "video" && entity.kind != "audio"
            })
        );
    }
}

#[test]
fn plain_dashes_are_not_a_divider() {
    let formatted = page_blocks_formatted(std::iter::once(&paragraph("---")));
    assert_eq!(formatted.text, "---");
    assert!(formatted.entities.iter().all(|entity| entity.kind != "rule"));
}
