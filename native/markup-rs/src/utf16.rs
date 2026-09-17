//! Telegram entity offsets are UTF-16 code units.
//! Docs: https://core.telegram.org/api/entities

pub fn utf16_len(s: &str) -> i32 {
    s.encode_utf16().count() as i32
}

pub fn byte_to_utf16(s: &str, byte_offset: usize) -> i32 {
    let mut end = byte_offset.min(s.len());
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    utf16_len(&s[..end])
}

pub struct OutBuf {
    pub text: String,
    utf16: i32,
}

impl Default for OutBuf {
    fn default() -> Self {
        Self::new()
    }
}

impl OutBuf {
    pub fn new() -> Self {
        Self {
            text: String::new(),
            utf16: 0,
        }
    }

    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            text: String::with_capacity(capacity),
            utf16: 0,
        }
    }

    pub fn utf16_len(&self) -> i32 {
        self.utf16
    }

    pub fn push_str(&mut self, s: &str) {
        self.utf16 += utf16_len(s);
        self.text.push_str(s);
    }

    pub fn push_char(&mut self, c: char) {
        self.utf16 += if c.len_utf16() == 2 { 2 } else { 1 };
        self.text.push(c);
    }
}

/// Entity length must not include trailing whitespace/newlines (rtrim).
/// The following entity offset still includes those units in the text.
pub fn rtrim_utf16_len(slice: &str) -> i32 {
    utf16_len(slice.trim_end_matches(|c: char| c.is_whitespace()))
}

#[cfg(test)]
mod tests {
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
}
