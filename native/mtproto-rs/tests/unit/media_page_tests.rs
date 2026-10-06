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
