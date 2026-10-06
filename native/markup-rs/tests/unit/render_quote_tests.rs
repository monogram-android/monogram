use super::*;
use crate::utf16::utf16_len;

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
