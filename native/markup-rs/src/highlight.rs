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
        crate::simple_highlight::normalize_lang(&normalized)
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
            ("htm", "html"),
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
            ("", "plaintext"),
            ("ps1", "powershell"),
            ("h", "c"),
            (" KOTLIN ", "kotlin"),
        ] {
            let source = "fun main() { val x = 42; # comment\n\"text\" }";
            assert_eq!(
                highlight_code(source, alias),
                highlight_code(source, canonical),
                "{alias}"
            );
        }
        assert_ne!(
            highlight_code("fun main() {}", "dart"),
            highlight_code("fun main() {}", "kotlin")
        );
        assert_ne!(
            highlight_code("class Foo {}", "c"),
            highlight_code("class Foo {}", "cpp")
        );
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
