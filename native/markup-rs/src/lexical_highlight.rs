//! Small, tolerant lexical highlighting for languages whose full parse tables are costly.

use crate::highlight::HighlightSpan;
use crate::utf16::utf16_len;

const KOTLIN_KEYWORDS: &str = "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg";
const CPP_KEYWORDS: &str = "alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t char32_t class compl concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq";
const RUST_KEYWORDS: &str = "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while abstract become box do final macro override priv typeof unsized virtual yield try gen";
const CSHARP_KEYWORDS: &str = "abstract as async await base checked decimal delegate event finally fixed foreach in interface internal is lock null object out override params readonly record ref sbyte sealed stackalloc string unchecked unsafe ushort uint ulong var when where yield get set init value partial required with";

pub fn highlight(code: &str, language: &str) -> Option<Vec<HighlightSpan>> {
    if matches!(language, "bash" | "sh" | "shell") {
        return Some(highlight_shell(code));
    }
    let rust = matches!(language, "rust" | "rs");
    let kotlin = match language {
        "kotlin" | "kt" | "kts" => true,
        "cpp" | "c++" | "cc" | "cxx" | "hpp" | "hxx" => false,
        "rust" | "rs" => false,
        _ => return None,
    };
    let keywords = if rust {
        RUST_KEYWORDS
    } else if kotlin {
        KOTLIN_KEYWORDS
    } else {
        CPP_KEYWORDS
    };
    let mut spans = Vec::new();
    let mut pos = 0;
    let mut offset = 0;
    while pos < code.len() {
        let start = pos;
        let rest = &code[pos..];
        let ch = rest.chars().next().unwrap();
        let raw_end = if rust {
            rust_raw_end(rest)
        } else if kotlin {
            None
        } else {
            cpp_raw_end(rest)
        };
        let scope = if rest.starts_with("//") {
            pos += rest.find('\n').unwrap_or(rest.len());
            Some("comment")
        } else if rest.starts_with("/*") {
            pos = block_comment_end(code, pos, kotlin || rust);
            Some("comment")
        } else if kotlin && rest.starts_with("\"\"\"") {
            pos += 3;
            pos = code[pos..]
                .find("\"\"\"")
                .map_or(code.len(), |n| pos + n + 3);
            Some("string")
        } else if let Some(end) = raw_end {
            pos += end;
            Some("string")
        } else if rust && ch == '\'' && rust_lifetime_end(rest).is_some() {
            pos += rust_lifetime_end(rest).unwrap();
            Some("variable")
        } else if !kotlin && !rust && rest.starts_with("@\"") {
            pos += 2;
            while pos < code.len() {
                if code[pos..].starts_with("\"\"") {
                    pos += 2;
                } else if code[pos..].starts_with('"') {
                    pos += 1;
                    break;
                } else {
                    pos += code[pos..].chars().next().unwrap().len_utf8();
                }
            }
            Some("string")
        } else if ch == '"' || ch == '\'' || (kotlin && ch == '`') {
            pos = quoted_end(code, pos, ch, rust && ch == '"', true);
            Some(if ch == '`' { "variable" } else { "string" })
        } else if ch.is_ascii_digit() {
            let hex = rest.starts_with("0x") || rest.starts_with("0X");
            pos += ch.len_utf8();
            while pos < code.len() {
                let next = code.as_bytes()[pos];
                if next.is_ascii_alphanumeric()
                    || next == b'_'
                    || (!kotlin && !rust && next == b'\'')
                {
                    pos += 1;
                } else if next == b'.'
                    && code.as_bytes().get(pos + 1).is_some_and(|b| {
                        if hex {
                            b.is_ascii_hexdigit()
                        } else {
                            b.is_ascii_digit()
                        }
                    })
                {
                    pos += 1;
                } else if matches!(next, b'+' | b'-')
                    && matches!(code.as_bytes()[pos - 1], b'e' | b'E' | b'p' | b'P')
                {
                    pos += 1;
                } else {
                    break;
                }
            }
            Some("number")
        } else if ch == '_' || ch.is_alphabetic() {
            pos += ch.len_utf8();
            while let Some(next) = code[pos..].chars().next() {
                if next != '_' && !next.is_alphanumeric() {
                    break;
                }
                pos += next.len_utf8();
            }
            let word = &code[start..pos];
            Some(
                if keywords.split_ascii_whitespace().any(|key| key == word)
                    || (!rust
                        && !kotlin
                        && CSHARP_KEYWORDS
                            .split_ascii_whitespace()
                            .any(|key| key == word))
                {
                    "keyword"
                } else if code[pos..].trim_start().starts_with('(') {
                    "function"
                } else {
                    "variable"
                },
            )
        } else {
            pos += ch.len_utf8();
            if "(){}[]".contains(ch) {
                Some("punctuation.bracket")
            } else if ",;.:".contains(ch) {
                Some("punctuation.delimiter")
            } else if "+-*/%=!<>&|^~?".contains(ch) {
                Some("operator")
            } else {
                None
            }
        };
        let end = offset + utf16_len(&code[start..pos]);
        if let Some(scope) = scope {
            spans.push(HighlightSpan {
                start: offset,
                end,
                scope: scope.into(),
            });
        }
        offset = end;
    }
    Some(spans)
}

