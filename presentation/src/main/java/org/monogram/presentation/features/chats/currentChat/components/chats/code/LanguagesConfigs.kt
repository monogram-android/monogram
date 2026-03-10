package org.monogram.presentation.features.chats.currentChat.components.chats.code

data class LanguageConfig(
    val keywords: Set<String>,
    val types: Set<String>,
    val constants: Set<String>,
    val supportsAnnotations: Boolean,
    val singleLineComment: List<String>,
    val blockStart: String?,
    val blockEnd: String?,
    val caseInsensitiveKeywords: Boolean = false
)

// bash
val bashConfig = LanguageConfig(
    keywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function"
    ),
    types = emptySet(),
    constants = setOf("true", "false"),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = null,
    blockEnd = null
)

// docker
val dockerConfig = LanguageConfig(
    keywords = setOf(
        "FROM", "RUN", "CMD", "ENTRYPOINT", "COPY", "ADD", "ENV", "EXPOSE", "VOLUME", "WORKDIR",
        "USER", "LABEL", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = null,
    blockEnd = null
)

// yaml
val yamlConfig = LanguageConfig(
    keywords = setOf(
        "%YAML", "%TAG", "---", "..."
    ),
    types = setOf(
        "string", "int", "float", "bool", "null", "sequence", "mapping",
        "timestamp", "binary", "merge"
    ),
    constants = setOf(
        "true", "false", "null", "~", "yes", "no", "on", "off"
    ),
    supportsAnnotations = true,
    singleLineComment = listOf("#"),
    blockStart = "|",
    blockEnd = ">"
)

// markdown
val markdownConfig = LanguageConfig(
    keywords = setOf("#", "##", "###", "####", "#####", "######", "*", "-", "_", "`", "```"),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = emptyList(),
    blockStart = null,
    blockEnd = null
)

// ini
val iniConfig = LanguageConfig(
    keywords = setOf(
        "[", "]", "=", ":"
    ),
    types = setOf(
        "string", "int", "float", "bool", "list"
    ),
    constants = setOf(
        "true", "false", "yes", "no", "on", "off", "null", "~"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf(";", "#"),
    blockStart = null,
    blockEnd = null
)

// toml
val tomlConfig = LanguageConfig(
    keywords = setOf(
        "table", "array", "datetime", "inline-table", "[", "[]", "[[", "]]"
    ),
    types = setOf(
        "string", "int", "float", "bool", "datetime", "array", "table", "inline-table"
    ),
    constants = setOf(
        "true", "false", "nan", "inf", "-inf"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = "\"\"\"",
    blockEnd = "\"\"\""
)

// properties
val propertiesConfig = LanguageConfig(
    keywords = setOf(
        "=", ":", "\\", " "
    ),
    types = setOf(
        "string", "int", "float", "bool"
    ),
    constants = setOf(
        "true", "false", "yes", "no", "on", "off"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf("#", "!"),
    blockStart = null,
    blockEnd = null
)

// php
val phpConfig = LanguageConfig(
    keywords = setOf(
        "function", "class", "interface", "trait", "extends", "implements", "public", "private",
        "protected", "static", "final", "return", "if", "else", "elseif", "for", "foreach", "while",
        "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new",
        "use", "namespace", "global", "var"
    ),
    types = setOf("int", "float", "string", "bool", "array", "object", "callable", "mixed"),
    constants = setOf("true", "false", "null"),
    supportsAnnotations = false,
    singleLineComment = listOf("//", "#"),
    blockStart = "/*",
    blockEnd = "*/"
)

// ruby
val rubyConfig = LanguageConfig(
    keywords = setOf(
        "def", "class", "module", "if", "elsif", "else", "end", "while", "for", "in", "do", "return",
        "yield", "begin", "rescue", "ensure", "case", "when", "require", "include", "extend"
    ),
    types = emptySet(),
    constants = setOf("true", "false", "nil"),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = null,
    blockEnd = null
)

// lua
val luaConfig = LanguageConfig(
    keywords = setOf(
        "function", "local", "if", "then", "else", "elseif", "end", "for", "while", "repeat", "until",
        "return", "break", "in", "goto"
    ),
    types = emptySet(),
    constants = setOf("true", "false", "nil"),
    supportsAnnotations = false,
    singleLineComment = listOf("--"),
    blockStart = "--[[",
    blockEnd = "]]"
)

// haskell
val haskellConfig = LanguageConfig(
    keywords = setOf(
        "module", "import", "where", "let", "in", "case", "of", "data", "type", "class", "instance",
        "deriving", "do", "if", "then", "else"
    ),
    types = emptySet(),
    constants = setOf("True", "False"),
    supportsAnnotations = false,
    singleLineComment = listOf("--"),
    blockStart = "{-",
    blockEnd = "-}"
)

// r
val rConfig = LanguageConfig(
    keywords = setOf(
        "function", "if", "else", "for", "while", "repeat", "break", "next", "return"
    ),
    types = emptySet(),
    constants = setOf("TRUE", "FALSE", "NULL", "NA"),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = null,
    blockEnd = null
)

// matlab
val matlabConfig = LanguageConfig(
    keywords = setOf(
        "function", "end", "if", "else", "elseif", "for", "while", "switch", "case", "otherwise",
        "return", "break", "continue", "try", "catch"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = listOf("%"),
    blockStart = "%{",
    blockEnd = "%}"
)

// asm
val asmConfig = LanguageConfig(
    keywords = setOf(
        "mov", "add", "sub", "mul", "div", "jmp", "je", "jne", "jg", "jl", "call", "ret", "push", "pop"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = listOf(";"),
    blockStart = null,
    blockEnd = null
)

// proto
val protoConfig = LanguageConfig(
    keywords = setOf(
        "syntax", "message", "enum", "package", "import", "option", "service", "rpc", "returns"
    ),
    types = setOf("int32", "int64", "string", "bool", "bytes", "float", "double"),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// graphql
val graphqlConfig = LanguageConfig(
    keywords = setOf(
        "type", "query", "mutation", "subscription", "schema", "interface", "union", "enum", "input"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = null,
    blockEnd = null
)

// css
val cssConfig = LanguageConfig(
    keywords = setOf(
        "color", "background", "margin", "padding", "border", "display", "flex", "grid", "position",
        "absolute", "relative", "fixed", "font", "width", "height", "content"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = emptyList(),
    blockStart = null,
    blockEnd = null
)

// scss
val scssConfig = LanguageConfig(
    keywords = setOf(
        "@mixin", "@include", "@import", "@use", "@extend", "@if", "@else", "@for", "@each", "@while"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// regex
val regexConfig = LanguageConfig(
    keywords = setOf(
        "\\d", "\\w", "\\s", "\\b", "\\B", ".", "*", "+", "?", "|", "(", ")", "[", "]", "{", "}"
    ),
    types = emptySet(),
    constants = emptySet(),
    supportsAnnotations = false,
    singleLineComment = emptyList(),
    blockStart = null,
    blockEnd = null
)

// Kotlin
val kotlinConfig = LanguageConfig(
    keywords = setOf(
        "fun", "val", "var", "class", "object", "if", "else", "for", "while", "return", "package", "import",
        "private", "public", "protected", "override", "data", "sealed", "interface", "super", "this",
        "try", "catch", "finally", "throw", "when", "is", "in", "companion", "suspend", "inline", "noinline",
        "crossinline", "reified", "operator", "infix", "enum", "open", "lateinit", "const", "typealias"
    ),
    types = setOf("Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String", "List", "Map", "Set"),
    constants = setOf("true", "false", "null"),
    supportsAnnotations = true,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// Java / C / C++ / C#
val cLikeConfig = LanguageConfig(
    keywords = setOf(
        "public", "private", "protected", "class", "interface", "enum", "static", "final", "void", "int",
        "double", "float", "char", "boolean", "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "return", "new", "this", "super", "try", "catch", "throw", "throws", "namespace", "using",
        "struct", "include", "extends", "implements"
    ),
    types = setOf(
        "int", "long", "short", "byte", "float", "double", "boolean", "char", "String", "List", "Map", "Set",
        "void"
    ),
    constants = setOf("true", "false", "null"),
    supportsAnnotations = true,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// Python
val pythonConfig = LanguageConfig(
    keywords = setOf(
        "def", "class", "if", "else", "elif", "for", "while", "return", "import", "from", "as", "try", "except",
        "finally", "with", "lambda", "global", "nonlocal", "pass", "raise", "yield", "and", "or", "not", "is", "in"
    ),
    types = emptySet(),
    constants = setOf("None", "True", "False"),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = null,
    blockEnd = null
)

// JavaScript / TypeScript
val jsTsConfig = LanguageConfig(
    keywords = setOf(
        "function", "const", "let", "var", "class", "if", "else", "for", "while", "return", "import", "export",
        "from", "async", "await", "try", "catch", "throw", "new", "this", "typeof", "instanceof", "void",
        "delete", "interface", "type", "enum", "extends", "implements"
    ),
    types = setOf("string", "number", "boolean", "any", "unknown", "never", "void", "Array", "Promise", "Record"),
    constants = setOf("true", "false", "null", "undefined"),
    supportsAnnotations = false,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// Go
val goConfig = LanguageConfig(
    keywords = setOf(
        "func", "var", "const", "type", "struct", "interface", "package", "import", "return", "if", "else",
        "for", "range", "go", "defer", "switch", "case", "default", "map", "chan", "select"
    ),
    types = setOf(
        "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
        "float32", "float64", "string", "bool", "byte", "rune"
    ),
    constants = setOf("true", "false", "nil", "iota"),
    supportsAnnotations = false,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// Rust
val rustConfig = LanguageConfig(
    keywords = setOf(
        "fn", "let", "mut", "if", "else", "loop", "while", "for", "in", "return", "break", "continue",
        "struct", "enum", "impl", "trait", "pub", "use", "mod", "match", "move", "unsafe", "where",
        "crate", "super", "self", "Self", "as", "async", "await", "dyn", "ref", "box", "const", "static",
        "extern", "type", "union", "macro_rules!"
    ),
    types = setOf(
        "i8", "i16", "i32", "i64", "i128", "isize",
        "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "bool", "char", "str"
    ),
    constants = setOf(
        "true", "false"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf("//", "///", "//!"),
    blockStart = "/*",
    blockEnd = "*/"
)

// Swift
val swiftConfig = LanguageConfig(
    keywords = setOf(
        "func", "var", "let", "if", "else", "guard", "for", "while", "repeat", "return",
        "class", "struct", "enum", "extension", "protocol", "init", "deinit", "import",
        "switch", "case", "default", "break", "continue", "fallthrough", "where",
        "as", "is", "in", "try", "catch", "throw", "throws", "rethrows", "async", "await",
        "actor", "nonisolated", "mutating", "nonmutating", "static", "final", "override",
        "public", "private", "fileprivate", "internal", "open", "convenience", "required",
        "lazy", "weak", "unowned", "associatedtype", "typealias", "subscript", "operator",
        "precedencegroup"
    ),
    types = setOf(
        "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16", "UInt32",
        "UInt64", "Float", "Double", "Bool", "Character", "String", "Array", "Dictionary",
        "Set", "Optional", "Any", "AnyObject", "CGFloat", "Decimal", "Data", "URL", "Void"
    ),
    constants = setOf(
        "true", "false", "nil"
    ),
    supportsAnnotations = true,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/",
    caseInsensitiveKeywords = false
)

// SQL
val sqlConfig = LanguageConfig(
    keywords = setOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "JOIN", "INNER", "LEFT",
        "RIGHT", "FULL", "OUTER", "CROSS", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
        "OFFSET", "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "VIEW", "TRIGGER", "FUNCTION",
        "PROCEDURE", "UNION", "ALL", "DISTINCT", "AS", "NULL", "AND", "OR", "NOT", "IN",
        "EXISTS", "BETWEEN", "LIKE", "IS", "CASE", "WHEN", "THEN", "ELSE", "END", "WITH",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "CHECK", "DEFAULT",
        "VALUES", "RETURNING", "CAST", "EXPLAIN", "ANALYZE"
    ),
    types = setOf(
        "INT", "INTEGER", "SMALLINT", "BIGINT", "SERIAL", "BIGSERIAL", "DECIMAL", "NUMERIC",
        "FLOAT", "REAL", "DOUBLE", "BOOLEAN", "BOOL", "CHAR", "VARCHAR", "TEXT", "DATE",
        "TIME", "TIMESTAMP", "TIMESTAMPTZ", "INTERVAL", "UUID", "JSON", "JSONB", "BYTEA"
    ),
    constants = setOf(
        "NULL", "TRUE", "FALSE"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf("--", "#"),
    blockStart = "/*",
    blockEnd = "*/",
    caseInsensitiveKeywords = false
)

// Java
val javaConfig = LanguageConfig(
    keywords = setOf(
        "class", "interface", "enum", "extends", "implements", "public", "private", "protected",
        "static", "final", "void", "new", "return", "if", "else", "for", "while", "switch", "case",
        "default", "break", "continue", "try", "catch", "finally", "throw", "throws", "import",
        "package", "this", "super", "instanceof", "synchronized", "volatile", "transient"
    ),
    types = setOf(
        "int", "long", "short", "byte", "float", "double", "boolean", "char", "String", "List", "Map", "Set"
    ),
    constants = setOf("true", "false", "null"),
    supportsAnnotations = true,
    singleLineComment = listOf("//"),
    blockStart = "/*",
    blockEnd = "*/"
)

// JSON
val jsonConfig = LanguageConfig(
    keywords = setOf(
        "{", "}", "[", "]", ":", ","
    ),
    types = setOf(
        "object", "array", "string", "number", "integer", "boolean", "null"
    ),
    constants = setOf(
        "true", "false", "null"
    ),
    supportsAnnotations = false,
    singleLineComment = emptyList(),
    blockStart = null,
    blockEnd = null
)

// XML/HTML
val xmlConfig = LanguageConfig(
    keywords = setOf(
        "doctype", "html", "head", "body", "title", "meta", "link", "script", "style",
        "div", "span", "p", "a", "img", "ul", "ol", "li", "table", "tr", "td", "form", "input"
    ),
    types = setOf(
        "element", "attribute", "text", "cdata", "comment", "pi"
    ),
    constants = emptySet(),
    supportsAnnotations = true,
    singleLineComment = emptyList(),
    blockStart = "<!--",
    blockEnd = "-->"
)

// nginx
val nginxConfig = LanguageConfig(
    keywords = setOf(
        "worker_processes", "events", "http", "server", "location", "listen", "server_name",
        "root", "index", "include", "try_files", "proxy_pass", "proxy_set_header", "add_header",
        "return", "rewrite", "error_page", "client_max_body_size", "gzip", "gzip_types",
        "ssl", "ssl_certificate", "ssl_certificate_key", "upstream", "fastcgi_pass",
        "fastcgi_param", "access_log", "error_log", "autoindex", "deny", "allow", "set",
        "limit_conn", "limit_req", "resolver", "proxy_cache", "proxy_cache_path"
    ),
    types = setOf(
        "directive", "block", "string", "number", "path"
    ),
    constants = setOf(
        "on", "off", "default", "none"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = "{",
    blockEnd = "}"
)

// caddy
val caddyConfig = LanguageConfig(
    keywords = setOf(
        "import", "route", "handle", "handle_path", "reverse_proxy", "file_server", "root",
        "redir", "respond", "header", "tls", "encode", "php_fastcgi", "log", "basicauth",
        "map", "templates", "bind", "transport", "route", "try_files"
    ),
    types = setOf(
        "directive", "matcher", "handler", "string", "path"
    ),
    constants = setOf(
        "on", "off", "true", "false"
    ),
    supportsAnnotations = false,
    singleLineComment = listOf("#"),
    blockStart = "{",
    blockEnd = "}"
)