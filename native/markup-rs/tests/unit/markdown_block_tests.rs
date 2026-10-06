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
        ("# Heading\nbody", "# Heading\nbody"),
        ("> quote\nbody", "quote\nbody"),
        ("```rust\ncode\n```\nbody", "code\nbody"),
    ] {
        assert_eq!(parse_telegram_markdown(raw).text, expected);
    }
}

#[test]
fn nested_quote_markers_emit_one_region() {
    let styled = parse_telegram_markdown("> outer\n>> middle\n>>> inner\n> tail");
    assert_eq!(styled.text, "outer\nmiddle\ninner\ntail");
    let quotes: Vec<_> = styled
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(quotes.len(), 1);
    assert_eq!(quotes[0].offset, 0);
    assert_eq!(
        utf16_slice(&styled.text, quotes[0].offset, quotes[0].length),
        "outer\nmiddle\ninner\ntail"
    );
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
    assert_eq!(quotes.len(), 1, "{quotes:?}");
    assert_eq!((quotes[0].offset, quotes[0].length), (0, 3));
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
fn html_tags_are_literal_in_composer() {
    for raw in [
        "<blockquote expandable>hidden</blockquote>",
        "**bold** and <pre>x</pre>",
    ] {
        let styled = parse_telegram_markdown(raw);
        assert!(!styled
            .entities
            .iter()
            .any(|e| e.kind == "blockquote" || e.kind == "pre"));
        assert!(styled.text.contains('<'));
    }
}

#[test]
fn task_list_markers_are_literal() {
    let raw = "- [ ] open\n- [x] done";
    let styled = parse_telegram_markdown(raw);
    assert_eq!(styled.text, raw);
    assert!(styled.entities.is_empty());
}