fn quoted_end(code: &str, start: usize, quote: char, multiline: bool, escapes: bool) -> usize {
    let mut pos = start + quote.len_utf8();
    while let Some(ch) = code[pos..].chars().next() {
        if !multiline && (ch == '\n' || ch == '\r') {
            break;
        }
        pos += ch.len_utf8();
        if ch == quote {
            break;
        }
        if escapes && ch == '\\' && quote != '`' {
            if let Some(escaped) = code[pos..].chars().next() {
                pos += escaped.len_utf8();
            }
        }
    }
    pos
}

fn block_comment_end(code: &str, start: usize, nested: bool) -> usize {
    let mut depth = 1;
    let mut pos = start + 2;
    while pos < code.len() {
        if code[pos..].starts_with("*/") {
            pos += 2;
            depth -= 1;
            if depth == 0 {
                return pos;
            }
        } else if nested && code[pos..].starts_with("/*") {
            depth += 1;
            pos += 2;
        } else {
            pos += code[pos..].chars().next().unwrap().len_utf8();
        }
    }
    pos
}

fn cpp_raw_end(code: &str) -> Option<usize> {
    let prefix = ["R\"", "u8R\"", "uR\"", "UR\"", "LR\""]
        .into_iter()
        .find(|p| code.starts_with(p))?;
    let rest = &code[prefix.len()..];
    let delimiter_end = rest.bytes().take(17).position(|b| b == b'(')?;
    let delimiter = rest.get(..delimiter_end)?;
    if delimiter
        .bytes()
        .any(|b| b.is_ascii_whitespace() || matches!(b, b'\\' | b')') || !b.is_ascii())
    {
        return None;
    }
    let body = prefix.len() + delimiter_end + 1;
    let closing = format!("){delimiter}\"");
    Some(
        code[body..]
            .find(&closing)
            .map_or(code.len(), |n| body + n + closing.len()),
    )
}

fn rust_raw_end(code: &str) -> Option<usize> {
    let prefix = if code.starts_with("br") || code.starts_with("cr") {
        2
    } else if code.starts_with('r') {
        1
    } else {
        return None;
    };
    let hashes = code[prefix..].bytes().take_while(|b| *b == b'#').count();
    if hashes > 255 || code.as_bytes().get(prefix + hashes) != Some(&b'"') {
        return None;
    }
    let body = prefix + hashes + 1;
    let closing = format!("\"{}", "#".repeat(hashes));
    Some(
        code[body..]
            .find(&closing)
            .map_or(code.len(), |n| body + n + closing.len()),
    )
}

fn rust_lifetime_end(code: &str) -> Option<usize> {
    let first = code[1..].chars().next()?;
    if first != '_' && !first.is_alphabetic() {
        return None;
    }
    let mut end = 1 + first.len_utf8();
    while let Some(ch) = code[end..].chars().next() {
        if ch != '_' && !ch.is_alphanumeric() {
            break;
        }
        end += ch.len_utf8();
    }
    if code.as_bytes().get(end) == Some(&b'\'') {
        None
    } else {
        Some(end)
    }
}

const SHELL_KEYWORDS: &str =
    "if then else elif fi case esac for select while until do done in function time coproc";
const SHELL_BUILTINS: &str = "alias bg bind break builtin cd command compgen complete continue declare dirs disown echo enable eval exec exit export fc fg getopts hash help history jobs kill let local logout mapfile popd printf pushd pwd read readarray readonly return set shift shopt source test times trap type typeset ulimit umask unalias unset wait";

