use crate::utf16::utf16_len;
use crate::{MarkupBlockDto, MarkupEntityDto};

use super::block;
use super::markdown_blocks::{is_rule_line, parse_loose_pipe_table};

fn byte_at_utf16(text: &str, offset: i32) -> Option<usize> {
    if offset < 0 {
        return None;
    }
    let mut units = 0;
    for (byte, ch) in text.char_indices() {
        if units == offset {
            return Some(byte);
        }
        units += ch.len_utf16() as i32;
        if units > offset {
            return None;
        }
    }
    (units == offset).then_some(text.len())
}

fn slice_entities(entities: &[MarkupEntityDto], start: i32, end: i32) -> Vec<MarkupEntityDto> {
    entities
        .iter()
        .filter_map(|entity| {
            let from = entity.offset.max(start);
            let to = entity.offset.checked_add(entity.length)?.min(end);
            (entity.offset >= 0 && entity.length > 0 && from < to).then(|| MarkupEntityDto {
                kind: entity.kind.clone(),
                offset: from - start,
                length: to - from,
                extra: entity.extra.clone(),
            })
        })
        .collect()
}

pub(super) fn entity_blocks(text: &str, entities: &[MarkupEntityDto]) -> Vec<MarkupBlockDto> {
    let length = utf16_len(text);
    let valid: Vec<_> = entities
        .iter()
        .filter(|entity| {
            entity.length > 0
                && byte_at_utf16(text, entity.offset).is_some()
                && entity
                    .offset
                    .checked_add(entity.length)
                    .and_then(|end| byte_at_utf16(text, end))
                    .is_some()
        })
        .cloned()
        .collect();
    let mut structures: Vec<_> = valid
        .iter()
        .filter(|entity| {
            matches!(
                entity.kind.as_str(),
                "pre" | "blockquote" | "heading" | "details" | "rule" | "task" | "photo"
            )
        })
        .collect();
    structures.sort_by_key(|entity| (entity.offset, if entity.kind == "pre" { 0 } else { 1 }));
    if structures.is_empty() {
        return vec![block("paragraph", text.into(), valid)];
    }
    let mut blocks = Vec::new();
    let mut cursor = 0;
    for entity in structures {
        if entity.offset < cursor {
            continue;
        }
        let end = entity.offset + entity.length;
        if cursor < entity.offset {
            push_entity_paragraph(text, &valid, cursor, entity.offset, &mut blocks);
        }
        let kind = match entity.kind.as_str() {
            "pre" => "code",
            "blockquote" => "quote",
            "details" => "details",
            "rule" => "rule",
            "task" => "tasks",
            "photo" => "photo",
            _ => "heading",
        };
        let source =
            &text[byte_at_utf16(text, entity.offset).unwrap()..byte_at_utf16(text, end).unwrap()];
        if kind == "photo" {
            let mut photo = block("photo", String::new(), Vec::new());
            photo.language = entity.extra.clone();
            blocks.push(photo);
            cursor = end;
            continue;
        }
        if kind == "rule" {
            blocks.push(block("rule", String::new(), Vec::new()));
            cursor = end;
            continue;
        }
        if kind == "details" {
            let split = source.find('\n');
            let (title, body) = split
                .map(|at| (&source[..at], &source[at + 1..]))
                .unwrap_or((source, ""));
            blocks.push(MarkupBlockDto {
                kind: kind.into(),
                text: format!("{title}\n{body}"),
                entities: Vec::new(),
                language: None,
                level: 0,
                headers: Vec::new(),
                rows: Vec::new(),
            });
            // Details children are carried by the DTO text and mapped in Kotlin.
            // Keep the block range atomic so nested structural entities cannot
            // render after the collapsed section.
            cursor = end;
            continue;
        }
        let mut current = block(
            kind,
            source.into(),
            slice_entities(&valid, entity.offset, end),
        );
        if kind == "code" {
            let source = &current.text;
            let language = entity
                .extra
                .as_deref()
                .unwrap_or("")
                .trim()
                .to_ascii_lowercase();
            if language == "table" || language == "markdown-table" {
                if let Some(table) = parse_loose_pipe_table(source) {
                    blocks.push(table);
                    cursor = end;
                    continue;
                }
            }
            current.language = entity.extra.clone();
            current.entities.clear();
        }
        if kind == "heading" {
            current.level = entity
                .extra
                .as_deref()
                .and_then(|value| value.parse().ok())
                .unwrap_or(1)
                .clamp(1, 6);
        }
        if kind == "quote" {
            // Ranges covering the block are its own levels (duplicates = deeper
            // levels); strictly inner quotes stay nested regions.
            let span_end = entity.offset + entity.length;
            let levels = valid
                .iter()
                .filter(|inner| {
                    inner.kind == "blockquote"
                        && inner.offset <= entity.offset
                        && inner.offset + inner.length >= span_end
                })
                .count();
            current.level = levels.max(1) as i32;
            current.language = entity.extra.clone();
            let text_len = utf16_len(&current.text);
            current.entities.retain(|inner| {
                inner.kind != "blockquote"
                    || !(inner.offset <= 0 && inner.offset + inner.length >= text_len)
            });
        }
        blocks.push(current);
        cursor = end;
    }
    if cursor < length {
        push_entity_paragraph(text, &valid, cursor, length, &mut blocks);
    }
    blocks
}

