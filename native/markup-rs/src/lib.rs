//! Android UniFFI surface for Telegram-style markup (tree-sitter).
//! Separate from monogram-mtproto. Features must call the Kotlin facade, not this crate.

#![deny(unsafe_code)]

mod highlight;
mod html;
mod lexical_highlight;
mod markdown;
mod math;
mod render;
mod simple_highlight;
mod utf16;

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, uniffi::Record)]
pub struct MarkupEntityDto {
    pub kind: String,
    pub offset: i32,
    pub length: i32,
    pub extra: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StyledMarkupDto {
    pub text: String,
    pub entities: Vec<MarkupEntityDto>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MarkupBlockDto {
    pub kind: String,
    pub text: String,
    pub entities: Vec<MarkupEntityDto>,
    pub language: Option<String>,
    pub level: i32,
    pub headers: Vec<String>,
    pub rows: Vec<Vec<String>>,
}

#[uniffi::export]
pub fn render_blocks(
    text: String,
    entities: Vec<MarkupEntityDto>,
    parse_markdown: bool,
) -> Vec<MarkupBlockDto> {
    render::render_blocks(&text, entities, parse_markdown)
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct HighlightSpanDto {
    pub start: i32,
    pub end: i32,
    pub scope: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MathSpanDto {
    pub start: i32,
    pub end: i32,
    pub display: bool,
    pub source: String,
}

#[uniffi::export]
pub fn library_version() -> String {
    format!(
        "monogram-markup/{}; tree-sitter markup",
        env!("CARGO_PKG_VERSION")
    )
}

#[uniffi::export]
pub fn parse_telegram_markdown(raw: String) -> StyledMarkupDto {
    let styled = markdown::parse_telegram_markdown(&raw);
    StyledMarkupDto {
        text: styled.text,
        entities: styled
            .entities
            .into_iter()
            .map(|e| MarkupEntityDto {
                kind: e.kind,
                offset: e.offset,
                length: e.length,
                extra: e.extra,
            })
            .collect(),
    }
}

#[uniffi::export]
pub fn highlight_code(code: String, language: String) -> Vec<HighlightSpanDto> {
    highlight::highlight_code(&code, &language)
        .into_iter()
        .map(|s| HighlightSpanDto {
            start: s.start,
            end: s.end,
            scope: s.scope,
        })
        .collect()
}

#[uniffi::export]
pub fn extract_math(raw: String) -> Vec<MathSpanDto> {
    math::extract_math(&raw)
        .into_iter()
        .map(|s| MathSpanDto {
            start: s.start,
            end: s.end,
            display: s.display,
            source: s.source,
        })
        .collect()
}

#[uniffi::export]
pub fn supported_highlight_languages() -> Vec<String> {
    highlight::supported_languages()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_export_roundtrip() {
        let styled = parse_telegram_markdown("**hi**".into());
        assert_eq!(styled.text, "hi");
        assert_eq!(styled.entities[0].kind, "bold");
    }
}
