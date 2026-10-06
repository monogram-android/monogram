use super::*;
use crate::utf16::utf16_len;

#[test]
fn structural_entity_separators_do_not_render_empty_paragraphs() {
    let blocks = render_blocks(
        "before\n\ncode\n\nafter",
        vec![MarkupEntityDto {
            kind: "pre".into(),
            offset: 8,
            length: 4,
            extra: None,
        }],
        false,
    );
    assert_eq!(
        blocks.iter().map(|b| b.text.as_str()).collect::<Vec<_>>(),
        ["before", "code", "after"]
    );
    assert_eq!(render_blocks("a\n\nb", vec![], false)[0].text, "a\n\nb");
}

#[test]
fn raw_inline_markdown_has_utf16_entities() {
    let blocks = render_blocks("👋 **bold __italic__**", vec![], true);
    assert_eq!(blocks[0].text, "👋 bold italic");
    assert!(
        blocks[0]
            .entities
            .iter()
            .any(|entity| entity.kind == "bold" && entity.offset == 3)
    );
    assert!(
        blocks[0]
            .entities
            .iter()
            .any(|entity| entity.kind == "italic" && entity.offset == 8)
    );
}

#[test]
fn raw_structures_use_tree_sitter() {
    let blocks = render_blocks(
        "# Title\n\n```rust\nlet x = 1;\n```\n\n| A | B |\n| --- | --- |\n| one | two |\n\n---\n",
        vec![],
        true,
    );
    assert_eq!(
        blocks
            .iter()
            .map(|block| block.kind.as_str())
            .collect::<Vec<_>>(),
        ["heading", "code", "table", "rule"]
    );
    assert_eq!(blocks[1].language.as_deref(), Some("rust"));
    assert_eq!(blocks[1].text, "let x = 1;");
    assert_eq!(blocks[2].headers, ["A", "B"]);
    assert_eq!(blocks[2].rows, [vec!["one", "two"]]);
}

#[test]
fn html_inline_and_table_render_as_markup() {
    let inline = render_blocks(
        "<p><strong>Bold</strong> and <a href=\"https://example.com\">link</a><br>next</p>",
        vec![],
        true,
    );
    assert_eq!(inline[0].text, "Bold and link\nnext");
    assert!(
        inline[0]
            .entities
            .iter()
            .any(|entity| entity.kind == "bold")
    );
    assert!(
        inline[0]
            .entities
            .iter()
            .any(|entity| entity.kind == "text_url"
                && entity.extra.as_deref() == Some("https://example.com"))
    );

    let table = render_blocks(
        "<table><tr><th>Name</th><th>Value</th></tr><tr><td>A</td><td>1</td></tr></table>",
        vec![],
        true,
    );
    assert_eq!(table[0].kind, "table");
    assert_eq!(table[0].headers, ["Name", "Value"]);
    assert_eq!(table[0].rows, [vec!["A", "1"]]);
}

#[test]
fn lists_and_rules_render_readable_markers() {
    let blocks = render_blocks(
        "- one\n  - nested\n1. first\n2. second\n[x] done\n[ ] todo\n\n---\n",
        vec![],
        true,
    );
    let text = blocks
        .iter()
        .filter_map(|block| (block.kind == "paragraph").then_some(block.text.as_str()))
        .collect::<Vec<_>>()
        .join("\n");
    assert!(text.contains("• one"));
    assert!(text.contains("1. first"));
    assert!(text.contains("☑ done"));
    assert!(text.contains("☐ todo"));
    assert!(blocks.iter().any(|block| block.kind == "rule"));
}

#[test]
fn rules_after_heading_are_not_literal_text() {
    let blocks = render_blocks("## Horizontal Rules\n---\n---\n## Next", vec![], true);
    assert!(blocks.iter().any(|block| block.kind == "rule"));
    assert!(
        !blocks
            .iter()
            .any(|block| block.kind == "paragraph" && block.text.trim() == "---")
    );
}

#[test]
fn server_table_pre_stays_code() {
    let text = "| Header One | Header Two |\n| Lorem | Ipsum |\n| Sit | Amet |";
    let blocks = render_blocks(
        text,
        vec![MarkupEntityDto {
            kind: "pre".into(),
            offset: 0,
            length: utf16_len(text) as i32,
            extra: Some("table".into()),
        }],
        true,
    );
    assert_eq!(blocks[0].kind, "code");
    assert_eq!(blocks[0].text, text);
}

