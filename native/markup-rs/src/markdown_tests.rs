use super::*;

fn utf16_slice(text: &str, offset: i32, length: i32) -> String {
    String::from_utf16(
        &text
            .encode_utf16()
            .skip(offset as usize)
            .take(length as usize)
            .collect::<Vec<_>>(),
    )
    .unwrap()
}

#[test]
fn block_separators_preserve_following_text() {
    for (raw, expected) in [
        ("# Heading\nbody", "Heading\nbody"),
        ("> quote\nbody", "quote\nbody"),
        ("```rust\ncode\n```\nbody", "code\nbody"),
    ] {
        assert_eq!(parse_telegram_markdown(raw).text, expected);
    }
}

#[test]
fn escaped_markup_remains_literal() {
    let styled = parse_telegram_markdown(r"\*literal\* and \$math\$");
    assert_eq!(styled.text, "*literal* and $math$");
    assert!(styled.entities.is_empty());
}

#[test]
fn bold_strips_markers() {
    let styled = parse_telegram_markdown("**bold**");
    assert_eq!(styled.text, "bold");
    assert_eq!(styled.entities.len(), 1);
    assert_eq!(styled.entities[0].kind, "bold");
    assert_eq!(styled.entities[0].offset, 0);
    assert_eq!(styled.entities[0].length, 4);
}

#[test]
fn rtrim_entity_length() {
    let styled = parse_telegram_markdown("**bold **");
    assert_eq!(styled.text, "bold ");
    assert_eq!(styled.entities[0].length, 4);
}

#[test]
fn emoji_utf16_offsets() {
    let styled = parse_telegram_markdown("👋 **x**");
    assert_eq!(styled.text, "👋 x");
    let bold = styled.entities.iter().find(|e| e.kind == "bold").unwrap();
    assert_eq!(bold.offset, 3);
    assert_eq!(bold.length, 1);
}

#[test]
fn fence_pre_language() {
    let styled = parse_telegram_markdown("```rust\nlet x = 1;\n```");
    assert_eq!(styled.text, "let x = 1;");
    assert_eq!(styled.entities[0].kind, "pre");
    assert_eq!(styled.entities[0].extra.as_deref(), Some("rust"));
}

#[test]
fn spoiler_and_code() {
    let styled = parse_telegram_markdown("||hid|| and `c`");
    let kinds: Vec<_> = styled.entities.iter().map(|e| e.kind.as_str()).collect();
    assert!(kinds.contains(&"spoiler"));
    assert!(kinds.contains(&"code"));
    assert_eq!(styled.text, "hid and c");
}

#[test]
fn custom_emoji_markdown() {
    let styled = parse_telegram_markdown("![👍](tg://emoji?id=42)");
    assert_eq!(styled.text, "👍");
    assert_eq!(styled.entities[0].kind, "custom_emoji");
    assert_eq!(styled.entities[0].extra.as_deref(), Some("42"));
}

#[test]
fn nested_quote_markers_are_overlapping_entities() {
    let styled = parse_telegram_markdown("> outer\n>> middle\n>>> inner\n> tail");
    assert_eq!(styled.text, "outer\nmiddle\ninner\ntail");
    let quotes: Vec<_> = styled
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert!(quotes.len() >= 3, "{quotes:?}");
    let outer = quotes.iter().max_by_key(|e| e.length).unwrap();
    assert_eq!(outer.offset, 0);
    assert!(quotes.iter().any(|e| {
        let slice = utf16_slice(&styled.text, e.offset, e.length);
        slice == "inner" || slice.starts_with("inner")
    }));
}

#[test]
fn quote_markers_are_dropped_from_text() {
    let styled = parse_telegram_markdown(">> two");
    assert_eq!(styled.text, "two");
    let quotes: Vec<_> = styled
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(quotes.len(), 2, "{quotes:?}");
    assert_eq!((quotes[0].offset, quotes[0].length), (0, 3));
    assert_eq!((quotes[1].offset, quotes[1].length), (0, 3));
}

#[test]
fn separated_quote_regions_stay_separate() {
    let styled = parse_telegram_markdown("> one\ntail\n> two");
    assert_eq!(styled.text, "one\ntail\ntwo");
    let quotes: Vec<_> = styled
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(quotes.len(), 2, "{quotes:?}");
}

#[test]
fn html_nested_blockquotes_are_overlapping_entities() {
    let styled = parse_telegram_markdown(
        "<blockquote>outer<blockquote>middle<blockquote>inner</blockquote></blockquote>tail</blockquote>",
    );
    assert_eq!(styled.text, "outermiddleinnertail");
    let quotes: Vec<_> = styled
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(quotes.len(), 3, "{quotes:?}");
    assert!(quotes.iter().any(|e| e.offset == 0 && e.length == 20));
}

#[test]
fn html_expandable_quote() {
    let styled = parse_telegram_markdown("<blockquote expandable>hidden</blockquote>");
    let quote = styled
        .entities
        .iter()
        .find(|e| e.kind == "blockquote")
        .unwrap();
    assert_eq!(quote.extra.as_deref(), Some("collapsed"));
}

#[test]
fn task_list_entities() {
    let styled = parse_telegram_markdown("- [ ] open\n- [x] done");
    assert_eq!(styled.text, "open\ndone");
    let tasks: Vec<_> = styled
        .entities
        .iter()
        .filter(|e| e.kind == "task")
        .collect();
    assert_eq!(tasks.len(), 2);
    assert_eq!(tasks[0].extra.as_deref(), Some("0"));
    assert_eq!(tasks[1].extra.as_deref(), Some("1"));
}

#[test]
fn bold_italic_combinations() {
    let cases: [(&str, &str, &[(&str, i32, i32)]); 7] = [
        ("***both***", "both", &[("bold", 0, 4), ("italic", 0, 4)]),
        (
            "*italic **bold** italic*",
            "italic bold italic",
            &[("italic", 0, 18), ("bold", 7, 4)],
        ),
        (
            "**bold *italic* bold**",
            "bold italic bold",
            &[("bold", 0, 16), ("italic", 5, 6)],
        ),
        (
            "__under **bold**__",
            "under bold",
            &[("underline", 0, 10), ("bold", 6, 4)],
        ),
        (
            "~~strike *italic*~~",
            "strike italic",
            &[("strike", 0, 13), ("italic", 7, 6)],
        ),
        (
            "||spoiler **bold**||",
            "spoiler bold",
            &[("spoiler", 0, 12), ("bold", 8, 4)],
        ),
        (
            "**bold __under__**",
            "bold under",
            &[("bold", 0, 10), ("underline", 5, 5)],
        ),
    ];
    for (raw, text, expected) in cases {
        let styled = parse_telegram_markdown(raw);
        assert_eq!(styled.text, text, "{raw:?}");
        for (kind, offset, length) in expected {
            assert!(
                styled
                    .entities
                    .iter()
                    .any(|e| e.kind == *kind && e.offset == *offset && e.length == *length),
                "{raw:?} missing {kind} {offset} {length}: {:?}",
                styled.entities
            );
        }
    }
}
