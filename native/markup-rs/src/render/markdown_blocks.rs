use std::cell::RefCell;

use crate::utf16::utf16_len;
use crate::{MarkupBlockDto, MarkupEntityDto};

use super::block;
use super::html::{html_to_markdown, parse_html_details, parse_html_table};
use super::render_blocks;

thread_local! {
    static MD_PARSER: RefCell<Option<tree_sitter::Parser>> = const { RefCell::new(None) };
}

fn parse_markdown_tree(text: &str) -> Option<tree_sitter::Tree> {
    MD_PARSER.with(|cell| {
        let mut opt = cell.borrow_mut();
        if opt.is_none() {
            let mut p = tree_sitter::Parser::new();
            if p.set_language(&tree_sitter_md::LANGUAGE.into()).is_ok() {
                *opt = Some(p);
            }
        }
        opt.as_mut().and_then(|p| p.parse(text, None))
    })
}

pub(super) fn markdown_blocks(text: &str) -> Vec<MarkupBlockDto> {
    let Some(tree) = parse_markdown_tree(text) else {
        return vec![block("paragraph", text.into(), Vec::new())];
    };
    let mut nodes = Vec::new();
    collect_blocks(tree.root_node(), &mut nodes);
    let mut blocks = Vec::new();
    let mut cursor = 0;
    for node in nodes {
        if cursor < node.start_byte() {
            push_paragraph(&text[cursor..node.start_byte()], &mut blocks);
        }
        let raw = &text[node.byte_range()];
        match node.kind() {
            "fenced_code_block" | "indented_code_block" => {
                let mut code = block("code", String::new(), Vec::new());
                let mut walker = node.walk();
                for child in node.named_children(&mut walker) {
                    match child.kind() {
                        "info_string" => {
                            code.language = text[child.byte_range()]
                                .split_whitespace()
                                .next()
                                .map(str::to_owned)
                        }
                        "code_fence_content" => {
                            code.text = text[child.byte_range()]
                                .trim_end_matches('\n')
                                .trim_end_matches('\r')
                                .into()
                        }
                        _ => {}
                    }
                }
                if node.kind() == "indented_code_block" {
                    code.text = raw
                        .lines()
                        .map(|line| {
                            line.strip_prefix("    ")
                                .or_else(|| line.strip_prefix('\t'))
                                .unwrap_or(line)
                        })
                        .collect::<Vec<_>>()
                        .join("\n");
                }
                blocks.push(code);
            }
            "pipe_table" => {
                let mut table = block("table", raw.into(), Vec::new());
                let mut walker = node.walk();
                for row in node.named_children(&mut walker) {
                    if !matches!(row.kind(), "pipe_table_header" | "pipe_table_row") {
                        continue;
                    }
                    let mut cells = row.walk();
                    let values = row
                        .named_children(&mut cells)
                        .filter(|cell| cell.kind() == "pipe_table_cell")
                        .map(|cell| text[cell.byte_range()].trim().to_string())
                        .collect();
                    if row.kind() == "pipe_table_header" {
                        table.headers = values;
                    } else {
                        table.rows.push(values);
                    }
                }
                blocks.push(table);
            }
            "thematic_break" => blocks.push(block("rule", String::new(), Vec::new())),
            "atx_heading" | "setext_heading" => {
                let content = node
                    .child_by_field_name("heading_content")
                    .map(|content| &text[content.byte_range()])
                    .unwrap_or_else(|| {
                        raw.lines()
                            .next()
                            .unwrap_or("")
                            .trim_start_matches('#')
                            .trim()
                    });
                let parsed = crate::parse_telegram_markdown(content.into());
                let mut heading = block("heading", parsed.text, parsed.entities);
                heading.level = if node.kind() == "setext_heading" {
                    if raw
                        .lines()
                        .last()
                        .unwrap_or("")
                        .trim_start()
                        .starts_with('=')
                    {
                        1
                    } else {
                        2
                    }
                } else {
                    raw.chars().take_while(|ch| *ch == '#').count() as i32
                };
                blocks.push(heading);
            }
            "block_quote" => {
                let content = raw
                    .lines()
                    .map(|line| {
                        line.strip_prefix('>')
                            .unwrap_or(line)
                            .strip_prefix(' ')
                            .unwrap_or_else(|| line.strip_prefix('>').unwrap_or(line))
                    })
                    .collect::<Vec<_>>()
                    .join("\n");
                let parsed = crate::parse_telegram_markdown(content);
                blocks.push(quote_block(parsed));
            }
            "html_block" | "html_inline" => {
                if let Some(details) = parse_html_details(raw) {
                    blocks.push(details);
                } else if let Some(table) = parse_html_table(raw) {
                    blocks.push(table);
                } else {
                    blocks.extend(render_blocks(&html_to_markdown(raw), Vec::new(), true));
                }
            }
            _ => push_paragraph(raw, &mut blocks),
        }
        cursor = node.end_byte();
    }
    if cursor < text.len() {
        push_paragraph(&text[cursor..], &mut blocks);
    }
    blocks
}

