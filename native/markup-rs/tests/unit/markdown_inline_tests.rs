use super::*;

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
fn bold_italic_combinations() {
    let cases: [(&str, &str, &[(&str, i32, i32)]); 7] = [
        ("**__both__**", "both", &[("bold", 0, 4), ("italic", 0, 4)]),
        (
            "__italic **bold** italic__",
            "italic bold italic",
            &[("italic", 0, 18), ("bold", 7, 4)],
        ),
        (
            "**bold __italic__ bold**",
            "bold italic bold",
            &[("bold", 0, 16), ("italic", 5, 6)],
        ),
        (
            "__under **bold**__",
            "under bold",
            &[("italic", 0, 10), ("bold", 6, 4)],
        ),
        (
            "~~strike __italic__~~",
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
            &[("bold", 0, 10), ("italic", 5, 5)],
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

#[test]
fn identifiers_and_single_delimiters_are_literal() {
    for raw in [
        "mediatek,gpio_usage_mapping",
        "foo_bar_baz",
        "*x*",
        "# Title",
        "---",
        "* item",
        "**unclosed",
    ] {
        let styled = parse_telegram_markdown(raw);
        assert_eq!(styled.text, raw);
        assert!(styled.entities.is_empty(), "{raw:?}");
    }
}

#[test]
fn code_is_opaque_inside_style() {
    let styled = parse_telegram_markdown("**a `**b**` c**");
    assert_eq!(styled.text, "a **b** c");
    let code = styled.entities.iter().find(|e| e.kind == "code").unwrap();
    assert!(!styled.entities.iter().any(|e| e.kind != "code"
        && e.offset < code.offset + code.length
        && e.offset + e.length > code.offset));
}
