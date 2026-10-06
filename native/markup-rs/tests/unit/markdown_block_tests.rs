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