fn collect_blocks<'tree>(
    node: tree_sitter::Node<'tree>,
    blocks: &mut Vec<tree_sitter::Node<'tree>>,
) {
    match node.kind() {
        "fenced_code_block"
        | "indented_code_block"
        | "pipe_table"
        | "thematic_break"
        | "atx_heading"
        | "setext_heading"
        | "block_quote"
        | "paragraph"
        | "html_block" => blocks.push(node),
        "document" | "section" => {
            let mut cursor = node.walk();
            for child in node.named_children(&mut cursor) {
                collect_blocks(child, blocks);
            }
        }
        _ => blocks.push(node),
    }
}

/// Covering quote ranges are the block's levels, inner ones are nested regions.
fn quote_block(parsed: crate::StyledMarkupDto) -> MarkupBlockDto {
    let full = utf16_len(&parsed.text);
    let covering = parsed
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote" && e.offset <= 0 && e.offset + e.length >= full)
        .count();
    let collapsed = parsed
        .entities
        .iter()
        .any(|e| e.kind == "blockquote" && e.extra.as_deref() == Some("collapsed"));
    let inner: Vec<MarkupEntityDto> = parsed
        .entities
        .into_iter()
        .filter(|e| !(e.kind == "blockquote" && e.offset <= 0 && e.offset + e.length >= full))
        .collect();
    let mut quote = block("quote", parsed.text, inner);
    quote.level = 1 + covering as i32;
    if collapsed {
        quote.language = Some("collapsed".to_string());
    }
    quote
}

fn push_paragraph(raw: &str, blocks: &mut Vec<MarkupBlockDto>) {
    let raw = raw.trim_matches(['\r', '\n']);
    if raw
        .lines()
        .next()
        .unwrap_or("")
        .trim_start()
        .starts_with("**>")
    {
        // Collapsed (`expandable`) quote syntax parsed by the markdown reader.
        blocks.push(quote_block(crate::parse_telegram_markdown(raw.to_string())));
        return;
    }
    if raw.is_empty() {
        return;
    }
    if let Some(table) = parse_loose_pipe_table(raw) {
        blocks.push(table);
        return;
    }
    if let Some(details) = parse_html_details(raw) {
        blocks.push(details);
        return;
    }
    // tree-sitter keeps list containers opaque to the block renderer. Normalize
    // their markers into readable text while retaining indentation and inline styles.
    if raw.lines().any(|line| is_rule_line(line.trim())) {
        let mut paragraph = String::new();
        for line in raw.lines() {
            if is_rule_line(line.trim()) {
                if !paragraph.trim().is_empty() {
                    blocks.push(block("paragraph", paragraph.trim_end().into(), Vec::new()));
                    paragraph.clear();
                }
                blocks.push(block("rule", String::new(), Vec::new()));
            } else {
                paragraph.push_str(line);
                paragraph.push('\n');
            }
        }
        if !paragraph.trim().is_empty() {
            blocks.push(block(
                "paragraph",
                normalize_list_markers(paragraph.trim_end()),
                Vec::new(),
            ));
        }
        return;
    }
    let source = if raw.contains('<') {
        html_to_markdown(raw)
    } else {
        normalize_list_markers(raw)
    };
    if raw.contains('<')
        && (raw.to_ascii_lowercase().contains("<h")
            || raw.to_ascii_lowercase().contains("<ul")
            || raw.to_ascii_lowercase().contains("<ol")
            || raw.to_ascii_lowercase().contains("<table")
            || raw.to_ascii_lowercase().contains("<details"))
    {
        blocks.extend(render_blocks(&source, Vec::new(), true));
        return;
    }
    let parsed = crate::parse_telegram_markdown(source);
    blocks.push(block("paragraph", parsed.text, parsed.entities));
}

