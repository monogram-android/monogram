//! Native block parsing; server entities never pass through Markdown syntax parsing.

use crate::{MarkupBlockDto, MarkupEntityDto};

mod entity_blocks;
mod html;
mod markdown_blocks;

use entity_blocks::entity_blocks;
use html::{parse_html_details, parse_html_table};
use markdown_blocks::markdown_blocks;

const MAX_PARSE_BYTES: usize = 64 * 1024;
const MAX_ENTITIES: usize = 4096;

fn block(kind: &str, text: String, entities: Vec<MarkupEntityDto>) -> MarkupBlockDto {
    MarkupBlockDto {
        kind: kind.into(),
        text,
        entities,
        language: None,
        level: 0,
        headers: Vec::new(),
        rows: Vec::new(),
    }
}

pub fn render_blocks(
    text: &str,
    entities: Vec<MarkupEntityDto>,
    parse_markdown: bool,
) -> Vec<MarkupBlockDto> {
    if text.is_empty() {
        return Vec::new();
    }
    if text.len() > MAX_PARSE_BYTES || entities.len() > MAX_ENTITIES {
        return vec![block("paragraph", text.into(), Vec::new())];
    }
    // Telegram may attach ordinary entities to a message that also contains
    // an HTML block. Preserve the structural HTML before falling back to the
    // entity-only path, which intentionally does not parse Markdown markers.
    if parse_markdown && !entities.is_empty() {
        let lower = text.to_ascii_lowercase();
        if lower.contains("<details") && lower.contains("</details>") {
            if let Some(details) = parse_html_details(text) {
                return vec![details];
            }
        }
        if lower.contains("<table") && lower.contains("</table>") {
            if let Some(table) = parse_html_table(text) {
                return vec![table];
            }
        }
    }
    if !parse_markdown || !entities.is_empty() {
        return entity_blocks(text, &entities);
    }
    markdown_blocks(text)
}

#[cfg(test)]
#[path = "render_tests.rs"]
mod tests;
