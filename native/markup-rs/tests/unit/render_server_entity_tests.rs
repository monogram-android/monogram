use super::*;
use crate::utf16::utf16_len;

#[test]
fn server_entities_keep_literal_markdown() {
    let blocks = render_blocks(
        "**literal**",
        vec![MarkupEntityDto {
            kind: "bold".into(),
            offset: 2,
            length: 7,
            extra: None,
        }],
        true,
    );
    assert_eq!(blocks[0].text, "**literal**");
    assert_eq!(blocks[0].entities[0].offset, 2);
    assert_eq!(
        render_blocks("# literal\n---", vec![], false)[0].text,
        "# literal\n---"
    );
}

#[test]
fn server_pre_is_not_parsed_as_a_table() {
    let raw = "| A |\n| --- |";
    let blocks = render_blocks(
        raw,
        vec![MarkupEntityDto {
            kind: "pre".into(),
            offset: 0,
            length: utf16_len(raw),
            extra: Some("text".into()),
        }],
        false,
    );
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "code");
    assert_eq!(blocks[0].text, raw);
}
