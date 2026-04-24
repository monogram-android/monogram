package org.monogram.presentation.features.chats.conversation.ui.message.code

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

class CodeHighlighter {
    private interface CodeTheme {
        val Keyword: Color
        val String: Color
        val Comment: Color
        val Number: Color
        val Annotation: Color
        val Type: Color
        val Function: Color
        val Field: Color
        val Generic: Color
        val Operator: Color
        val Punctuation: Color
        val Default: Color
    }

    private object DarkTheme : CodeTheme {
        override val Keyword = Color(0xFFCC7832)
        override val String = Color(0xFF6A8759)
        override val Comment = Color(0xFF808080)
        override val Number = Color(0xFF6897BB)
        override val Annotation = Color(0xFFBBB529)
        override val Type = Color(0xFF769AA5)
        override val Function = Color(0xFFA9B7C6)
        override val Field = Color(0xFF9876AA)
        override val Generic = Color(0xFFB8D7A3)
        override val Operator = Color(0xFFD4D4D4)
        override val Punctuation = Color(0xFFBBBBBB)
        override val Default = Color(0xFFA9B7C6)
    }

    private object LightTheme : CodeTheme {
        override val Keyword = Color(0xFF0033B3)
        override val String = Color(0xFF067D17)
        override val Comment = Color(0xFF8A8A8A)
        override val Number = Color(0xFF1750EB)
        override val Annotation = Color(0xFF795E26)
        override val Type = Color(0xFF267F99)
        override val Function = Color(0xFF6F42C1)
        override val Field = Color(0xFF005CC5)
        override val Generic = Color(0xFF22863A)
        override val Operator = Color(0xFF24292E)
        override val Punctuation = Color(0xFF586069)
        override val Default = Color(0xFF24292E)
    }

    private class DynamicTheme(scheme: ColorScheme) : CodeTheme {
        override val Keyword = scheme.primary
        override val String = scheme.tertiary
        override val Comment = scheme.outline
        override val Number = scheme.secondary
        override val Annotation = scheme.primary
        override val Type = scheme.primary
        override val Function = scheme.secondary
        override val Field = scheme.tertiary
        override val Generic = scheme.secondary
        override val Operator = scheme.onSurfaceVariant
        override val Punctuation = scheme.onSurfaceVariant
        override val Default = scheme.onSurface
    }

    private enum class TokenType {
        Keyword, Type, Function, Variable, Field,
        String, Number, Comment, Annotation,
        Operator, Punctuation, GenericParameter,
        Whitespace, Other
    }

    private data class Token(val range: IntRange, val type: TokenType)

    private fun configFor(lang: String?): LanguageConfig =
        when (lang?.lowercase()) {
            "kotlin" -> kotlinConfig
            "c", "cpp", "c++", "c#", "cs" -> cLikeConfig
            "python", "py" -> pythonConfig
            "javascript", "js", "typescript", "ts" -> jsTsConfig
            "go", "golang" -> goConfig
            "rust", "rs" -> rustConfig
            "swift" -> swiftConfig
            "sql" -> sqlConfig
            "json" -> jsonConfig
            "xml", "html" -> xmlConfig
            "java" -> javaConfig
            "bash", "sh", "zsh" -> bashConfig
            "dockerfile" -> dockerConfig
            "yaml", "yml" -> yamlConfig
            "md", "markdown" -> markdownConfig
            "ini" -> iniConfig
            "toml" -> tomlConfig
            "properties" -> propertiesConfig
            "php" -> phpConfig
            "ruby", "rb" -> rubyConfig
            "lua" -> luaConfig
            "haskell", "hs" -> haskellConfig
            "r" -> rConfig
            "matlab", "m" -> matlabConfig
            "asm", "s" -> asmConfig
            "proto" -> protoConfig
            "graphql", "gql" -> graphqlConfig
            "css" -> cssConfig
            "scss", "sass" -> scssConfig
            "regex", "regexp" -> regexConfig
            "nginx" -> nginxConfig
            "caddy" -> caddyConfig
            else -> kotlinConfig
        }

    fun highlight(
        text: String,
        language: String?,
        isDark: Boolean = true,
        scheme: ColorScheme? = null
    ): AnnotatedString = buildAnnotatedString {
        val theme = when {
            scheme != null -> DynamicTheme(scheme)
            isDark -> DarkTheme
            else -> LightTheme
        }
        withStyle(SpanStyle(color = theme.Default, fontFamily = FontFamily.Monospace)) {
            append(text)
        }

        val cfg = configFor(language)
        val tokens = tokenize(text, cfg)

        tokens.forEach { token ->
            val style = styleFor(token.type, theme) ?: return@forEach
            addStyle(style, token.range.first, token.range.last + 1)
        }
    }

    private fun tokenize(text: String, cfg: LanguageConfig): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = text.length

