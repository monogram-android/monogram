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

#[test]
fn plain_text_around_structural_entities_is_not_reinterpreted() {
    let text = "code\n---\n☐ todo\nmediatek,gpio_usage_mapping";
    let blocks = render_blocks(text, vec![MarkupEntityDto { kind: "pre".into(), offset: 0, length: 4, extra: None }], false);
    assert_eq!(blocks.len(), 2);
    assert_eq!(blocks[0].kind, "code");
    assert_eq!(blocks[1].kind, "paragraph");
    assert_eq!(blocks[1].text, "---\n☐ todo\nmediatek,gpio_usage_mapping");
}

#[test]
fn details_keep_nested_entities_with_rebased_utf16_ranges() {
    let text = "Summary\n👋 arguments\n{json}";
    let entities = vec![
        MarkupEntityDto { kind: "details".into(), offset: 0, length: utf16_len(text), extra: None },
        MarkupEntityDto { kind: "details".into(), offset: 8, length: utf16_len("👋 arguments\n{json}"), extra: None },
        MarkupEntityDto { kind: "pre".into(), offset: 21, length: 6, extra: Some("json".into()) },
    ];
    let blocks = render_blocks(text, entities, false);
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "details");
    assert_eq!(blocks[0].entities.len(), 2);
    assert_eq!(blocks[0].entities[0].kind, "details");
    assert_eq!(blocks[0].entities[1].offset, 21);
}
