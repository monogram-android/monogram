use super::*;

#[test]
fn inline_and_display() {
    let spans = extract_math("see $a+b$ and $$x^2$$");
    assert_eq!(spans.len(), 2);
    assert!(!spans[0].display);
    assert_eq!(spans[0].source, "a+b");
    assert!(spans[1].display);
    assert_eq!(spans[1].source, "x^2");
}

#[test]
fn skips_code_fences() {
    let spans = extract_math("```\n$not$\n```\nand $yes$");
    assert_eq!(spans.len(), 1);
    assert_eq!(spans[0].source, "yes");
}

#[test]
fn escaped_dollars_are_literal() {
    assert!(extract_math(r"\$literal\$").is_empty());
    let spans = extract_math(r"$a\$b$");
    assert_eq!(spans.len(), 1);
    assert_eq!(spans[0].source, r"a\$b");
}

#[test]
fn unmatched_display_delimiter_does_not_become_inline() {
    assert!(extract_math("$$unfinished$").is_empty());
}

#[test]
fn skips_double_backtick_code() {
    let spans = extract_math("``$literal$ `code` `` and $x$");
    assert_eq!(spans.len(), 1);
    assert_eq!(spans[0].source, "x");
}