        fun isId(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        fun starts(prefix: String) = text.startsWith(prefix, i)

        while (i < n) {
            val c = text[i]

            // whitespace
            if (c.isWhitespace()) {
                val s = i
                while (i < n && text[i].isWhitespace()) i++
                tokens += Token(s until i, TokenType.Whitespace)
                continue
            }

            // single-line comment
            val sl = cfg.singleLineComment.firstOrNull { starts(it) }
            if (sl != null) {
                val s = i
                i += sl.length
                while (i < n && text[i] != '\n') i++
                tokens += Token(s until i, TokenType.Comment)
                continue
            }

            // block comment
            if (cfg.blockStart != null && cfg.blockEnd != null && starts(cfg.blockStart)) {
                val s = i
                i += cfg.blockStart.length
                val end = text.indexOf(cfg.blockEnd, i).let { if (it == -1) n else it + cfg.blockEnd.length }
                i = end
                tokens += Token(s until i, TokenType.Comment)
                continue
            }

            // strings
            if (c == '"' || c == '\'') {
                val quote = c
                val s = i
                i++
                var esc = false
                while (i < n) {
                    val ch = text[i]
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == quote) {
                        i++; break
                    }
                    i++
                }
                tokens += Token(s until i.coerceAtMost(n), TokenType.String)
                continue
            }

            // annotation
            if (c == '@' && cfg.supportsAnnotations) {
                val s = i
                i++
                while (i < n && isId(text[i])) i++
                tokens += Token(s until i, TokenType.Annotation)
                continue
            }

            // operators
            val ops = listOf(
                "==", "===", "!=", "!==", "<=", ">=", "->", "=>", "&&", "||", "::",
                "+", "-", "*", "/", "%", "!", "?", "=", "<", ">", ".", ":", ";", ","
            )
            val op = ops.firstOrNull { text.startsWith(it, i) }
            if (op != null) {
                tokens += Token(i until i + op.length, TokenType.Operator)
                i += op.length
                continue
            }

            // punctuation
            if (c in listOf('(', ')', '{', '}', '[', ']')) {
                tokens += Token(i until i + 1, TokenType.Punctuation)
                i++
                continue
            }

            // numbers
            if (c.isDigit()) {
                val s = i
                i++
                var dot = false
                while (i < n) {
                    val ch = text[i]
                    if (ch == '.' && !dot) {
                        dot = true; i++
                    } else if (ch.isDigit()) i++
                    else break
                }
                tokens += Token(s until i, TokenType.Number)
                continue
            }

            // identifiers
            if (c.isLetter() || c == '_' || c == '$') {
                val s = i
                i++
                while (i < n && isId(text[i])) i++
                val word = text.substring(s, i)

                val key = if (cfg.caseInsensitiveKeywords) word.uppercase() else word
                val isKeyword = key in cfg.keywords
                val isType = (!cfg.caseInsensitiveKeywords && (word in cfg.types || word.first().isUpperCase())) ||
                        (cfg.caseInsensitiveKeywords && word.uppercase() in cfg.types.map { it.uppercase() }.toSet())
                val isConst = if (cfg.caseInsensitiveKeywords)
                    word.uppercase() in cfg.constants.map { it.uppercase() }.toSet()
                else
                    word in cfg.constants

                val baseType = when {
                    isKeyword -> TokenType.Keyword
                    isType -> TokenType.Type
                    isConst -> TokenType.Number
                    else -> TokenType.Variable
                }

                val next = text.indexOfFirstNonWs(i)
                val isFunc = next != -1 && next < n && text[next] == '('

                val prev = text.indexOfLastNonWs(s - 1)
                val isField = prev != -1 && text[prev] == '.'

                val final = when {
                    baseType == TokenType.Keyword || baseType == TokenType.Type -> baseType
                    isFunc -> TokenType.Function
                    isField -> TokenType.Field
                    else -> TokenType.Variable
                }

                tokens += Token(s until i, final)
                continue
            }

            tokens += Token(i until i + 1, TokenType.Other)
            i++
        }

        // generics
        val gen = Regex("<\\s*([A-Z][A-Za-z0-9_]*)\\s*>")
        gen.findAll(text).forEach {
            val r = it.groups[1]?.range ?: return@forEach
            tokens += Token(r, TokenType.GenericParameter)
        }

        return tokens.sortedBy { it.range.first }
    }

    private fun styleFor(t: TokenType, theme: CodeTheme): SpanStyle? = when (t) {
        TokenType.Keyword -> SpanStyle(color = theme.Keyword, fontWeight = FontWeight.Bold)
        TokenType.Type -> SpanStyle(color = theme.Type, fontWeight = FontWeight.SemiBold)
        TokenType.Function -> SpanStyle(color = theme.Function)
        TokenType.Variable -> SpanStyle(color = theme.Default)
        TokenType.Field -> SpanStyle(color = theme.Field)
        TokenType.String -> SpanStyle(color = theme.String)
        TokenType.Number -> SpanStyle(color = theme.Number)
        TokenType.Comment -> SpanStyle(color = theme.Comment, fontStyle = FontStyle.Italic)
        TokenType.Annotation -> SpanStyle(color = theme.Annotation)
        TokenType.Operator -> SpanStyle(color = theme.Operator)
        TokenType.Punctuation -> SpanStyle(color = theme.Punctuation)
        TokenType.GenericParameter -> SpanStyle(color = theme.Generic, fontStyle = FontStyle.Italic)
        TokenType.Whitespace, TokenType.Other -> null
    }

    private fun String.indexOfFirstNonWs(from: Int): Int {
        var i = from
        while (i < length && this[i].isWhitespace()) i++
        return if (i >= length) -1 else i
    }

    private fun String.indexOfLastNonWs(before: Int): Int {
        var i = before
        while (i >= 0 && this[i].isWhitespace()) i--
        return i
    }
}