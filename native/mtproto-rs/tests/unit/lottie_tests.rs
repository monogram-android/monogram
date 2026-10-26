use super::*;

#[test]
fn render_into_rejects_a_short_buffer_before_writing() {
    let mut out = [0u8; 4];
    let err = render_lottie_frame_into(0, 0.0, 2, 2, &mut out).unwrap_err();
    let message = match err {
        MtprotoError::Message(text) => text,
        other => panic!("unexpected error: {other:?}"),
    };
    assert!(
        message.contains("unknown lottie") || message.contains("too small"),
        "{message}"
    );
}
