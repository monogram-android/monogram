//! Shared lexical scanner: small language settings instead of generated parse tables.
use crate::{highlight::HighlightSpan, utf16::utf16_len};

struct Config {
    keywords: &'static str,
    comments: &'static [&'static str],
    block: Option<(&'static str, &'static str)>,
    quotes: &'static str,
    /// `"""` multiline strings
    triple: bool,
    /// also `'''` (Python, TOML, …); Java/Swift/Kotlin only use `"""`
    triple_single: bool,
    insensitive: bool,
    nested_block: bool,
    /// `#` / `//` comments only when they start a line (ini, dockerfile, …)
    comment_at_line_start: bool,
    /// `#include` / `#define` at line start → keyword, rest of line still scanned
    preprocessor: bool,
    /// treat this language as XML-like markup (tags, attributes, CDATA)
    markup: bool,
    /// `r"…"`, `f"…"`, `b"…"`, `u"…"`, `rf"…"` prefixes before quotes
    string_prefixes: bool,
}

pub(crate) fn normalize_lang(lang: &str) -> &str {
    match lang {
        "js" | "jsx" | "mjs" | "cjs" | "javascriptreact" | "ts" | "tsx" | "mts" | "cts"
        | "typescript" | "typescriptreact" => "javascript",
        "py" | "pyi" | "pyw" | "python3" => "python",
        "rs" => "rust",
        "c++" | "cc" | "cxx" | "hpp" | "hxx" | "hh" | "cpp" => "cpp",
        "h" => "c",
        "cs" | "c#" => "csharp",
        "kt" | "kts" => "kotlin",
        "sh" | "zsh" | "bash" | "shell" | "ksh" => "bash",
        "htm" | "vue" | "svelte" | "svg" => "html",
        "yml" => "yaml",
        "md" | "mdown" | "mdx" => "markdown",
        "ps1" | "psm1" | "psd1" | "pwsh" => "powershell",
        "mk" | "make" | "gnumakefile" => "makefile",
        "tf" | "hcl" => "terraform",
        "rb" => "ruby",
        "hs" => "haskell",
        "pl" | "pm" => "perl",
        "ex" | "exs" => "elixir",
        "jl" => "julia",
        "sol" => "solidity",
        "golang" => "go",
        "docker" => "dockerfile",
        "protobuf" | "textproto" => "proto",
        "sass" => "scss",
        "objc" | "objective-c" | "objectivec" => "objc",
        "fs" | "fsx" | "f#" => "fsharp",
        "ml" | "mli" => "ocaml",
        "m" => "matlab",
        "s" => "asm",
        "gql" => "graphql",
        "regexp" => "regex",
        "clj" | "cljs" | "cljc" => "clojure",
        "cmakelists" => "cmake",
        "json5" => "jsonc",
        "patch" => "diff",
        "mysql" | "postgres" | "postgresql" | "plsql" | "tsql" => "sql",
        "text" | "txt" | "plaintext" => "plaintext",
        other => other,
    }
}

