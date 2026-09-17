use crate::markdown::MarkupEntity;
use crate::utf16::{OutBuf, rtrim_utf16_len};

pub(crate) fn parse_html(raw: &str, out: &mut OutBuf, entities: &mut Vec<MarkupEntity>) {
    parse_html_range(raw, 0, raw.len(), out, entities);
}

pub(crate) fn parse_html_range(
    raw: &str,
    from: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) {
    let mut i = from;
    while i < to {
        if raw.as_bytes()[i] == b'<' {
            if let Some(next) = emit_html_tag(raw, i, to, out, entities) {
                i = next;
                continue;
            }
        }
        if raw[i..to].starts_with("&lt;") {
            out.push_char('<');
            i += 4;
            continue;
        }
        if raw[i..to].starts_with("&gt;") {
            out.push_char('>');
            i += 4;
            continue;
        }
        if raw[i..to].starts_with("&amp;") {
            out.push_char('&');
            i += 5;
            continue;
        }
        if raw[i..to].starts_with("&quot;") {
            out.push_char('"');
            i += 6;
            continue;
        }
        if raw[i..to].starts_with("&#39;") || raw[i..to].starts_with("&apos;") {
            out.push_char('\'');
            i += if raw[i..to].starts_with("&#39;") {
                5
            } else {
                6
            };
            continue;
        }
        if raw[i..to].starts_with("&nbsp;") {
            out.push_char(' ');
            i += 6;
            continue;
        }
        let ch = raw[i..].chars().next().unwrap();
        out.push_char(ch);
        i += ch.len_utf8();
    }
}

pub(crate) fn emit_html_tag(
    raw: &str,
    start: usize,
    to: usize,
    out: &mut OutBuf,
    entities: &mut Vec<MarkupEntity>,
) -> Option<usize> {
    let close_rel = raw[start..to].find('>')?;
    let close = start + close_rel;
    let inner = raw[start + 1..close].trim();
    if inner.is_empty() {
        return None;
    }
    let lower = inner.to_ascii_lowercase();
    if lower.starts_with("br") {
        out.push_char('\n');
        return Some(close + 1);
    }
    if lower.starts_with('/') {
        return Some(close + 1);
    }
    let name = lower.split_whitespace().next().unwrap_or(&lower);
    let name = name.trim_end_matches('/');
    if matches!(
        name,
        "p" | "div" | "ul" | "ol" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6"
    ) {
        if !out.text.is_empty() && !out.text.ends_with('\n') {
            out.push_char('\n');
        }
    }
    if name == "li" {
        if !out.text.is_empty() && !out.text.ends_with('\n') {
            out.push_char('\n');
        }
        out.push_str("• ");
    }
    let heading_level = if name.len() == 2 && name.starts_with('h') {
        name.as_bytes()[1]
            .checked_sub(b'0')
            .filter(|d| (1..=6).contains(d))
    } else {
        None
    };
    let (kind, extra) = match name {
        "b" | "strong" => ("bold", None),
        "i" | "em" => ("italic", None),
        "u" => ("underline", None),
        "s" | "strike" | "del" => ("strike", None),
        "code" => ("code", html_pre_lang(inner)),
        "pre" => ("pre", html_pre_lang(inner)),
        "tg-spoiler" => ("spoiler", None),
        "blockquote" => (
            "blockquote",
            lower
                .contains("expandable")
                .then(|| "collapsed".to_string()),
        ),
        "a" => ("text_url", html_attr(inner, "href")),
        "tg-emoji" => ("custom_emoji", html_attr(inner, "emoji-id")),
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            ("heading", heading_level.map(|d| d.to_string()))
        }
        _ => {
            let end = find_close_tag(raw, close + 1, to, name).unwrap_or(to);
            parse_html_range(raw, close + 1, end, out, entities);
            return Some(skip_close_tag(raw, end, to, name));
        }
    };
    let body_end = find_close_tag(raw, close + 1, to, name)?;
    let mark = out.utf16_len();
    let body_at = out.text.len();
    parse_html_range(raw, close + 1, body_end, out, entities);
    let body = &out.text[body_at..];
    let len = rtrim_utf16_len(body);
    if len > 0 {
        entities.push(MarkupEntity {
            kind: kind.into(),
            offset: mark,
            length: len,
            extra,
        });
        if heading_level.is_some() {
            entities.push(MarkupEntity {
                kind: "bold".into(),
                offset: mark,
                length: len,
                extra: None,
            });
        }
        if kind == "pre" {
            if let Some(lang) = entities
                .iter()
                .rev()
                .find(|e| e.kind == "code" && e.offset >= mark && e.offset + e.length <= mark + len)
                .and_then(|e| e.extra.clone())
            {
                if let Some(pre) = entities.last_mut() {
                    if pre.kind == "pre" && pre.extra.is_none() {
                        pre.extra = Some(lang);
                    }
                }
            }
        }
    }
    Some(skip_close_tag(raw, body_end, to, name))
}

fn html_attr(tag: &str, name: &str) -> Option<String> {
    let lower = tag.to_ascii_lowercase();
    let key = format!("{name}=");
    let at = lower.find(&key)?;
    let rest = tag[at + key.len()..].trim_start();
    if rest.starts_with('"') || rest.starts_with('\'') {
        let quote = rest.as_bytes()[0] as char;
        let end = rest[1..].find(quote)?;
        Some(rest[1..1 + end].to_string())
    } else {
        Some(
            rest.split(|ch: char| ch.is_whitespace() || ch == '>')
                .next()
                .unwrap_or("")
                .to_string(),
        )
    }
}

fn html_pre_lang(tag: &str) -> Option<String> {
    html_attr(tag, "class").and_then(|class| {
        class
            .split_whitespace()
            .find_map(|part| part.strip_prefix("language-"))
            .map(|s| s.to_string())
    })
}

fn find_close_tag(raw: &str, from: usize, to: usize, name: &str) -> Option<usize> {
    let needle = format!("</{name}");
    let mut i = from;
    let mut depth = 1i32;
    let open = format!("<{name}");
    while i < to {
        let slice = &raw[i..to];
        let lower = slice.to_ascii_lowercase();
        let next_open = lower.find(&open);
        let next_close = lower.find(&needle);
        match (next_open, next_close) {
            (Some(o), Some(c)) if o < c => {
                if slice[o..].as_bytes().get(open.len()) == Some(&b'/') {
                    i += o + open.len();
                    continue;
                }
                depth += 1;
                i += o + open.len();
            }
            (_, Some(c)) => {
                depth -= 1;
                if depth == 0 {
                    return Some(i + c);
                }
                i += c + needle.len();
            }
            (Some(o), None) => {
                depth += 1;
                i += o + open.len();
            }
            _ => return None,
        }
    }
    None
}

fn skip_close_tag(raw: &str, at: usize, to: usize, name: &str) -> usize {
    let needle = format!("</{name}");
    if raw[at..to].to_ascii_lowercase().starts_with(&needle) {
        raw[at..to].find('>').map(|n| at + n + 1).unwrap_or(to)
    } else {
        at
    }
}
