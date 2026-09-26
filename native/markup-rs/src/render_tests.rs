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
    let blocks = render_blocks("👋 **bold _italic_**", vec![], true);
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
fn server_table_pre_is_rendered_as_table() {
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
    assert_eq!(blocks[0].kind, "table");
    assert_eq!(blocks[0].headers, ["Header One", "Header Two"]);
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
fn nested_blockquote_entities_keep_inner_on_outer_quote() {
    let text = "outer\nmiddle\ninner\ntail";
    let blocks = render_blocks(
        text,
        vec![
            MarkupEntityDto {
                kind: "blockquote".into(),
                offset: 0,
                length: utf16_len(text),
                extra: None,
            },
            MarkupEntityDto {
                kind: "blockquote".into(),
                offset: 6,
                length: 12,
                extra: None,
            },
            MarkupEntityDto {
                kind: "blockquote".into(),
                offset: 13,
                length: 5,
                extra: Some("collapsed".into()),
            },
        ],
        false,
    );
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "quote");
    assert_eq!(blocks[0].level, 1);
    assert_eq!(
        blocks[0]
            .entities
            .iter()
            .filter(|entity| entity.kind == "blockquote")
            .count(),
        2
    );
    assert!(
        blocks[0]
            .entities
            .iter()
            .any(|entity| entity.extra.as_deref() == Some("collapsed"))
    );
}

#[test]
fn markdown_nested_quotes_render() {
    let blocks = render_blocks("> outer\n>> middle\n>>> inner", vec![], true);
    assert_eq!(blocks[0].kind, "quote");
    assert!(
        blocks[0]
            .entities
            .iter()
            .any(|entity| entity.kind == "blockquote")
    );
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
fn unicode_checklist_lines_become_tasks() {
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
    assert_eq!(blocks[1].kind, "tasks");
    assert_eq!(blocks[1].rows, vec![vec!["1", "Milk"], vec!["0", "Eggs"]]);
}

fn dto(kind: &str, offset: i32, length: i32) -> MarkupEntityDto {
    MarkupEntityDto {
        kind: kind.into(),
        offset,
        length,
        extra: None,
    }
}

fn quote_dtos(styled: &crate::markdown::StyledMarkup) -> Vec<MarkupEntityDto> {
    styled
        .entities
        .iter()
        .map(|e| MarkupEntityDto {
            kind: e.kind.clone(),
            offset: e.offset,
            length: e.length,
            extra: e.extra.clone(),
        })
        .collect()
}

#[test]
fn single_incoming_quote_draws_one_level() {
    let blocks = render_blocks("quote", vec![dto("blockquote", 0, 5)], false);
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "quote");
    assert_eq!(blocks[0].level, 1);
    assert!(
        blocks[0].entities.iter().all(|e| e.kind != "blockquote"),
        "{:?}",
        blocks[0].entities
    );
}

#[test]
fn duplicate_identical_ranges_are_deeper_levels() {
    let blocks = render_blocks(
        "two",
        vec![dto("blockquote", 0, 3), dto("blockquote", 0, 3)],
        false,
    );
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "quote");
    assert_eq!(blocks[0].level, 2);
    assert!(blocks[0].entities.iter().all(|e| e.kind != "blockquote"));
}

#[test]
fn inner_quote_stays_a_nested_region() {
    let text = "outer
inner";
    let blocks = render_blocks(
        text,
        vec![dto("blockquote", 0, 11), dto("blockquote", 6, 5)],
        false,
    );
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "quote");
    assert_eq!(blocks[0].level, 1);
    let inner: Vec<_> = blocks[0]
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(inner.len(), 1, "{:?}", blocks[0].entities);
    assert_eq!((inner[0].offset, inner[0].length), (6, 5));
}

#[test]
fn markdown_nesting_becomes_levels_and_regions() {
    for (raw, level, inner) in [
        ("> quote", 1, 0),
        (">> two", 2, 0),
        (">>> three", 3, 0),
        (
            "> outer
>> inner",
            1,
            1,
        ),
        (
            "> outer
>>> inner",
            1,
            2,
        ),
    ] {
        let styled = crate::markdown::parse_telegram_markdown(raw);
        let blocks = render_blocks(&styled.text, quote_dtos(&styled), false);
        assert_eq!(blocks.len(), 1, "{raw:?}");
        assert_eq!(blocks[0].kind, "quote", "{raw:?}");
        assert_eq!(blocks[0].level, level, "{raw:?} {:?}", blocks[0].entities);
        let inner_count = blocks[0]
            .entities
            .iter()
            .filter(|e| e.kind == "blockquote")
            .count();
        assert_eq!(inner_count, inner, "{raw:?} {:?}", blocks[0].entities);
    }
}

#[test]
fn collapsed_quote_keeps_its_flag() {
    let styled = crate::markdown::parse_telegram_markdown("**> hidden text");
    let blocks = render_blocks(&styled.text, quote_dtos(&styled), false);
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "quote");
    assert_eq!(blocks[0].language.as_deref(), Some("collapsed"));
}

#[test]
fn md_path_collapsed_quote_keeps_its_flag() {
    let blocks = render_blocks("**> hidden text", vec![], true);
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, "quote");
    assert_eq!(blocks[0].language.as_deref(), Some("collapsed"));
}

#[test]
fn separated_incoming_quote_regions_stay_separate() {
    let text = "one
tail
two";
    let blocks = render_blocks(
        text,
        vec![dto("blockquote", 0, 3), dto("blockquote", 9, 3)],
        false,
    );
    let kinds: Vec<_> = blocks.iter().map(|b| b.kind.as_str()).collect();
    assert_eq!(kinds, ["quote", "paragraph", "quote"]);
}
