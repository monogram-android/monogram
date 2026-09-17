//! Client-side `$...$` / `$$...$$` spans. Not a Telegram TL type.

use crate::utf16::byte_to_utf16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MathSpan {
    pub start: i32,
    pub end: i32,
    pub display: bool,
    pub source: String,
}

pub fn extract_math(raw: &str) -> Vec<MathSpan> {
    let mut spans = Vec::new();
    let bytes = raw.as_bytes();
    let mut i = 0;
    let mut in_fence = false;
    let mut inline_code_ticks = 0;
    while i < bytes.len() {
        if !raw.is_char_boundary(i) {
            i += 1;
            continue;
        }
        if bytes[i] == b'\\' {
            i += 1;
            if i < bytes.len() {
                i += raw[i..].chars().next().unwrap().len_utf8();
            }
            continue;
        }
        if inline_code_ticks == 0 && raw[i..].starts_with("```") {
            in_fence = !in_fence;
            i += 3;
            continue;
        }
        if in_fence {
            i += 1;
            continue;
        }
        if bytes[i] == b'`' {
            let ticks = bytes[i..].iter().take_while(|byte| **byte == b'`').count();
            if inline_code_ticks == 0 {
                inline_code_ticks = ticks;
            } else if inline_code_ticks == ticks {
                inline_code_ticks = 0;
            }
            i += ticks;
            continue;
        }
        if inline_code_ticks != 0 {
            i += 1;
            continue;
        }
        if raw[i..].starts_with("$$") {
            if let Some(end) = find_closing(&raw[i + 2..], "$$") {
                let inner = &raw[i + 2..i + 2 + end];
                if !inner.trim().is_empty() {
                    spans.push(MathSpan {
                        start: byte_to_utf16(raw, i),
                        end: byte_to_utf16(raw, i + 2 + end + 2),
                        display: true,
                        source: inner.to_string(),
                    });
                    i += 2 + end + 2;
                    continue;
                }
            }
            i += 2;
            continue;
        }
        if bytes[i] == b'$' {
            if let Some(rel) = find_closing(&raw[i + 1..], "$") {
                let inner = &raw[i + 1..i + 1 + rel];
                if !inner.is_empty() && !inner.contains('\n') {
                    spans.push(MathSpan {
                        start: byte_to_utf16(raw, i),
                        end: byte_to_utf16(raw, i + 1 + rel + 1),
                        display: false,
                        source: inner.to_string(),
                    });
                    i += 1 + rel + 1;
                    continue;
                }
            }
        }
        i += 1;
    }
    spans
}

pub(crate) fn find_closing(raw: &str, delimiter: &str) -> Option<usize> {
    let mut escaped = false;
    for (offset, ch) in raw.char_indices() {
        if escaped {
            escaped = false;
        } else if ch == '\\' {
            escaped = true;
        } else if raw[offset..].starts_with(delimiter) {
            return Some(offset);
        } else if delimiter == "$" && ch == '\n' {
            return None;
        }
    }
    None
}

#[cfg(test)]
mod tests {
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
}
