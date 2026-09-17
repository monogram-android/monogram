//! Shared lexical scanner: small language settings instead of generated parse tables.
use crate::{highlight::HighlightSpan, utf16::utf16_len};

struct Config {
    keywords: &'static str,
    comments: &'static [&'static str],
    block: Option<(&'static str, &'static str)>,
    quotes: &'static str,
    triple: bool,
    insensitive: bool,
}

fn config(lang: &str) -> Config {
    let mut c = Config {
        keywords: "",
        comments: &["//"],
        block: Some(("/*", "*/")),
        quotes: "\"'",
        triple: false,
        insensitive: false,
    };
    c.keywords = match lang {
        "javascript" => "as async await break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in infer instanceof interface is keyof let module namespace never new null of package private protected public readonly return satisfies set static super switch this throw true try type typeof undefined unknown var void while with yield",
        "python" => "and as assert async await break case class continue def del elif else except False finally for from global if import in is lambda match None nonlocal not or pass raise return True try while with yield",
        "go" => "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil",
        "swift" => "actor any as associatedtype async await break case catch class continue convenience default defer deinit didSet do dynamic else enum extension fallthrough false fileprivate final for func get guard if import in indirect init inout internal is isolated lazy let mutating nil nonisolated open operator optional override private protocol public repeat required rethrows return self Self set some static struct subscript super switch throw throws true try typealias unowned var weak where while willSet",
        "java" => "abstract assert boolean break byte case catch char class const continue default do double else enum exports extends false final finally float for if implements import instanceof int interface long module native new null package permits private protected public record requires return sealed short static strictfp super switch synchronized this throw throws transient true try var void volatile while yield",
        "sql" => "select from where insert into values update set delete create alter drop table index view join inner left right full outer on as and or not null is in exists like between group by order having limit offset union all distinct asc desc primary key foreign references constraint default case when then else end begin commit rollback with returning true false",
        "json" => "true false null",
        "dockerfile" => "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as",
        "yaml" => "true false null yes no on off",
        "toml" => "true false inf nan",
        "php" => "abstract and array as break callable case catch class clone const continue declare default die do echo else elseif empty endfor endforeach endif endswitch endwhile enum eval exit extends false final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new null or print private protected public readonly require require_once return static switch throw trait true try unset use var while xor yield",
        "ruby" => "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield",
        "lua" => "and break do else elseif end false for function goto if in local nil not or repeat return then true until while",
        "haskell" => "as case class data default deriving do else family forall foreign hiding if import in infix infixl infixr instance let module newtype of qualified then type where True False",
        "r" => "if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA NA_integer_ NA_real_ NA_complex_ NA_character_",
        "matlab" => "break case catch classdef continue else elseif end enumeration events for function global if methods otherwise parfor persistent properties return spmd switch try while true false",
        "asm" => "mov movq movl movz lea ldr str push pop add sub mul imul div idiv and or xor not neg cmp test jmp je jne jz jnz jg jl jge jle call ret nop syscall int section global extern db dw dd dq equ align bits inc dec shl shr rax rbx rcx rdx rsp rbp rsi rdi eax ebx ecx edx esp ebp esi edi",
        "proto" => "syntax edition import weak public package option optional required repeated oneof map reserved extensions to max enum message service rpc returns stream extend true false double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes",
        "graphql" => "query mutation subscription fragment on schema scalar type interface union enum input extend directive implements repeatable true false null",
        "css" | "scss" => "important inherit initial unset revert none auto transparent currentcolor true false null if else for each while from through to in mixin include function return extend use forward media supports keyframes import charset namespace font-face layer container",
        "nginx" => "http server location upstream events stream map geo limit_except if set listen server_name root index try_files proxy_pass proxy_set_header return rewrite include worker_processes worker_connections access_log error_log ssl_certificate ssl_certificate_key on off",
        "caddy" => "import log debug email auto_https tls root file_server reverse_proxy redir rewrite respond handle handle_path route header encode templates php_fastcgi request_body bind basic_auth forward_auth errors metrics on off",
        _ => "",
    };
    match lang {
        "python" | "ruby" | "r" | "yaml" | "toml" | "dockerfile" | "graphql" | "nginx"
        | "caddy" => {
            c.comments = &["#"];
            c.block = None;
        }
        "sql" => {
            c.comments = &["--"];
            c.insensitive = true;
        }
        "lua" => {
            c.comments = &["--"];
            c.block = None;
        }
        "haskell" => {
            c.comments = &["--"];
            c.block = Some(("{-", "-}"));
        }
        "matlab" => {
            c.comments = &["%"];
            c.block = Some(("%{", "%}"));
        }
        "asm" => {
            c.comments = &[";", "#", "//"];
            c.insensitive = true;
        }
        "ini" => {
            c.comments = &[";", "#"];
            c.block = None;
        }
        "properties" => {
            c.comments = &["#", "!"];
            c.block = None;
            c.quotes = "";
        }
        "xml" | "markdown" => {
            c.comments = &[];
            c.block = Some(("<!--", "-->"));
        }
        "json" => {
            c.comments = &[];
            c.block = None;
            c.quotes = "\"";
        }
        "css" => c.comments = &[],
        "php" => c.comments = &["//", "#"],
        _ => {}
    }
    c.triple = matches!(lang, "python" | "swift" | "java" | "toml" | "graphql");
    if matches!(lang, "javascript" | "go" | "r") {
        c.quotes = "\"'`";
    }
    if lang == "dockerfile" {
        c.insensitive = true;
    }
    c
}

