use super::*;

#[test]
fn utf16_len_various_inputs() {
    let cases = [("abc", 3), ("👋", 2), ("a👋b", 4)];
    for (input, expected) in cases {
        assert_eq!(utf16_len(input), expected, "Failed for: {input}");
    }
}

#[test]
fn rtrim_drops_trailing_spaces() {
    assert_eq!(rtrim_utf16_len("bold  \n"), 4);
    assert_eq!(rtrim_utf16_len("  x"), 3);
}
