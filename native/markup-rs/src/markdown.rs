//! Telegram Markdown/HTML → plain text + MessageEntity.
//! Docs: https://core.telegram.org/api/entities
//! Nested blockquotes are overlapping entities (outer contains inner).

use crate::html::parse_html;
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
    if looks_like_html(raw) {
        parse_html(raw, &mut out, &mut entities);
    } else {
        parse_markdown(raw, 0, raw.len(), &mut out, &mut entities, true);
    }
    entities.sort_by(|a, b| a.offset.cmp(&b.offset).then(b.length.cmp(&a.length)));
    StyledMarkup {
        text: out.text,
        entities,
    }
}

fn looks_like_html(raw: &str) -> bool {
    let lower = raw.to_ascii_lowercase();
    lower.contains("<blockquote")
        || lower.contains("<tg-spoiler")
        || lower.contains("<tg-emoji")
        || lower.contains("<strong")
        || lower.contains("<pre")
        || lower.contains("<details")
        || lower.contains("<table")
        || lower.contains("<ul")
        || lower.contains("<ol")
        || lower.contains("<h1")
        || lower.contains("<h2")
        || lower.contains("<h3")
        || (lower.contains("<b>") && lower.contains("</b>"))
        || (lower.contains("<i>") && lower.contains("</i>"))
        || lower.contains("<a href")
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
            if is_rule_line(&raw[i..to]) {
                i = emit_rule(raw, i, to, out, entities);
                continue;
            }
            if let Some(next) = emit_heading(raw, i, to, out, entities) {
                i = next;
                continue;
            }
            if quote_depth(&raw[i..to]) > 0 {
                i = emit_quote_run(raw, i, to, from, out, entities);
                continue;
            }
            if let Some(next) = emit_task_run(raw, i, to, from, out, entities) {
                i = next;
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

fn is_rule_line(rest: &str) -> bool {
    let line = rest.split('\n').next().unwrap_or(rest).trim();
    let compact: String = line.chars().filter(|ch| !ch.is_whitespace()).collect();
    compact.len() >= 3
        && (compact.chars().all(|ch| ch == '-')
            || compact.chars().all(|ch| ch == '*')
            || compact.chars().all(|ch| ch == '_'))
}

fn emit_rule(
    raw: &str,
    i: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> usize {
    let end = line_end(raw, i, to);
    let mark = out.utf16_len();
    out.push_str("---");
    entities.push(MarkupEntity {
        kind: "rule".into(),
        offset: mark,
        length: 3,
        extra: None,
    });
    if end < to {
        out.push_char('\n');
        end + 1
    } else {
        end
    }
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
        depth: usize,
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
            depth,
            collapsed,
            start: i + body_off,
            end,
        });
        i = if end < to { end + 1 } else { to };
    }
    if lines.is_empty() {
        return i;
    }
    // Every depth level emits its own blockquote range, so nesting survives.
    let max_depth = lines.iter().map(|l| l.depth).max().unwrap_or(1);
    let mut line_spans: Vec<(i32, i32, usize, bool)> = Vec::new();
    for (idx, line) in lines.iter().enumerate() {
        if idx > 0 {
            out.push_char('\n');
        }
        let start = out.utf16_len();
        parse_markdown(raw, line.start, line.end, out, entities, false);
        line_spans.push((start, out.utf16_len(), line.depth, line.collapsed));
    }
    let mut ranges: Vec<(i32, i32, bool)> = Vec::new();
    for depth in 1..=max_depth {
        let mut run_start: Option<i32> = None;
        let mut run_end: i32 = 0;
        let mut collapsed = false;
        for (start, end, line_depth, line_collapsed) in &line_spans {
            if *line_depth >= depth {
                if run_start.is_none() {
                    run_start = Some(*start);
                    collapsed = *line_collapsed && depth == 1;
                }
                run_end = *end;
                if *line_collapsed && depth == 1 {
                    collapsed = true;
                }
            } else if let Some(from) = run_start.take() {
                ranges.push((from, run_end, collapsed));
                collapsed = false;
            }
        }
        if let Some(from) = run_start {
            ranges.push((from, run_end, collapsed));
        }
    }
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

fn emit_task_run(
    raw: &str,
    mut i: usize,
    to: usize,
    from: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let mut emitted = false;
    let mut first = true;
    while i < to && at_line_start(raw, i, from) {
        let end = line_end(raw, i, to);
        let line = &raw[i..end];
        let Some((done, body_from)) = task_line(line) else {
            break;
        };
        if !first {
            out.push_char('\n');
        }
        first = false;
        emitted = true;
        let mark = out.utf16_len();
        let body_at = out.text.len();
        parse_markdown(raw, i + body_from, end, out, entities, false);
        let body = &out.text[body_at..];
        let len = rtrim_utf16_len(body);
        if len > 0 {
            entities.push(MarkupEntity {
                kind: "task".into(),
                offset: mark,
                length: len,
                extra: Some(if done { "1".into() } else { "0".into() }),
            });
        }
        i = if end < to { end + 1 } else { to };
    }
    if i > 0 && emitted && raw.as_bytes().get(i.wrapping_sub(1)) == Some(&b'\n') {
        out.push_char('\n');
    }
    emitted.then_some(i)
}

fn task_line(line: &str) -> Option<(bool, usize)> {
    let trimmed = line.trim_start();
    let indent = line.len() - trimmed.len();
    let rest = trimmed
        .strip_prefix("- ")
        .or_else(|| trimmed.strip_prefix("* "))
        .or_else(|| trimmed.strip_prefix("+ "))
        .unwrap_or(trimmed);
    let (done, after) = if let Some(tail) = rest
        .strip_prefix("[x] ")
        .or_else(|| rest.strip_prefix("[X] "))
    {
        (true, tail)
    } else if let Some(tail) = rest.strip_prefix("[ ] ") {
        (false, tail)
    } else {
        return None;
    };
    let body_from = indent + (trimmed.len() - rest.len()) + (rest.len() - after.len());
    Some((done, body_from))
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

fn emit_heading(
    raw: &str,
    start: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let mut hashes = 0usize;
    while start + hashes < to && raw.as_bytes()[start + hashes] == b'#' && hashes < 6 {
        hashes += 1;
    }
    if hashes == 0 || start + hashes >= to || raw.as_bytes()[start + hashes] != b' ' {
        return None;
    }
    let content_start = start + hashes + 1;
    let line_end = raw[start..to].find('\n').map(|n| start + n).unwrap_or(to);
    let mark = out.utf16_len();
    let body_at = out.text.len();
    parse_markdown(raw, content_start, line_end, out, entities, false);
    let body = &out.text[body_at..];
    let len = rtrim_utf16_len(body);
    if len > 0 {
        entities.push(MarkupEntity {
            kind: "heading".into(),
            offset: mark,
            length: len,
            extra: Some(hashes.to_string()),
        });
        entities.push(MarkupEntity {
            kind: "bold".into(),
            offset: mark,
            length: len,
            extra: None,
        });
    }
    let i = if line_end < to {
        out.push_char('\n');
        line_end + 1
    } else {
        to
    };
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
    if rest.starts_with("***") {
        return emit_kinds_wrap(raw, start, to, "***", &["bold", "italic"], out, entities);
    }
    if rest.starts_with("**") {
        return emit_wrap(raw, start, to, "**", "bold", out, entities);
    }
    if rest.starts_with("__") {
        return emit_wrap(raw, start, to, "__", "underline", out, entities);
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
    if rest.starts_with('*') {
        return emit_single_wrap(raw, start, to, '*', "italic", out, entities);
    }
    if rest.starts_with('_') {
        return emit_single_wrap(raw, start, to, '_', "italic", out, entities);
    }
    None
}

/// Closing marker for a single delimiter; `*` inside `**` is not a match.
fn find_single_close(raw: &str, from: usize, to: usize, ch: char) -> Option<usize> {
    let marker = ch as u8;
    let bytes = raw.as_bytes();
    let mut i = from;
    while i < to {
        let c = raw[i..].chars().next()?;
        if c == ch {
            let prev_same = i > from && bytes.get(i - 1) == Some(&marker);
            let next_same = i + 1 < to && bytes.get(i + 1) == Some(&marker);
            if !prev_same && !next_same {
                return Some(i);
            }
        }
        i += c.len_utf8();
    }
    None
}

fn emit_single_wrap(
    raw: &str,
    start: usize,
    to: usize,
    ch: char,
    kind: &str,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let inner_from = start + ch.len_utf8();
    let end = find_single_close(raw, inner_from, to, ch)?;
    if end == inner_from {
        return None;
    }
    let mark = out.utf16_len();
    let body_at = out.text.len();
    parse_markdown(raw, inner_from, end, out, entities, false);
    let len = rtrim_utf16_len(&out.text[body_at..]);
    if len > 0 {
        entities.push(MarkupEntity {
            kind: kind.into(),
            offset: mark,
            length: len,
            extra: None,
        });
    }
    Some(end + ch.len_utf8())
}

fn emit_kinds_wrap(
    raw: &str,
    start: usize,
    to: usize,
    delim: &str,
    kinds: &[&str],
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let inner_from = start + delim.len();
    if inner_from >= to {
        return None;
    }
    let end = raw[inner_from..to].find(delim).map(|n| inner_from + n)?;
    if end == inner_from {
        return None;
    }
    let mark = out.utf16_len();
    let body_at = out.text.len();
    parse_markdown(raw, inner_from, end, out, entities, false);
    let len = rtrim_utf16_len(&out.text[body_at..]);
    if len > 0 {
        for kind in kinds {
            entities.push(MarkupEntity {
                kind: (*kind).into(),
                offset: mark,
                length: len,
                extra: None,
            });
        }
    }
    Some(end + delim.len())
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
    let end = raw[inner_from..to].find(delim).map(|n| inner_from + n)?;
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
#[path = "markdown_tests.rs"]
mod tests;