fn push_entity_paragraph(
    text: &str,
    entities: &[MarkupEntityDto],
    start: i32,
    end: i32,
    blocks: &mut Vec<MarkupBlockDto>,
) {
    let raw = &text[byte_at_utf16(text, start).unwrap()..byte_at_utf16(text, end).unwrap()];
    let content = raw.trim_matches(['\r', '\n']);
    if content.trim().is_empty() {
        return;
    }
    let leading = raw.len() - raw.trim_start_matches(['\r', '\n']).len();
    let from = start + utf16_len(&raw[..leading]);
    if content.contains('\u{FFFC}') {
        let mut cursor = from;
        let mut rest = content;
        while let Some(idx) = rest.find('\u{FFFC}') {
            let before = &rest[..idx];
            if !before.trim().is_empty() {
                push_plain_gap(before, entities, cursor, blocks);
            }
            cursor += utf16_len(before);
            let extra = entities
                .iter()
                .find(|item| {
                    item.kind == "photo"
                        && item.offset <= cursor
                        && item.offset + item.length >= cursor + 1
                })
                .or_else(|| entities.iter().find(|item| item.kind == "photo"))
                .and_then(|item| item.extra.clone());
            let mut photo = block("photo", String::new(), Vec::new());
            photo.language = extra;
            blocks.push(photo);
            cursor += 1;
            rest = &rest[idx + '\u{FFFC}'.len_utf8()..];
            if rest.starts_with('\n') {
                rest = &rest[1..];
                cursor += 1;
            }
        }
        if !rest.trim().is_empty() {
            push_plain_gap(rest, entities, cursor, blocks);
        }
        return;
    }
    push_plain_gap(content, entities, from, blocks);
}

fn unicode_task_line(line: &str) -> Option<(bool, String)> {
    let trimmed = line
        .trim_start()
        .replace('\u{fe0f}', "")
        .replace('\u{fe0e}', "");
    let mut chars = trimmed.chars();
    let mark = chars.next()?;
    let rest = chars.as_str().trim().to_string();
    match mark {
        '\u{2611}' | '\u{2705}' | '\u{2714}' | '\u{2612}' => Some((true, rest)),
        '\u{2610}' | '\u{25A1}' | '\u{25A2}' | '\u{25CB}' => Some((false, rest)),
        _ => None,
    }
}

fn push_plain_gap(
    content: &str,
    entities: &[MarkupEntityDto],
    from: i32,
    blocks: &mut Vec<MarkupBlockDto>,
) {
    let lines: Vec<&str> = content.lines().collect();
    let mut i = 0;
    let mut utf16 = from;
    while i < lines.len() {
        if let Some((done, item)) = unicode_task_line(lines[i]) {
            let mut rows = vec![vec![if done { "1".into() } else { "0".into() }, item]];
            utf16 += utf16_len(lines[i]) + 1;
            i += 1;
            while i < lines.len() {
                let Some((done, item)) = unicode_task_line(lines[i]) else {
                    break;
                };
                rows.push(vec![if done { "1".into() } else { "0".into() }, item]);
                utf16 += utf16_len(lines[i]) + 1;
                i += 1;
            }
            let text = rows
                .iter()
                .map(|row| row.get(1).cloned().unwrap_or_default())
                .collect::<Vec<_>>()
                .join("\n");
            blocks.push(MarkupBlockDto {
                kind: "tasks".into(),
                text,
                entities: Vec::new(),
                language: None,
                level: 0,
                headers: Vec::new(),
                rows,
            });
            continue;
        }
        if is_rule_line(lines[i].trim()) {
            blocks.push(block("rule", String::new(), Vec::new()));
            utf16 += utf16_len(lines[i]) + 1;
            i += 1;
            continue;
        }
        let para_from = utf16;
        let mut paragraph = String::new();
        while i < lines.len()
            && unicode_task_line(lines[i]).is_none()
            && !is_rule_line(lines[i].trim())
        {
            if !paragraph.is_empty() {
                paragraph.push('\n');
            }
            paragraph.push_str(lines[i]);
            utf16 += utf16_len(lines[i]) + 1;
            i += 1;
        }
        if !paragraph.trim().is_empty() {
            let cleaned = paragraph.replace('\u{FFFC}', "");
            blocks.push(block(
                "paragraph",
                cleaned.clone(),
                slice_entities(entities, para_from, para_from + utf16_len(&cleaned)),
            ));
        }
    }
}