fn highlight_shell(code: &str) -> Vec<HighlightSpan> {
    let mut spans = Vec::new();
    let mut pos = 0;
    let mut offset = 0;
    let mut heredocs = std::collections::VecDeque::new();
    let mut in_heredoc = false;
    while pos < code.len() {
        let start = pos;
        let rest = &code[pos..];
        let ch = rest.chars().next().unwrap();
        let scope = if in_heredoc {
            let (delimiter, strip_tabs): &(String, bool) = heredocs.front().unwrap();
            let line_end = rest.find('\n').unwrap_or(rest.len());
            let line = rest[..line_end].trim_end_matches('\r');
            let line = if *strip_tabs {
                line.trim_start_matches('\t')
            } else {
                line
            };
            let finished = line == delimiter;
            pos += line_end + usize::from(line_end < rest.len());
            if finished {
                heredocs.pop_front();
                in_heredoc = !heredocs.is_empty();
            }
            Some("string")
        } else if ch == '\n' {
            pos += 1;
            in_heredoc = !heredocs.is_empty();
            None
        } else if rest.starts_with("<<<") {
            pos += 3;
            Some("operator")
        } else if rest.starts_with("<<") {
            if let Some((end, delimiter, strip_tabs)) = shell_heredoc(rest) {
                pos += end;
                heredocs.push_back((delimiter, strip_tabs));
            } else {
                pos += 2;
            }
            Some("operator")
        } else if ch == '#'
            && (start == 0
                || code[..start]
                    .chars()
                    .next_back()
                    .is_some_and(|c| c.is_whitespace() || ";|&()".contains(c)))
        {
            pos += rest.find('\n').unwrap_or(rest.len());
            Some("comment")
        } else if ch == '\'' || ch == '"' || ch == '`' {
            pos = quoted_end(code, pos, ch, true, ch != '\'');
            Some("string")
        } else if ch == '\\' {
            pos += 1;
            if let Some(escaped) = code[pos..].chars().next() {
                pos += escaped.len_utf8();
            }
            Some("string")
        } else if ch == '$' {
            pos += 1;
            if code[pos..].starts_with('{') {
                pos = code[pos..].find('}').map_or(code.len(), |n| pos + n + 1);
            } else if code[pos..].starts_with('(') {
                // Leave command substitution contents to the normal token scanner.
            } else if code[pos..]
                .chars()
                .next()
                .is_some_and(|c| c.is_ascii_digit() || "?#$!@*-".contains(c))
            {
                pos += code[pos..].chars().next().unwrap().len_utf8();
            } else {
                while code[pos..]
                    .chars()
                    .next()
                    .is_some_and(|c| c == '_' || c.is_alphanumeric())
                {
                    pos += code[pos..].chars().next().unwrap().len_utf8();
                }
            }
            Some("variable")
        } else if ch == '_' || ch.is_alphanumeric() {
            pos += ch.len_utf8();
            while code[pos..]
                .chars()
                .next()
                .is_some_and(|c| c == '_' || c == '-' || c.is_alphanumeric())
            {
                pos += code[pos..].chars().next().unwrap().len_utf8();
            }
            let word = &code[start..pos];
            if SHELL_KEYWORDS
                .split_ascii_whitespace()
                .any(|key| key == word)
            {
                Some("keyword")
            } else if SHELL_BUILTINS
                .split_ascii_whitespace()
                .any(|key| key == word)
            {
                Some("function.builtin")
            } else if word.bytes().all(|b| b.is_ascii_digit()) {
                Some("number")
            } else {
                None
            }
        } else {
            pos += ch.len_utf8();
            if "|&;<>=".contains(ch) {
                Some("operator")
            } else if "(){}[]".contains(ch) {
                Some("punctuation.bracket")
            } else {
                None
            }
        };
        let end = offset + utf16_len(&code[start..pos]);
        if let Some(scope) = scope {
            spans.push(HighlightSpan {
                start: offset,
                end,
                scope: scope.into(),
            });
        }
        offset = end;
    }
    spans
}