fn delimited(code: &str, open: &str, close: &str, nested: bool) -> usize {
    let mut pos = open.len();
    let mut depth = 1;
    while pos < code.len() {
        if code[pos..].starts_with(close) {
            pos += close.len();
            depth -= 1;
            if depth == 0 {
                return pos;
            }
        } else if nested && code[pos..].starts_with(open) {
            pos += open.len();
            depth += 1;
        } else {
            pos += code[pos..].chars().next().unwrap().len_utf8();
        }
    }
    pos
}

fn lua_long(code: &str) -> Option<usize> {
    let rest = code.strip_prefix('[')?;
    let count = rest.bytes().take_while(|b| *b == b'=').count();
    if rest.as_bytes().get(count) != Some(&b'[') {
        return None;
    }
    let open = &code[..count + 2];
    Some(delimited(
        code,
        open,
        &format!("]{}]", "=".repeat(count)),
        false,
    ))
}

pub fn highlight(code: &str, lang: &str) -> Vec<HighlightSpan> {
    let c = config(lang);
    let mut spans = Vec::new();
    let mut pos = 0;
    let mut offset = 0;
    let mut line_start = true;
    let mut xml_tag = false;
    while pos < code.len() {
        let start = pos;
        let rest = &code[pos..];
        let ch = rest.chars().next().unwrap();
        let block = c.block.filter(|(open, _)| rest.starts_with(open));
        let scope = if lang == "regex" {
            pos += ch.len_utf8();
            if ch == '\\' {
                if let Some(next) = code[pos..].chars().next() {
                    pos += next.len_utf8();
                }
                Some("string.escape")
            } else if "[](){}".contains(ch) {
                Some("punctuation.bracket")
            } else if ".*+?^$|".contains(ch) {
                Some("operator")
            } else {
                None
            }
        } else if lang == "lua" && rest.starts_with("--[") && lua_long(&rest[2..]).is_some() {
            pos += 2 + lua_long(&rest[2..]).unwrap();
            Some("comment")
        } else if let Some((open, close)) = block {
            pos += delimited(rest, open, close, matches!(lang, "haskell" | "swift"));
            Some("comment")
        } else if c.comments.iter().any(|p| rest.starts_with(p))
            && (!matches!(lang, "properties" | "ini" | "dockerfile") || line_start)
            && (lang != "yaml" || start == 0 || code[..start].ends_with(char::is_whitespace))
        {
            pos += rest.find('\n').unwrap_or(rest.len());
            Some("comment")
        } else if lang == "lua" && lua_long(rest).is_some() {
            pos += lua_long(rest).unwrap();
            Some("string")
        } else if lang == "xml" && rest.starts_with("<![CDATA[") {
            pos += delimited(rest, "<![CDATA[", "]]>", false);
            Some("string")
        } else if lang == "markdown" && (rest.starts_with("```") || rest.starts_with("~~~")) {
            let marker = &rest[..3];
            pos += delimited(rest, marker, marker, false);
            Some("string")
        } else if lang == "markdown" && ch == '`' {
            pos += delimited(rest, "`", "`", false);
            Some("string")
        } else if lang == "markdown" && line_start && ch == '#' {
            pos += rest.find('\n').unwrap_or(rest.len());
            Some("keyword")
        } else if matches!(lang, "ini" | "toml") && line_start && ch == '[' {
            pos += rest.find('\n').unwrap_or(rest.len());
            Some("type")
        } else if c.triple && (rest.starts_with("\"\"\"") || rest.starts_with("'''")) {
            let marker = &rest[..3];
            pos += delimited(rest, marker, marker, false);
            Some("string")
        } else if c.quotes.contains(ch) && !matches!(lang, "markdown") && (lang != "xml" || xml_tag)
        {
            pos += ch.len_utf8();
            while let Some(next) = code[pos..].chars().next() {
                if matches!(next, '\n' | '\r') && ch != '`' {
                    break;
                }
                pos += next.len_utf8();
                if next == ch {
                    if matches!(lang, "sql" | "matlab" | "yaml") && code[pos..].starts_with(ch) {
                        pos += ch.len_utf8();
                    } else {
                        break;
                    }
                } else if next == '\\'
                    && !(lang == "toml" && ch == '\'')
                    && !(lang == "go" && ch == '`')
                {
                    if let Some(escaped) = code[pos..].chars().next() {
                        pos += escaped.len_utf8();
                    }
                }
            }
            Some(
                if lang == "json" && code[pos..].trim_start().starts_with(':') {
                    "property"
                } else {
                    "string"
                },
            )
        } else if lang == "xml" && ch == '<' {
            xml_tag = true;
            pos += 1;
            while code[pos..].starts_with(['/', '!', '?']) {
                pos += 1;
            }
            while code[pos..]
                .chars()
                .next()
                .is_some_and(|v| v.is_alphanumeric() || "_:-".contains(v))
            {
                pos += code[pos..].chars().next().unwrap().len_utf8();
            }
            Some("tag")
        } else if lang == "xml" && ch == '>' {
            xml_tag = false;
            pos += 1;
            Some("tag")
        } else if lang == "xml" && !xml_tag {
            pos += ch.len_utf8();
            None
        } else if ch.is_ascii_digit() {
            pos += 1;
            while let Some(next) = code.as_bytes().get(pos) {
                if next.is_ascii_alphanumeric()
                    || *next == b'_'
                    || (*next == b'.'
                        && code.as_bytes().get(pos + 1).is_some_and(u8::is_ascii_digit))
                    || (matches!(next, b'+' | b'-')
                        && matches!(code.as_bytes()[pos - 1], b'e' | b'E'))
                {
                    pos += 1;
                } else {
                    break;
                }
            }
            Some("number")
        } else if ch == '_'
            || ch.is_alphabetic()
            || (ch == '$' && matches!(lang, "php" | "javascript" | "graphql" | "scss" | "nginx"))
        {
            pos += ch.len_utf8();
            while code[pos..].chars().next().is_some_and(|v| {
                v == '_'
                    || v.is_alphanumeric()
                    || (v == '-'
                        && matches!(
                            lang,
                            "css" | "scss" | "xml" | "yaml" | "properties" | "caddy"
                        ))
            }) {
                pos += code[pos..].chars().next().unwrap().len_utf8();
            }
            let word = &code[start..pos];
            Some(
                if c.keywords.split_ascii_whitespace().any(|k| {
                    if c.insensitive {
                        k.eq_ignore_ascii_case(word)
                    } else {
                        k == word
                    }
                }) {
                    "keyword"
                } else if lang == "xml"
                    || (matches!(
                        lang,
                        "yaml" | "toml" | "ini" | "properties" | "css" | "scss"
                    ) && code[pos..].trim_start().starts_with([':', '=']))
                {
                    "property"
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
            } else if "+-*/%=!<>&|^~?@$".contains(ch) {
                Some("operator")
            } else {
                None
            }
        };
        let token = &code[start..pos];
        let end = offset + utf16_len(token);
        if let Some(scope) = scope {
            spans.push(HighlightSpan {
                start: offset,
                end,
                scope: scope.into(),
            });
        }
        if let Some((_, tail)) = token.rsplit_once('\n') {
            line_start = tail.chars().all(char::is_whitespace);
        } else if !token.chars().all(char::is_whitespace) {
            line_start = false;
        }
        offset = end;
    }
    spans
}

#[cfg(test)]
mod tests {
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
        ] {
            let encoded: Vec<_> = source.encode_utf16().collect();
            assert!(
                highlight(source, lang).iter().any(|s| s.scope == scope
                    && String::from_utf16(&encoded[s.start as usize..s.end as usize]).unwrap()
                        == expected),
                "{lang}: {expected}"
            );
        }
    }
}
