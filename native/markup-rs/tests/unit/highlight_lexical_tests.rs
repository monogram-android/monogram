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
    let source = "auto s = u8R\"tag(\" // raw\n)tag\"; constexpr auto n = 0x1.fp+2; int x = 1'000;";
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
                    String::from_utf16(&encoded[span.start as usize..span.end as usize]).is_ok()
                );
            }
        }
    }
}