fn shell_heredoc(code: &str) -> Option<(usize, String, bool)> {
    let strip_tabs = code.starts_with("<<-");
    let mut pos = if strip_tabs { 3 } else { 2 };
    while code[pos..].starts_with([' ', '\t']) {
        pos += 1;
    }
    let mut delimiter = String::new();
    while let Some(ch) = code[pos..].chars().next() {
        if ch.is_whitespace() || ";|&<>()".contains(ch) {
            break;
        }
        if ch == '\'' || ch == '"' {
            let end = quoted_end(code, pos, ch, false, ch != '\'');
            if end <= pos + 1 || !code[..end].ends_with(ch) {
                return None;
            }
            delimiter.push_str(&code[pos + 1..end - 1]);
            pos = end;
        } else if ch == '\\' {
            pos += 1;
            let escaped = code[pos..].chars().next()?;
            if escaped == '\n' {
                return None;
            }
            delimiter.push(escaped);
            pos += escaped.len_utf8();
        } else {
            delimiter.push(ch);
            pos += ch.len_utf8();
        }
    }
    if delimiter.is_empty() {
        None
    } else {
        Some((pos, delimiter, strip_tabs))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tokens<'a>(source: &'a str, language: &str, scope: &str) -> Vec<String> {
        let utf16: Vec<_> = source.encode_utf16().collect();
        highlight(source, language)
            .unwrap()
            .iter()
            .filter(|s| s.scope == scope)
            .map(|s| String::from_utf16(&utf16[s.start as usize..s.end as usize]).unwrap())
            .collect()
    }

    #[test]
    fn kotlin_multiline_nested_comments_and_ranges() {
        let source = "/* outer /* nested */ end */ val x = \"\"\"// raw\ntext\"\"\"; for (i in 1..10) println(i)";
        assert_eq!(
            tokens(source, "kotlin", "comment"),
            ["/* outer /* nested */ end */"]
        );
        assert_eq!(
            tokens(source, "kotlin", "string"),
            ["\"\"\"// raw\ntext\"\"\""]
        );
        assert_eq!(tokens(source, "kotlin", "number"), ["1", "10"]);
        assert_eq!(tokens(source, "kotlin", "keyword"), ["val", "for", "in"]);
    }

    #[test]
    fn cpp_raw_string_and_numeric_literals() {
        let source =
            "auto s = u8R\"tag(\" // raw\n)tag\"; constexpr auto n = 0x1.fp+2; int x = 1'000;";
        assert_eq!(
            tokens(source, "cpp", "string"),
            ["u8R\"tag(\" // raw\n)tag\""]
        );
        assert!(tokens(source, "cpp", "comment").is_empty());
        assert_eq!(tokens(source, "cpp", "number"), ["0x1.fp+2", "1'000"]);
    }

    #[test]
    fn rust_lifetimes_raw_strings_and_nested_comments() {
        let source = "fn f<'a>(s: &'a str) { let x = br##\"// raw \"# text\"##; let c = 'x'; /* a /* b */ c */ }";
        assert_eq!(
            tokens(source, "rust", "string"),
            ["br##\"// raw \"# text\"##", "'x'"]
        );
        assert_eq!(tokens(source, "rust", "comment"), ["/* a /* b */ c */"]);
        assert_eq!(tokens(source, "rust", "keyword"), ["fn", "let", "let"]);
        assert_eq!(tokens("let x = 1..10;", "rs", "number"), ["1", "10"]);
    }

    #[test]
    fn shell_quotes_variables_and_multiple_heredocs() {
        let source = "echo foo#bar \\# $HOME ${x:-default} # comment\ncat <<'EOF' <<-END\n# not comment\nEOF\n\t$HOME\n\tEND\necho done";
        assert_eq!(tokens(source, "bash", "comment"), ["# comment"]);
        assert_eq!(
            tokens(source, "bash", "variable"),
            ["$HOME", "${x:-default}"]
        );
        let strings = tokens(source, "bash", "string");
        assert!(strings.iter().any(|s| s == "# not comment\n"));
        assert!(strings.iter().any(|s| s == "\t$HOME\n"));
        assert_eq!(
            tokens("echo 'it\\' # outside", "sh", "comment"),
            ["# outside"]
        );
        assert_eq!(
            tokens("echo \"line one\n# inside\"", "shell", "string"),
            ["\"line one\n# inside\""]
        );
    }

    #[test]
    fn utf16_offsets_and_unfinished_input_are_safe() {
        for language in ["kotlin", "cpp", "rust", "bash"] {
            for source in [
                "// \u{1f44b}\nval name = \"text\"",
                "/* unfinished",
                "\"unfinished\nreturn 1",
                "R\"tag(unclosed",
                "val `class` = 2",
            ] {
                let spans = highlight(source, language).unwrap();
                assert!(spans.windows(2).all(|pair| pair[0].end <= pair[1].start));
                assert!(
                    spans
                        .iter()
                        .all(|s| s.start < s.end && s.end <= utf16_len(source))
                );
                for span in spans {
                    let encoded: Vec<_> = source.encode_utf16().collect();
                    assert!(
                        String::from_utf16(&encoded[span.start as usize..span.end as usize])
                            .is_ok()
                    );
                }
            }
        }
    }
}