pub(super) fn parse_loose_pipe_table(raw: &str) -> Option<MarkupBlockDto> {
    let lines: Vec<&str> = raw
        .lines()
        .filter(|line| line.trim().contains('|'))
        .collect();
    if lines.len() < 2
        || lines.iter().any(|line| {
            let trimmed = line.trim();
            !trimmed.starts_with('|') || !trimmed.ends_with('|')
        })
    {
        return None;
    }
    let rows: Vec<Vec<String>> = lines
        .iter()
        .map(|line| {
            line.trim()
                .trim_matches('|')
                .split('|')
                .map(|cell| cell.trim().to_owned())
                .collect()
        })
        .collect();
    let width = rows.first()?.len();
    if width < 2 || rows.iter().any(|row| row.len() != width) {
        return None;
    }
    let is_separator = |row: &[String]| {
        row.iter()
            .all(|cell| !cell.is_empty() && cell.chars().all(|ch| matches!(ch, '-' | ':' | ' ')))
    };
    let (headers, body) = if rows.get(1).is_some_and(|row| is_separator(row)) {
        (rows[0].clone(), rows.into_iter().skip(2).collect())
    } else {
        (rows[0].clone(), rows.into_iter().skip(1).collect())
    };
    Some(MarkupBlockDto {
        kind: "table".into(),
        text: String::new(),
        entities: Vec::new(),
        language: None,
        level: 0,
        headers,
        rows: body,
    })
}

pub(super) fn is_rule_line(line: &str) -> bool {
    let compact: String = line.chars().filter(|ch| !ch.is_whitespace()).collect();
    compact.len() >= 3
        && (compact.chars().all(|ch| ch == '-')
            || compact.chars().all(|ch| ch == '*')
            || compact.chars().all(|ch| ch == '_'))
}

fn normalize_list_markers(text: &str) -> String {
    text.lines()
        .map(|line| {
            let indent = line.len() - line.trim_start().len();
            let trimmed = line.trim_start();
            let marker_end = trimmed.find(|ch: char| ch == '.' || ch == ')' || ch == ' ');
            if let Some(end) = marker_end {
                let marker = &trimmed[..end];
                let tail = trimmed[end..]
                    .trim_start_matches(['.', ')', ' '])
                    .trim_start();
                if marker.chars().all(|ch| ch.is_ascii_digit()) && !tail.is_empty() {
                    return format!("{}{}. {}", " ".repeat(indent), marker, tail);
                }
            }
            let unordered = ["- ", "* ", "+ "];
            if let Some(prefix) = unordered
                .iter()
                .find(|prefix| trimmed.starts_with(**prefix))
            {
                return format!(
                    "{}• {}",
                    " ".repeat(indent),
                    trimmed[prefix.len()..].trim_start()
                );
            }
            if let Some(tail) = trimmed
                .strip_prefix("[x]")
                .or_else(|| trimmed.strip_prefix("[X]"))
            {
                return format!("{}☑ {}", " ".repeat(indent), tail.trim_start());
            }
            if let Some(tail) = trimmed.strip_prefix("[ ]") {
                return format!("{}☐ {}", " ".repeat(indent), tail.trim_start());
            }
            line.to_owned()
        })
        .collect::<Vec<_>>()
        .join("\n")
}