#[test]
fn html_lists_and_headings_keep_structure() {
    let blocks = render_blocks(
        "<h2>Tables</h2><ul><li>One</li><li>Two</li></ul>",
        vec![],
        true,
    );
    assert_eq!(blocks[0].kind, "heading");
    assert_eq!(blocks[0].text, "Tables");
    assert!(
        blocks
            .iter()
            .any(|block| block.text.contains("• One") || block.text.contains("- One"))
    );
}

#[test]
fn html_details_preserve_summary_and_body() {
    let blocks = render_blocks(
        "<details><summary>Click to expand</summary><p>Hidden body</p></details>",
        vec![],
        true,
    );
    assert_eq!(blocks[0].kind, "details");
    assert_eq!(blocks[0].text, "Click to expand\nHidden body");
}

#[test]
fn html_structure_survives_adjacent_server_entity() {
    let blocks = render_blocks(
        "<details><summary>More</summary><p>Body</p></details>",
        vec![MarkupEntityDto {
            kind: "bold".into(),
            offset: 0,
            length: 1,
            extra: None,
        }],
        true,
    );
    assert_eq!(blocks[0].kind, "details");
    assert_eq!(blocks[0].text, "More\nBody");
}

#[test]
fn rich_details_entity_becomes_collapsible_block() {
    let blocks = render_blocks(
        "Summary\nHidden body",
        vec![MarkupEntityDto {
            kind: "details".into(),
            offset: 0,
            length: 19,
            extra: None,
        }],
        true,
    );
    assert_eq!(blocks[0].kind, "details");
    assert_eq!(blocks[0].text, "Summary\nHidden body");
}

#[test]
fn oversized_input_falls_back_without_losing_text() {
    let raw = "*".repeat(MAX_PARSE_BYTES + 1);
    assert_eq!(render_blocks(&raw, vec![], true)[0].text, raw);
}

#[test]
fn math_source_does_not_become_markdown_emphasis() {
    let blocks = render_blocks("$x_i + y_j$ and **bold**", vec![], true);
    assert_eq!(blocks[0].text, "$x_i + y_j$ and bold");
    assert_eq!(blocks[0].entities.len(), 1);
    assert_eq!(blocks[0].entities[0].kind, "bold");
}

#[test]
fn render_handles_empty_input() {
    let blocks = render_blocks("", vec![], true);
    assert!(blocks.is_empty());
    let blocks_with_entities = render_blocks(
        "",
        vec![MarkupEntityDto {
            kind: "bold".into(),
            offset: 0,
            length: 5,
            extra: None,
        }],
        false,
    );
    assert!(blocks_with_entities.is_empty());
}

#[test]
fn render_handles_invalid_spans() {
    let invalid_entities = vec![
        MarkupEntityDto {
            kind: "bold".into(),
            offset: -5,
            length: 3,
            extra: None,
        },
        MarkupEntityDto {
            kind: "italic".into(),
            offset: 100,
            length: 10,
            extra: None,
        },
        MarkupEntityDto {
            kind: "code".into(),
            offset: 2,
            length: 50,
            extra: None,
        },
    ];
    let blocks = render_blocks("hello world", invalid_entities, false);
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].text, "hello world");
    assert!(blocks[0].entities.is_empty());
}

#[test]
fn photo_entity_is_a_photo_block() {
    let text = "before\u{FFFC}\nafter";
    let blocks = render_blocks(
        text,
        vec![MarkupEntityDto {
            kind: "photo".into(),
            offset: utf16_len("before") as i32,
            length: 1,
            extra: Some("photo:99:300x200".into()),
        }],
        false,
    );
    let kinds: Vec<_> = blocks.iter().map(|b| b.kind.as_str()).collect();
    assert_eq!(kinds, ["paragraph", "photo", "paragraph"]);
    assert_eq!(blocks[1].language.as_deref(), Some("photo:99:300x200"));
    assert!(!blocks.iter().any(|b| b.text.contains('\u{FFFC}')));
}

#[test]
fn unicode_checklist_lines_stay_literal() {
    let text = "Tasks\n\u{2611} Milk\n\u{2610} Eggs";
    let blocks = render_blocks(
        text,
        vec![MarkupEntityDto {
            kind: "heading".into(),
            offset: 0,
            length: utf16_len("Tasks") as i32,
            extra: Some("2".into()),
        }],
        false,
    );
    assert_eq!(blocks[0].kind, "heading");
    assert_eq!(blocks[1].kind, "paragraph");
    assert_eq!(blocks[1].text, "\u{2611} Milk\n\u{2610} Eggs");
}
