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

#[test]
fn classify_document_kinds() {
    assert_eq!(
        classify_document("application/x-tgsticker", true, true, false),
        "sticker_animated"
    );
    assert_eq!(
        classify_document("image/webp", true, false, false),
        "sticker"
    );
    assert_eq!(classify_document("video/mp4", false, true, false), "gif");
    assert_eq!(classify_document("video/mp4", false, false, true), "video");
}

#[test]
fn round_video_attribute_is_video_note_not_voice() {
    use tellers_mtproto::latest::api::{
        DocumentAttribute, DocumentAttributeVideoConstructor, DocumentConstructor, True,
        TrueConstructor, Vector, VectorConstructor,
    };
    let mut doc = DocumentConstructor {
        flags: 0,
        id: 1,
        access_hash: 1,
        file_reference: vec![1],
        date: 0,
        mime_type: "video/mp4".into(),
        size: 12,
        thumbs: None,
        video_thumbs: None,
        dc_id: 2,
        attributes: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![Box::new(DocumentAttribute::DocumentAttributeVideo(
                DocumentAttributeVideoConstructor {
                    flags: DocumentAttributeVideoConstructor::ROUND_MESSAGE_FLAG,
                    round_message: Some(Box::new(True::True(TrueConstructor {}))),
                    supports_streaming: None,
                    nosound: None,
                    duration: 3.0,
                    w: 240,
                    h: 240,
                    preload_prefix_size: None,
                    video_start_ts: None,
                    video_codec: None,
                },
            ))],
        })),
    };
    assert_eq!(document_kind(&doc), "video_note");
    doc.attributes = Box::new(Vector::Vector(VectorConstructor {
        field_0: 2,
        field_1: vec![
            Box::new(DocumentAttribute::DocumentAttributeAudio(
                tellers_mtproto::latest::api::DocumentAttributeAudioConstructor {
                    flags: 0,
                    voice: None,
                    duration: 2,
                    title: None,
                    performer: None,
                    waveform: None,
                },
            )),
            Box::new(DocumentAttribute::DocumentAttributeVideo(
                DocumentAttributeVideoConstructor {
                    flags: DocumentAttributeVideoConstructor::ROUND_MESSAGE_FLAG,
                    round_message: Some(Box::new(True::True(TrueConstructor {}))),
                    supports_streaming: None,
                    nosound: None,
                    duration: 3.0,
                    w: 240,
                    h: 240,
                    preload_prefix_size: None,
                    video_start_ts: None,
                    video_codec: None,
                },
            )),
        ],
    }));
    assert_eq!(
        document_kind(&doc),
        "video_note",
        "round video wins over a sidecar audio attribute",
    );
    assert_eq!(classify_document("image/gif", false, false, false), "gif");
    assert_eq!(
        classify_document("image/jpeg", false, false, false),
        "document"
    );
    assert_eq!(
        classify_document("image/png", false, false, false),
        "document"
    );
    assert_eq!(
        classify_document("image/webp", false, false, false),
        "document"
    );
    assert_eq!(
        classify_document("application/pdf", false, false, false),
        "document"
    );
}