fn config(lang: &str) -> Config {
    let mut c = Config {
        keywords: "",
        comments: &["//"],
        block: Some(("/*", "*/")),
        quotes: "\"'",
        triple: false,
        triple_single: false,
        insensitive: false,
        nested_block: false,
        comment_at_line_start: false,
        preprocessor: false,
        markup: false,
        string_prefixes: false,
    };
    c.keywords = match lang {
        "javascript" => {
            "as async await break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in infer instanceof interface is keyof let module namespace never new null of package private protected public readonly return satisfies set static super switch this throw true try type typeof undefined unknown using var void while with yield"
        }
        "python" => {
            "and as assert async await break case class continue def del elif else except False finally for from global if import in is lambda match None nonlocal not or pass raise return True try type while with yield"
        }
        "go" => {
            "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil"
        }
        "swift" => {
            "actor any as associatedtype async await break case catch class continue convenience default defer deinit didSet do dynamic else enum extension fallthrough false fileprivate final for func get guard if import in indirect init inout internal is isolated lazy let mutating nil nonisolated open operator optional override private protocol public repeat required rethrows return self Self set some static struct subscript super switch throw throws true try typealias unowned var weak where while willSet"
        }
        "java" => {
            "abstract assert boolean break byte case catch char class const continue default do double else enum exports extends false final finally float for if implements import instanceof int interface long module native new null package permits private protected public record requires return sealed short static strictfp super switch synchronized this throw throws transient true try var void volatile while yield"
        }
        "sql" => {
            "select from where insert into values update set delete create alter drop table index view join inner left right full outer on as and or not null is in exists like between group by order having limit offset union all distinct asc desc primary key foreign references constraint default case when then else end begin commit rollback with returning true false merge truncate grant revoke window over partition rows range unbounded preceding following fetch next only except intersect apply pivot unpivot any some cast coalesce nullif current_date current_timestamp recursive cross natural using"
        }
        "json" | "jsonc" => "true false null",
        "dockerfile" => {
            "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as"
        }
        "yaml" => "true false null yes no on off",
        "toml" => "true false inf nan",
        "php" => {
            "abstract and array as break callable case catch class clone const continue declare default die do echo else elseif empty endfor endforeach endif endswitch endwhile enum eval exit extends false final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new null or print private protected public readonly require require_once return static switch throw trait true try unset use var while xor yield"
        }
        "ruby" => {
            "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield"
        }
        "lua" => {
            "and break do else elseif end false for function goto if in local nil not or repeat return then true until while"
        }
        "haskell" => {
            "as case class data default deriving do else family forall foreign hiding if import in infix infixl infixr instance let module newtype of qualified then type where True False"
        }
        "r" => {
            "if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA NA_integer_ NA_real_ NA_complex_ NA_character_"
        }
        "matlab" => {
            "break case catch classdef continue else elseif end enumeration events for function global if methods otherwise parfor persistent properties return spmd switch try while true false"
        }
        "asm" => {
            "mov movq movl movz lea ldr str push pop add sub mul imul div idiv and or xor not neg cmp test jmp je jne jz jnz jg jl jge jle call ret nop syscall int section global extern db dw dd dq equ align bits inc dec shl shr rax rbx rcx rdx rsp rbp rsi rdi eax ebx ecx edx esp ebp esi edi"
        }
        "proto" => {
            "syntax edition import weak public package option optional required repeated oneof map reserved extensions to max enum message service rpc returns stream extend true false double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes"
        }
        "graphql" => {
            "query mutation subscription fragment on schema scalar type interface union enum input extend directive implements repeatable true false null"
        }
        "css" | "scss" | "less" => {
            "important inherit initial unset revert none auto transparent currentcolor true false null if else for each while from through to in mixin include function return extend use forward media supports keyframes import charset namespace font-face layer container when"
        }
        "nginx" => {
            "http server location upstream events stream map geo limit_except if set listen server_name root index try_files proxy_pass proxy_set_header return rewrite include worker_processes worker_connections access_log error_log ssl_certificate ssl_certificate_key on off"
        }
        "caddy" => {
            "import log debug email auto_https tls root file_server reverse_proxy redir rewrite respond handle handle_path route header encode templates php_fastcgi request_body bind basic_auth forward_auth errors metrics on off"
        }
        "rust" => {
            "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while abstract become box do final gen macro override priv try typeof unsized virtual yield union"
        }
        "c" => {
            "alignas alignof auto bool break case char const constexpr continue default do double else enum extern false float for goto if inline int long nullptr register restrict return short signed sizeof static static_assert struct switch thread_local true typedef typeof typeof_unqual union unsigned void volatile while _Alignas _Alignof _Atomic _BitInt _Bool _Complex _Generic _Imaginary _Noreturn _Static_assert _Thread_local"
        }
        "cpp" => {
            "alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t char32_t class compl concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq override final import module"
        }
        "csharp" => {
            "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while add alias and args ascending async await by descending dynamic equals from get global group init into join let managed nameof nint not notnull nuint on or orderby partial record remove required scoped select set unmanaged value var when where with yield file"
        }
        "kotlin" => {
            "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while abstract actual annotation by catch companion const constructor crossinline data delegate dynamic enum expect external final finally get import infix init inline inner internal lateinit noinline open operator out override private protected public reified sealed set suspend tailrec value vararg where"
        }
        "dart" => {
            "abstract as assert async await base break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for Function get hide if implements import in interface is late library mixin new null on operator part required rethrow return sealed set show static super switch sync this throw true try typedef var void when while with yield"
        }
        "zig" => {
            "addrspace align allowzero and anyframe anytype asm async await break callconv catch comptime const continue defer else enum errdefer error export extern false fn for if inline noalias nosuspend noinline null opaque or orelse packed pub resume return linksection struct suspend switch test threadlocal true try undefined union unreachable var volatile while anyerror anyopaque"
        }
        "elixir" => {
            "true false nil when and or not in fn do end catch rescue after else alias case cond def defdelegate defexception defguard defguardp defimpl defmacro defmacrop defmodule defoverridable defp defprotocol defstruct destructure for if import quote raise receive require try unless unquote use with"
        }
        "scala" => {
            "abstract case catch class def do else enum export extends false final finally for forSome given if implicit import lazy match new null object override package private protected return sealed super then this throw trait true try type val var while with yield as derives end extension infix inline opaque open transparent using"
        }
        "bash" => {
            "if then else elif fi case esac for while until do done function select time coproc in break continue return exit echo read cd export local readonly declare typeset unset shift eval exec source trap wait alias set test let true false"
        }
        "powershell" => {
            "begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach from function hidden if in inlinescript param process return static switch throw trap try until using var while workflow parallel sequence configuration and or not xor as is $true $false $null"
        }
        "makefile" => {
            "include define endef undefine ifdef ifndef ifeq ifneq else endif export unexport override private vpath load"
        }
        "cmake" => {
            "if else elseif endif foreach endforeach while endwhile function endfunction macro endmacro return break continue set unset option project include find_package add_executable add_library add_subdirectory add_custom_command add_custom_target target_link_libraries target_include_directories target_compile_definitions message cmake_minimum_required enable_testing add_test install export list string math"
        }
        "terraform" => {
            "resource provider variable output module data locals terraform for_each count depends_on lifecycle provisioner connection dynamic true false null for in if else endif source version"
        }
        "perl" => {
            "and cmp continue core do else elsif eq exp for foreach ge gt if le lock lt m ne no or package q qq qr qw qx s sub tr unless until while xor y my our local state use require given when default say next last redo goto return eval die warn"
        }
        "julia" => {
            "abstract baremodule begin break catch const continue do else elseif end export false finally for function global if import in isa let local macro module mutable primitive quote return struct true try type using while where nothing missing"
        }
        "solidity" => {
            "pragma contract library interface abstract is using import function modifier event struct enum mapping address bool string bytes uint int public private internal external payable view pure constant immutable virtual override returns return if else for while do break continue throw emit this super try catch assembly memory storage calldata new delete true false indexed anonymous constructor fallback receive unchecked from as"
        }
        "objc" => {
            "alignas alignof auto bool break case char const constexpr continue default do double else enum extern false float for goto if inline int long nullptr register restrict return short signed sizeof static static_assert struct switch thread_local true typedef union unsigned void volatile while id self super nil Nil YES NO BOOL SEL IMP nonatomic atomic retain strong weak assign copy readonly readwrite getter setter interface implementation protocol end property synthesize dynamic selector encode try catch throw finally synchronized autoreleasepool class optional required import"
        }
        "ocaml" => {
            "and as assert asr begin class constraint do done downto else end exception external false for fun function functor if in include inherit initializer land lazy let lor lsl lsr lxor match method mod module mutable new nonrec object of open or private rec sig struct then to true try type val virtual when while with"
        }
        "fsharp" => {
            "abstract and as assert base begin class default delegate do done downcast downto elif else end exception extern false finally for fun function global if in inherit inline interface internal lazy let match member module mutable namespace new null of open or override rec return select static struct then to true try type upcast use val void when while with yield async await"
        }
        "clojure" => {
            "and or not if do let letfn fn def defn defn- defmacro defonce defmulti defmethod defprotocol defrecord deftype loop recur quote var if-not when when-not cond condp case try catch finally throw new set ns in-ns require import use refer alias declare true false nil"
        }
        "nim" => {
            "addr and as asm bind block break case cast concept const continue converter defer discard distinct div do elif else end enum except export finally for from func if import in include interface is isnot iterator let macro method mixin mod nil not notin object of or out proc ptr raise ref return shl shr static template try tuple type using var when while xor yield"
        }
        "groovy" => {
            "abstract as assert break case catch class const continue def default do else enum extends false final finally for goto if implements import in instanceof interface native new null package private protected public return static super switch synchronized this throw throws transient true try void volatile while trait var record sealed permits"
        }
        // Tag names are classified by the markup scanner (`<div` → tag).
        // Putting them in keywords would only fire on attributes inside a tag
        // (`title`, `label`, `style`…), stealing the `property` scope.
        "html" => "",
        _ => "",
    };
    match lang {
        "python" | "ruby" | "r" | "yaml" | "toml" | "dockerfile" | "graphql" | "nginx"
        | "caddy" | "bash" | "makefile" | "cmake" | "perl" | "elixir" => {
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
            c.nested_block = true;
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
            c.comment_at_line_start = true;
        }
        "properties" => {
            c.comments = &["#", "!"];
            c.block = None;
            c.quotes = "";
            c.comment_at_line_start = true;
        }
        "xml" | "markdown" | "html" => {
            c.comments = &[];
            c.block = Some(("<!--", "-->"));
        }
        "json" => {
            c.comments = &[];
            c.block = None;
            c.quotes = "\"";
        }
        "jsonc" => {
            c.quotes = "\"";
        }
        "css" => c.comments = &[],
        "php" => c.comments = &["//", "#"],
        "powershell" => {
            c.comments = &["#"];
            c.block = Some(("<#", "#>"));
            c.insensitive = true;
        }
        "zig" => {
            c.block = None;
            c.quotes = "\"";
        }
        "clojure" => {
            c.comments = &[";"];
            c.block = None;
            c.quotes = "\"";
        }
        "ocaml" => {
            c.comments = &[];
            c.block = Some(("(*", "*)"));
            c.nested_block = true;
        }
        "fsharp" => {
            c.comments = &["//"];
            c.block = Some(("(*", "*)"));
            c.nested_block = true;
        }
        "julia" => {
            c.comments = &["#"];
            c.block = Some(("#=", "=#"));
            c.nested_block = true;
        }
        "nim" => {
            c.comments = &["#"];
            c.block = Some(("#[", "]#"));
            c.nested_block = true;
        }
        "terraform" => {
            c.comments = &["#", "//"];
            c.block = Some(("/*", "*/"));
        }
        "plaintext" | "diff" => {
            c.comments = &[];
            c.block = None;
            c.quotes = "";
        }
        _ => {}
    }
    c.triple = matches!(
        lang,
        "python"
            | "swift"
            | "java"
            | "toml"
            | "graphql"
            | "kotlin"
            | "scala"
            | "dart"
            | "elixir"
            | "julia"
            | "groovy"
            | "csharp"
    );
    c.triple_single = matches!(lang, "python" | "toml" | "elixir" | "dart");
    if matches!(lang, "javascript" | "go" | "r" | "bash" | "perl" | "elixir") {
        c.quotes = "\"'`";
    }
    if matches!(lang, "dockerfile" | "yaml" | "powershell" | "cmake") {
        c.insensitive = true;
    }
    if matches!(lang, "ini" | "properties" | "dockerfile") {
        c.comment_at_line_start = true;
    }
    if matches!(lang, "c" | "cpp" | "objc" | "csharp") {
        c.preprocessor = true;
    }
    if matches!(lang, "swift" | "kotlin" | "rust") {
        c.nested_block = true;
    }
    if matches!(lang, "xml" | "html") {
        c.markup = true;
    }
    if matches!(lang, "python" | "rust") {
        c.string_prefixes = true;
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

/// `r"…"`, `r#"…"#`, `br#"…"#`, `cr##"…"##`
fn rust_raw(code: &str) -> Option<usize> {
    let mut prefix = 0;
    let mut rest = code;
    if rest.starts_with('b') || rest.starts_with('c') {
        rest = &rest[1..];
        prefix = 1;
    }
    let rest = rest.strip_prefix('r')?;
    prefix += 1;
    let hashes = rest.bytes().take_while(|b| *b == b'#').count();
    if rest.as_bytes().get(hashes) != Some(&b'"') {
        return None;
    }
    let open_len = prefix + 1 + hashes;
    let close = format!("\"{}", "#".repeat(hashes));
    Some(delimited(code, &code[..open_len], &close, false))
}

/// Length of a Python/Rust string prefix sitting immediately before a quote.
fn string_prefix(rest: &str, lang: &str) -> usize {
    let b = rest.as_bytes();
    if lang == "python" {
        let is_pfx = |c: u8| matches!(c | 32, b'r' | b'f' | b'b' | b'u');
        if b.len() >= 3 && is_pfx(b[0]) && is_pfx(b[1]) && matches!(b[2], b'\'' | b'"') {
            2
        } else if b.len() >= 2 && is_pfx(b[0]) && matches!(b[1], b'\'' | b'"') {
            1
        } else {
            0
        }
    } else if lang == "rust" {
        if matches!(b, [b'b' | b'c', b'"', ..]) {
            1
        } else {
            0
        }
    } else {
        0
    }
}

fn scan_quoted(code: &str, mut pos: usize, quote: char, lang: &str) -> usize {
    while let Some(next) = code[pos..].chars().next() {
        if matches!(next, '\n' | '\r') && quote != '`' {
            break;
        }
        pos += next.len_utf8();
        if next == quote {
            if matches!(lang, "sql" | "matlab" | "yaml") && code[pos..].starts_with(quote) {
                pos += quote.len_utf8();
            } else {
                break;
            }
        } else if next == '\\'
            && !(lang == "toml" && quote == '\'')
            && !(lang == "go" && quote == '`')
        {
            if let Some(escaped) = code[pos..].chars().next() {
                pos += escaped.len_utf8();
            }
        }
    }
    pos
}

fn is_keyword(keywords: &[&str], word: &str, insensitive: bool) -> bool {
    keywords.iter().any(|k| {
        if insensitive {
            k.eq_ignore_ascii_case(word)
        } else {
            *k == word
        }
    })
}

pub fn highlight(code: &str, lang: &str) -> Vec<HighlightSpan> {
    let lang = normalize_lang(lang);
    let c = config(lang);
    let keywords: Vec<&str> = c.keywords.split_ascii_whitespace().collect();
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
        let prefix = if c.string_prefixes {
            string_prefix(rest, lang)
        } else {
            0
        };
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
        } else if lang == "diff" && line_start {
            pos += rest.find('\n').unwrap_or(rest.len());
            if rest.starts_with("+++")
                || rest.starts_with("---")
                || rest.starts_with("diff")
                || rest.starts_with("index")
                || rest.starts_with("@@")
            {
                Some("keyword")
            } else if rest.starts_with('+') {
                Some("string")
            } else if rest.starts_with('-') {
                Some("comment")
            } else {
                None
            }
        } else if lang == "lua" && rest.starts_with("--[") {
            if let Some(n) = lua_long(&rest[2..]) {
                pos += 2 + n;
                Some("comment")
            } else {
                pos += rest.find('\n').unwrap_or(rest.len());
                Some("comment")
            }
        } else if let Some((open, close)) = block {
            pos += delimited(rest, open, close, c.nested_block);
            Some("comment")
        } else if c.comments.iter().any(|p| rest.starts_with(p))
            && (!c.comment_at_line_start || line_start)
            && (lang != "yaml" || start == 0 || code[..start].ends_with(char::is_whitespace))
        {
            pos += rest.find('\n').unwrap_or(rest.len());
            Some("comment")
        } else if let Some(n) = (lang == "lua").then(|| lua_long(rest)).flatten() {
            pos += n;
            Some("string")
        } else if let Some(n) = (lang == "rust").then(|| rust_raw(rest)).flatten() {
            pos += n;
            Some("string")
        } else if lang == "rust" && ch == '\'' {
            let p = pos + 1;
            if code[p..].starts_with('\\') {
                pos = scan_quoted(code, p, '\'', lang);
                Some("string")
            } else if code.as_bytes().get(p + 1) == Some(&b'\'') {
                pos = p + 2;
                Some("string")
            } else if code[p..]
                .chars()
                .next()
                .is_some_and(|v| v.is_alphabetic() || v == '_')
            {
                pos = p;
                while code[pos..]
                    .chars()
                    .next()
                    .is_some_and(|v| v.is_alphanumeric() || v == '_')
                {
                    pos += code[pos..].chars().next().unwrap().len_utf8();
                }
                Some("type")
            } else {
                pos += 1;
                None
            }
        } else if c.markup && rest.starts_with("<![CDATA[") {
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
        } else if c.preprocessor && line_start && ch == '#' {
            pos += 1;
            while code[pos..]
                .chars()
                .next()
                .is_some_and(|v| v == ' ' || v == '\t')
            {
                pos += 1;
            }
            while code[pos..]
                .chars()
                .next()
                .is_some_and(|v| v.is_ascii_alphanumeric() || v == '_')
            {
                pos += code[pos..].chars().next().unwrap().len_utf8();
            }
            Some("keyword")
        } else if c.triple
            && (rest[prefix..].starts_with("\"\"\"")
                || (c.triple_single && rest[prefix..].starts_with("'''")))
        {
            pos += prefix;
            let marker = &code[pos..pos + 3];
            pos += delimited(&code[pos..], marker, marker, false);
            Some("string")
        } else if (c.quotes.contains(ch)
            || (prefix > 0
                && c.quotes
                    .contains(rest[prefix..].chars().next().unwrap_or('\0'))))
            && !matches!(lang, "markdown")
            && (!c.markup || xml_tag)
        {
            pos += prefix;
            let quote = code[pos..].chars().next().unwrap();
            pos += quote.len_utf8();
            pos = scan_quoted(code, pos, quote, lang);
            Some(
                if matches!(lang, "json" | "jsonc") && code[pos..].trim_start().starts_with(':') {
                    "property"
                } else {
                    "string"
                },
            )
        } else if c.markup && ch == '<' {
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
        } else if c.markup && ch == '>' {
            xml_tag = false;
            pos += 1;
            Some("tag")
        } else if c.markup && !xml_tag {
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
            || (ch == '$'
                && matches!(
                    lang,
                    "php"
                        | "javascript"
                        | "graphql"
                        | "scss"
                        | "less"
                        | "nginx"
                        | "bash"
                        | "powershell"
                        | "perl"
                ))
            || (ch == '#' && matches!(lang, "javascript"))
            || (ch == '@' && matches!(lang, "less"))
        {
            pos += ch.len_utf8();
            while code[pos..].chars().next().is_some_and(|v| {
                v == '_'
                    || v.is_alphanumeric()
                    || (v == '-'
                        && matches!(
                            lang,
                            "css"
                                | "scss"
                                | "less"
                                | "xml"
                                | "html"
                                | "yaml"
                                | "properties"
                                | "caddy"
                        ))
            }) {
                pos += code[pos..].chars().next().unwrap().len_utf8();
            }
            let word = &code[start..pos];
            Some(if is_keyword(&keywords, word, c.insensitive) {
                "keyword"
            } else if c.markup
                || (matches!(
                    lang,
                    "yaml" | "toml" | "ini" | "properties" | "css" | "scss" | "less"
                ) && code[pos..].trim_start().starts_with([':', '=']))
            {
                "property"
            } else if code[pos..].trim_start().starts_with('(') {
                "function"
            } else {
                "variable"
            })
        } else {
            pos += ch.len_utf8();
            if "(){}[]".contains(ch) {
                Some("punctuation.bracket")
            } else if ",;.:".contains(ch) {
                Some("punctuation.delimiter")
            } else if "+-*/%=!<>&|^~?@$#".contains(ch) {
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
}
