use super::{compose_chat_list_preview, preview_hides_file_name};

#[test]
fn dm_keeps_media_and_caption() {
    assert_eq!(
        compose_chat_list_preview(false, false, Some("Mike"), Some("Photo"), "hello").as_deref(),
        Some("Photo, hello"),
    );
}

#[test]
fn group_prefixes_sender() {
    assert_eq!(
        compose_chat_list_preview(true, false, Some("Mike"), Some("Photo"), "hello").as_deref(),
        Some("Mike: Photo, hello"),
    );
}

#[test]
fn group_outgoing_uses_you() {
    assert_eq!(
        compose_chat_list_preview(true, true, Some("Ada"), None, "sent").as_deref(),
        Some("You: sent"),
    );
}

#[test]
fn sticker_and_gif_file_names_stay_out_of_previews() {
    for kind in ["sticker", "sticker_animated", "sticker_video", "gif"] {
        assert!(preview_hides_file_name(Some(kind), "AnimatedSticker.tgs"));
        assert!(preview_hides_file_name(Some(kind), "sticker.webp"));
        assert!(preview_hides_file_name(Some(kind), "mp4.mp4"));
    }
    // A sticker file can also arrive as a plain document.
    assert!(preview_hides_file_name(Some("document"), "sticker.webm"));
    assert!(preview_hides_file_name(None, "AnimatedSticker.tgs"));
}

#[test]
fn generated_media_names_stay_out_of_previews() {
    assert!(preview_hides_file_name(
        Some("photo"),
        "Screenshot_20200722-104614385.jpg"
    ));
    assert!(preview_hides_file_name(Some("video"), "VID_20200803.mp4"));
    assert!(preview_hides_file_name(Some("voice"), "audio.ogg"));
    assert!(preview_hides_file_name(Some("video_note"), "circle.mp4"));
    assert!(preview_hides_file_name(Some("poll"), "{\"question\":1}"));
}

#[test]
fn documents_and_audio_keep_their_name() {
    assert!(!preview_hides_file_name(Some("document"), "report.pdf"));
    assert!(!preview_hides_file_name(Some("audio"), "Track — Artist"));
}
#[test]
fn only_documents_and_audio_preview_their_file_name() {
    for kind in [
        "todo",
        "poll",
        "geo",
        "venue",
        "contact",
        "dice",
        "photo",
        "video",
        "gif",
        "sticker",
        "sticker_animated",
        "sticker_video",
        "voice",
        "webpage",
    ] {
        assert!(
            preview_hides_file_name(Some(kind), "real-name.png"),
            "{kind} must not surface its file name"
        );
    }
    assert!(!preview_hides_file_name(Some("document"), "report.pdf"));
    assert!(!preview_hides_file_name(Some("audio"), "Track — Artist"));
    assert!(!preview_hides_file_name(None, "holiday.jpg"));
}
