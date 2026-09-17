use crate::lexical_highlight;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HighlightSpan {
    pub start: i32,
    pub end: i32,
    pub scope: String,
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
    ]
    .into_iter()
    .map(str::to_owned)
    .collect()
}

pub fn highlight_code(code: &str, language: &str) -> Vec<HighlightSpan> {
    let normalized = language.trim().to_ascii_lowercase();
    let language = match normalized.as_str() {
        "kotlin" | "kt" | "kts" => "kotlin",
        "c" | "cpp" | "c++" | "c#" | "cs" => "cpp",
        "python" | "py" => "python",
        "javascript" | "js" | "typescript" | "ts" | "tsx" | "jsx" => "javascript",
        "go" | "golang" => "go",
        "rust" | "rs" => "rust",
        "xml" | "html" => "xml",
        "bash" | "sh" | "zsh" | "shell" => "bash",
        "yaml" | "yml" => "yaml",
        "md" | "markdown" => "markdown",
        "ruby" | "rb" => "ruby",
        "haskell" | "hs" => "haskell",
        "matlab" | "m" => "matlab",
        "asm" | "s" => "asm",
        "graphql" | "gql" => "graphql",
        "scss" | "sass" => "scss",
        "regex" | "regexp" => "regex",
        "swift" | "sql" | "json" | "java" | "dockerfile" | "ini" | "toml" | "properties"
        | "php" | "lua" | "r" | "proto" | "css" | "nginx" | "caddy" => normalized.as_str(),
        _ => "kotlin",
    };
    lexical_highlight::highlight(code, language)
        .unwrap_or_else(|| crate::simple_highlight::highlight(code, language))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aliases_and_fallback() {
        for (alias, canonical) in [
            ("c++", "cpp"),
            ("cs", "c#"),
            ("py", "python"),
            ("js", "javascript"),
            ("ts", "typescript"),
            ("golang", "go"),
            ("rs", "rust"),
            ("html", "xml"),
            ("sh", "bash"),
            ("zsh", "bash"),
            ("yml", "yaml"),
            ("md", "markdown"),
            ("rb", "ruby"),
            ("hs", "haskell"),
            ("m", "matlab"),
            ("s", "asm"),
            ("gql", "graphql"),
            ("sass", "scss"),
            ("regexp", "regex"),
            ("", "kotlin"),
            ("unknown", "kotlin"),
            (" KOTLIN ", "kotlin"),
        ] {
            let source = "fun main() { val x = 42; # comment\n\"text\" }";
            assert_eq!(
                highlight_code(source, alias),
                highlight_code(source, canonical),
                "{alias}"
            );
        }
    }

    #[test]
    fn every_language_handles_unicode_and_incomplete_input() {
        for language in supported_languages() {
            for source in [
                "// \u{1f44b}\nname = 42",
                "\"unfinished \u{1f44b}",
                "/* nested /*",
                "'",
                "\\",
                "<!--",
                "[=[",
                "",
            ] {
                let encoded: Vec<_> = source.encode_utf16().collect();
                let spans = highlight_code(source, &language);
                assert!(
                    spans.windows(2).all(|p| p[0].end <= p[1].start),
                    "{language}"
                );
                for span in spans {
                    assert!(
                        span.start >= 0
                            && span.start < span.end
                            && span.end as usize <= encoded.len(),
                        "{language}"
                    );
                    assert!(
                        String::from_utf16(&encoded[span.start as usize..span.end as usize])
                            .is_ok()
                    );
                }
            }
        }
    }
}
