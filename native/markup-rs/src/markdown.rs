//! Telegram Markdown/HTML → plain text + MessageEntity.
//! Docs: https://core.telegram.org/api/entities

use crate::utf16::{rtrim_utf16_len, OutBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MarkupEntity {
    pub kind: String,
    pub offset: i32,
    pub length: i32,
    pub extra: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StyledMarkup {
    pub text: String,
    pub entities: Vec<MarkupEntity>,
}

pub fn parse_telegram_markdown(raw: &str) -> StyledMarkup {
    if raw.is_empty() {
        return StyledMarkup {
            text: String::new(),
            entities: Vec::new(),
        };
    }
    let mut out = OutBuf::with_capacity(raw.len());
    let mut entities = Vec::new();
    parse_markdown(raw, 0, raw.len(), &mut out, &mut entities, true);
    let code: Vec<_> = entities
        .iter()
        .filter(|e| e.kind == "code" || e.kind == "pre")
        .cloned()
        .collect();
    entities = entities
        .into_iter()
        .flat_map(|entity| {
            if entity.kind == "code" || entity.kind == "pre" {
                return vec![entity];
            }
            let mut parts = vec![entity];
            for opaque in &code {
                parts = parts
                    .into_iter()
                    .flat_map(|part| {
                        let end = part.offset + part.length;
                        let opaque_end = opaque.offset + opaque.length;
                        if end <= opaque.offset || part.offset >= opaque_end {
                            return vec![part];
                        }
                        let mut result = Vec::new();
                        if part.offset < opaque.offset {
                            let mut before = part.clone();
                            before.length = opaque.offset - part.offset;
                            result.push(before);
                        }
                        if end > opaque_end {
                            let mut after = part;
                            after.offset = opaque_end;
                            after.length = end - opaque_end;
                            result.push(after);
                        }
                        result
                    })
                    .collect();
            }
            parts
        })
        .collect();
    entities.sort_by(|a, b| a.offset.cmp(&b.offset).then(b.length.cmp(&a.length)));
    StyledMarkup {
        text: out.text,
        entities,
    }
}

fn parse_markdown(
    raw: &str,
    from: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
    allow_blocks: bool,
) {
    let mut i = from;
    while i < to {
        if allow_blocks && at_line_start(raw, i, from) {
            if raw[i..to].starts_with("```") {
                if let Some(next) = emit_fence(raw, i, to, out, entities) {
                    i = next;
                    continue;
                }
            }
            if quote_depth(&raw[i..to]) > 0 {
                i = emit_quote_run(raw, i, to, from, out, entities);
                continue;
            }
        }
        if let Some(next) = emit_inline(raw, i, to, out, entities) {
            i = next;
        } else {
            let ch = raw[i..].chars().next().unwrap();
            out.push_char(ch);
            i += ch.len_utf8();
        }
    }
}

fn at_line_start(raw: &str, i: usize, from: usize) -> bool {
    i == from || raw.as_bytes().get(i.wrapping_sub(1)) == Some(&b'\n')
}

fn line_end(raw: &str, i: usize, to: usize) -> usize {
    raw[i..to].find('\n').map(|n| i + n).unwrap_or(to)
}

fn quote_depth(rest: &str) -> usize {
    strip_quote_prefix(rest.split('\n').next().unwrap_or(rest)).0
}

fn strip_quote_prefix(line: &str) -> (usize, bool, usize) {
    if let Some(rest) = line.strip_prefix("**>") {
        let rest = rest.strip_prefix(' ').unwrap_or(rest);
        return (1, true, line.len() - rest.len());
    }
    let mut s = line;
    let mut depth = 0usize;
    while let Some(rest) = s.strip_prefix('>') {
        depth += 1;
        s = rest.strip_prefix(' ').unwrap_or(rest);
    }
    (depth, false, line.len() - s.len())
}

fn emit_quote_run(
    raw: &str,
    mut i: usize,
    to: usize,
    from: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> usize {
    struct Line {
        collapsed: bool,
        start: usize,
        end: usize,
    }
    let mut lines = Vec::new();
    while i < to && at_line_start(raw, i, from) {
        let end = line_end(raw, i, to);
        let line = &raw[i..end];
        let (depth, collapsed, body_off) = strip_quote_prefix(line);
        if depth == 0 {
            break;
        }
        lines.push(Line {
            collapsed,
            start: i + body_off,
            end,
        });
        i = if end < to { end + 1 } else { to };
    }
    if lines.is_empty() {
        return i;
    }
    let start = out.utf16_len();
    let collapsed = lines.iter().any(|line| line.collapsed);
    for (idx, line) in lines.iter().enumerate() {
        if idx > 0 {
            out.push_char('\n');
        }
        parse_markdown(raw, line.start, line.end, out, entities, false);
    }
    let ranges = vec![(start, out.utf16_len(), collapsed)];
    for (start, end, collapsed) in ranges {
        let slice_start = utf16_to_byte(&out.text, start);
        let slice_end = utf16_to_byte(&out.text, end);
        let len = rtrim_utf16_len(&out.text[slice_start..slice_end]);
        if len > 0 {
            entities.push(MarkupEntity {
                kind: "blockquote".into(),
                offset: start,
                length: len,
                extra: collapsed.then(|| "collapsed".to_string()),
            });
        }
    }
    if i > 0 && raw.as_bytes().get(i.wrapping_sub(1)) == Some(&b'\n') {
        out.push_char('\n');
    }
    i
}

fn utf16_to_byte(text: &str, offset: i32) -> usize {
    let mut units = 0i32;
    for (byte, ch) in text.char_indices() {
        if units == offset {
            return byte;
        }
        units += ch.len_utf16() as i32;
        if units > offset {
            return byte;
        }
    }
    text.len()
}

fn emit_fence(
    raw: &str,
    start: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let after_ticks = start + 3;
    let line_end = raw[after_ticks..to]
        .find('\n')
        .map(|n| after_ticks + n)
        .unwrap_or(to);
    let lang = raw[after_ticks..line_end].trim();
    let lang = if lang.is_empty() {
        None
    } else {
        Some(lang.to_string())
    };
    let body_start = if line_end < to { line_end + 1 } else { to };
    let close = raw[body_start..to].find("```").map(|n| body_start + n)?;
    let mut body_end = close;
    if body_end > body_start && raw.as_bytes()[body_end - 1] == b'\n' {
        body_end -= 1;
    }
    let body = &raw[body_start..body_end];
    let mark = out.utf16_len();
    out.push_str(body);
    let len = rtrim_utf16_len(body);
    if len > 0 {
        entities.push(MarkupEntity {
            kind: "pre".into(),
            offset: mark,
            length: len,
            extra: lang,
        });
    }
    let mut i = (close + 3).min(to);
    if i < to && raw.as_bytes()[i] == b'\n' {
        out.push_char('\n');
        i += 1;
    }
    Some(i)
}

fn emit_inline(
    raw: &str,
    start: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let rest = &raw[start..to];
    if rest.starts_with('$') {
        let delimiter = if rest.starts_with("$$") { "$$" } else { "$" };
        if let Some(close) = crate::math::find_closing(&rest[delimiter.len()..], delimiter) {
            let end = delimiter.len() + close + delimiter.len();
            out.push_str(&rest[..end]);
            return Some(start + end);
        }
    }
    if let Some(escaped) = rest.strip_prefix('\\').and_then(|tail| tail.chars().next()) {
        if "\\`*_~|[]()!#$>".contains(escaped) {
            out.push_char(escaped);
            return Some(start + 1 + escaped.len_utf8());
        }
    }
    if rest.starts_with("**") {
        return emit_wrap(raw, start, to, "**", "bold", out, entities);
    }
    if rest.starts_with("__") {
        return emit_wrap(raw, start, to, "__", "italic", out, entities);
    }
    if rest.starts_with("~~") {
        return emit_wrap(raw, start, to, "~~", "strike", out, entities);
    }
    if rest.starts_with("||") {
        return emit_wrap(raw, start, to, "||", "spoiler", out, entities);
    }
    if rest.starts_with('`') {
        return emit_wrap(raw, start, to, "`", "code", out, entities);
    }
    if rest.starts_with('!') && rest.len() > 1 && rest.as_bytes()[1] == b'[' {
        return emit_custom_emoji(raw, start, to, out, entities);
    }
    if rest.starts_with('[') {
        return emit_link(raw, start, to, out, entities);
    }

    None
}

fn find_close(raw: &str, from: usize, to: usize, delim: &str) -> Option<usize> {
    let mut i = from;
    while i < to {
        if raw[i..to].starts_with('\\') {
            i += 1;
            if i < to {
                i += raw[i..to].chars().next()?.len_utf8();
            }
            continue;
        }
        if raw[i..to].starts_with(delim) {
            return Some(i);
        }
        if delim != "`" && raw[i..to].starts_with('`') {
            if let Some(close) = raw[i + 1..to].find('`') {
                i += close + 2;
                continue;
            }
        }
        i += raw[i..to].chars().next()?.len_utf8();
    }
    None
}

fn emit_wrap(
    raw: &str,
    start: usize,
    to: usize,
    delim: &str,
    kind: &str,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let inner_from = start + delim.len();
    if inner_from >= to {
        return None;
    }
    let end = find_close(raw, inner_from, to, delim)?;
    if end == inner_from {
        return None;
    }
    let mark = out.utf16_len();
    let body_at = out.text.len();
    if kind != "code" {
        parse_markdown(raw, inner_from, end, out, entities, false);
    } else {
        out.push_str(&raw[inner_from..end]);
    }
    let body = &out.text[body_at..];
    let len = rtrim_utf16_len(body);
    if len > 0 {
        entities.push(MarkupEntity {
            kind: kind.into(),
            offset: mark,
            length: len,
            extra: None,
        });
    }
    Some(end + delim.len())
}

fn emit_link(
    raw: &str,
    start: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let label_end = raw[start + 1..to].find(']').map(|n| start + 1 + n)?;
    if label_end + 1 >= to || raw.as_bytes()[label_end + 1] != b'(' {
        return None;
    }
    let url_start = label_end + 2;
    let url_end = raw[url_start..to].find(')').map(|n| url_start + n)?;
    if label_end <= start + 1 {
        return None;
    }
    let url = raw[url_start..url_end].to_string();
    let kind = if url.starts_with("tg://emoji?id=") {
        "custom_emoji"
    } else {
        "text_url"
    };
    let extra = if kind == "custom_emoji" {
        url.strip_prefix("tg://emoji?id=").map(|s| s.to_string())
    } else {
        Some(url)
    };
    let mark = out.utf16_len();
    let body_at = out.text.len();
    parse_markdown(raw, start + 1, label_end, out, entities, false);
    let body = &out.text[body_at..];
    let len = rtrim_utf16_len(body);
    if len > 0 {
        entities.push(MarkupEntity {
            kind: kind.into(),
            offset: mark,
            length: len,
            extra,
        });
    }
    Some(url_end + 1)
}

fn emit_custom_emoji(
    raw: &str,
    start: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    emit_link(raw, start + 1, to, out, entities)
}

#[cfg(test)]
#[path = "../tests/unit/markdown_block_tests.rs"]
mod block_tests;
#[cfg(test)]
#[path = "../tests/unit/markdown_inline_tests.rs"]
mod inline_tests;
