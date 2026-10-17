use super::*;
#[test]
fn language_specific_tokens() {
    for (lang, source, expected, scope) in [
        (
            "python",
            "def f():\n  s = '''# text\ntext'''",
            "'''# text\ntext'''",
            "string",
        ),
        ("javascript", "const x = `text`;", "const", "keyword"),
        ("go", "func main() {}", "func", "keyword"),
        ("swift", "guard let x = y else {}", "guard", "keyword"),
        ("java", "public class Main {}", "public", "keyword"),
        ("sql", "SELECT 'it''s' -- comment", "'it''s'", "string"),
        ("json", "{\"key\": true}", "\"key\"", "property"),
        (
            "xml",
            "<div id=\"x\"><![CDATA[<text>]]></div>",
            "<![CDATA[<text>]]>",
            "string",
        ),
        ("dockerfile", "FROM alpine", "FROM", "keyword"),
        ("yaml", "key: value # note", "key", "property"),
        ("toml", "key = '''a\nb'''", "'''a\nb'''", "string"),
        ("ini", "[section]\nkey=value", "[section]", "type"),
        ("properties", "# note\nkey=value", "# note", "comment"),
        ("php", "echo $value;", "echo", "keyword"),
        ("ruby", "def f\nend", "def", "keyword"),
        (
            "lua",
            "--[=[ note ]=]\nlocal x = [[text]]",
            "--[=[ note ]=]",
            "comment",
        ),
        (
            "haskell",
            "{- a {- b -} c -} module Main where",
            "{- a {- b -} c -}",
            "comment",
        ),
        ("r", "function(x) TRUE", "TRUE", "keyword"),
        ("matlab", "% note\nfunction y=f(x)", "% note", "comment"),
        ("asm", "MOV eax, 1", "MOV", "keyword"),
        ("proto", "message Data {}", "message", "keyword"),
        ("graphql", "query { user }", "query", "keyword"),
        ("css", "a { color: red; }", "color", "property"),
        ("scss", "$color: red; // note", "// note", "comment"),
        ("regex", "\\d+", "\\d", "string.escape"),
        ("nginx", "server { listen 80; }", "listen", "keyword"),
        (
            "caddy",
            "localhost { reverse_proxy :8080 }",
            "reverse_proxy",
            "keyword",
        ),
        ("markdown", "# title\n`code`", "# title", "keyword"),
        ("rust", "fn main() {}", "fn", "keyword"),
        ("c", "int main() { return 0; }", "int", "keyword"),
        ("cpp", "class Foo {};", "class", "keyword"),
        ("csharp", "using System;", "using", "keyword"),
        ("kotlin", "fun main() {}", "fun", "keyword"),
        ("bash", "if true; then echo hi; fi", "if", "keyword"),
        ("html", "<div id=\"x\"></div>", "<div", "tag"),
        ("scala", "def main() = {}", "def", "keyword"),
        ("elixir", "defmodule Mix do end", "defmodule", "keyword"),
        ("dart", "class App {}", "class", "keyword"),
        ("zig", "pub fn main() void {}", "fn", "keyword"),
        ("powershell", "function Get-Name {}", "function", "keyword"),
        ("makefile", "include common.mk", "include", "keyword"),
        ("perl", "my $x = 1;", "my", "keyword"),
        ("julia", "function f(x) end", "function", "keyword"),
        ("solidity", "contract Token {}", "contract", "keyword"),
        (
            "terraform",
            "resource \"aws_instance\" \"web\" {}",
            "resource",
            "keyword",
        ),
        ("cmake", "if(TRUE)\nendif()", "if", "keyword"),
        ("objc", "@interface Foo : NSObject", "interface", "keyword"),
        ("clojure", "(defn foo [x] x)", "defn", "keyword"),
        ("ocaml", "let rec f x = x", "let", "keyword"),
        ("fsharp", "let x = 1", "let", "keyword"),
        ("nim", "proc foo() = discard", "proc", "keyword"),
        ("groovy", "def foo() {}", "def", "keyword"),
        ("jsonc", "// note\n{\"key\": true}", "// note", "comment"),
        ("less", "@color: red; // note", "// note", "comment"),
        (
            "diff",
            "--- a/file\n+++ b/file\n+added\n",
            "+++ b/file",
            "keyword",
        ),
        (
            "rs",
            "pub fn id<'a>(x: &'a i32) -> &'a i32 { x }",
            "fn",
            "keyword",
        ),
        ("ts", "const x: number = 1;", "const", "keyword"),
        ("python", "s = r\"raw # text\"", "r\"raw # text\"", "string"),
        (
            "rust",
            "let s = r#\"a \"quote\"\"#;",
            "r#\"a \"quote\"\"#",
            "string",
        ),
        (
            "sql",
            "SELECT * FROM t /* hidden */",
            "/* hidden */",
            "comment",
        ),
        ("yaml", "Key: Yes", "Yes", "keyword"),
        ("rust", "fn f<'a>(x: &'a str) {}", "'a", "type"),
        (
            "c",
            "#include <stdio.h>\nint main() { return 0; }",
            "#include",
            "keyword",
        ),
        (
            "typescript",
            "interface A { x: number }",
            "interface",
            "keyword",
        ),
        (
            "python",
            "s = r'# not comment'",
            "r'# not comment'",
            "string",
        ),
        ("js", "const x = 1;", "const", "keyword"),
        ("lua", "local x = 1", "local", "keyword"),
        ("html", "<div title=\"x\" style=\"y\">", "title", "property"),
    ] {
        let encoded: Vec<_> = source.encode_utf16().collect();
        assert!(
            highlight(source, lang).iter().any(|s| s.scope == scope
                && String::from_utf16(&encoded[s.start as usize..s.end as usize]).unwrap()
                    == expected),
            "{lang}: expected {expected:?} as {scope}, got {:?}",
            highlight(source, lang)
                .iter()
                .map(|s| (
                    String::from_utf16(&encoded[s.start as usize..s.end as usize]).unwrap(),
                    s.scope.clone()
                ))
                .collect::<Vec<_>>()
        );
    }
}
