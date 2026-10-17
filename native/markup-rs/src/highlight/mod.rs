mod lexical;
mod simple;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HighlightSpan {
    pub start: i32,
    pub end: i32,
    pub scope: compact_str::CompactString,
}

pub fn supported_languages() -> Vec<String> {
    [
        "kotlin",
        "c",
        "cpp",
        "c#",
        "python",
        "javascript",
        "typescript",
        "go",
        "rust",
        "swift",
        "sql",
        "json",
        "xml",
        "html",
        "java",
        "bash",
        "dockerfile",
        "yaml",
        "markdown",
        "ini",
        "toml",
        "properties",
        "php",
        "ruby",
        "lua",
        "haskell",
        "r",
        "matlab",
        "asm",
        "proto",
        "graphql",
        "css",
        "scss",
        "regex",
        "nginx",
        "caddy",
        "jsonc",
        "less",
        "dart",
        "zig",
        "elixir",
        "scala",
        "powershell",
        "makefile",
        "cmake",
        "terraform",
        "perl",
        "julia",
        "solidity",
        "objc",
        "ocaml",
        "fsharp",
        "clojure",
        "nim",
        "groovy",
        "diff",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect()
}

pub fn highlight_code(code: &str, language: &str) -> Vec<HighlightSpan> {
    let normalized = language.trim().to_ascii_lowercase();
    let language = if normalized.is_empty() {
        "plaintext"
    } else {
        simple::normalize_lang(&normalized)
    };
    lexical::highlight(code, language).unwrap_or_else(|| simple::highlight(code, language))
}

#[cfg(test)]
#[path = "../../tests/unit/highlight_mod_tests.rs"]
mod tests;
