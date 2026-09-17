package org.monogram.core.models

enum class ReplyMarkupKind {
    Inline,
    Keyboard,
    Hide,
    ForceReply,
}

enum class ReplyButtonType {
    Text,
    Url,
    Callback,
    SwitchInline,
    Copy,
    WebApp,
    Login,
    Game,
    Pay,
    User,
    RequestContact,
    RequestLocation,
    RequestPoll,
    RequestPeer,
    Disabled,
    Other,
}

data class ReplyButton(
    val type: ReplyButtonType,
    val text: String,
    val url: String? = null,
    val dataHex: String? = null,
    val query: String? = null,
    val samePeer: Boolean = false,
    val copyText: String? = null,
    val requiresPassword: Boolean = false,
)

data class ReplyMarkup(
    val kind: ReplyMarkupKind,
    val rows: List<List<ReplyButton>> = emptyList(),
    val resize: Boolean = false,
    val singleUse: Boolean = false,
    val selective: Boolean = false,
    val persistent: Boolean = false,
    val forceReply: Boolean = false,
    val placeholder: String? = null,
)

data class BotCallbackAnswer(
    val alert: Boolean = false,
    val message: String? = null,
    val url: String? = null,
    val cacheTime: Int = 0,
)

object ReplyMarkups {
    fun latestBotKeyboard(messages: List<Message>): ReplyMarkup? {
        return latestBotKeyboardMessage(messages)?.replyMarkup
    }

    fun latestBotKeyboardMessage(messages: List<Message>): Message? {
        for (message in messages.asReversed()) {
            val markup = message.replyMarkup ?: continue
            when (markup.kind) {
                ReplyMarkupKind.Keyboard, ReplyMarkupKind.ForceReply -> return message
                ReplyMarkupKind.Hide -> return null
                ReplyMarkupKind.Inline -> Unit
            }
        }
        return null
    }

    fun parse(raw: String?): ReplyMarkup? {
        if (raw.isNullOrBlank()) return null
        val root = CompactJson.parse(raw) as? Map<*, *> ?: return null
        val kind = when (root.string("k")) {
            "inline" -> ReplyMarkupKind.Inline
            "keyboard" -> ReplyMarkupKind.Keyboard
            "hide" -> ReplyMarkupKind.Hide
            "force" -> ReplyMarkupKind.ForceReply
            else -> return null
        }
        return ReplyMarkup(
            kind = kind,
            rows = parseRows(root["rows"]),
            resize = root.bool("resize"),
            singleUse = root.bool("single"),
            selective = root.bool("selective"),
            persistent = root.bool("persistent"),
            forceReply = root.bool("force"),
            placeholder = root.string("placeholder"),
        )
    }

    fun serialize(markup: ReplyMarkup?): String? {
        if (markup == null) return null
        val kind = when (markup.kind) {
            ReplyMarkupKind.Inline -> "inline"
            ReplyMarkupKind.Keyboard -> "keyboard"
            ReplyMarkupKind.Hide -> "hide"
            ReplyMarkupKind.ForceReply -> "force"
        }
        val body = buildString {
            append("{\"k\":\"").append(kind).append('"')
            if (markup.resize) append(",\"resize\":true")
            if (markup.singleUse) append(",\"single\":true")
            if (markup.selective) append(",\"selective\":true")
            if (markup.persistent) append(",\"persistent\":true")
            if (markup.forceReply) append(",\"force\":true")
            markup.placeholder?.takeIf { it.isNotEmpty() }?.let {
                append(",\"placeholder\":\"").append(escape(it)).append('"')
            }
            if (markup.rows.isNotEmpty()) {
                append(",\"rows\":[")
                markup.rows.forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) append(',')
                    append('[')
                    row.forEachIndexed { buttonIndex, button ->
                        if (buttonIndex > 0) append(',')
                        append(serializeButton(button))
                    }
                    append(']')
                }
                append(']')
            }
            append('}')
        }
        return body
    }

    private fun serializeButton(button: ReplyButton): String = buildString {
        append("{\"t\":\"")
        append(
            when (button.type) {
                ReplyButtonType.Text -> "text"
                ReplyButtonType.Url -> "url"
                ReplyButtonType.Callback -> "cb"
                ReplyButtonType.SwitchInline -> "sw"
                ReplyButtonType.Copy -> "copy"
                ReplyButtonType.WebApp -> "web"
                ReplyButtonType.Login -> "login"
                ReplyButtonType.Game -> "game"
                ReplyButtonType.Pay -> "pay"
                ReplyButtonType.User -> "user"
                ReplyButtonType.RequestContact -> "contact"
                ReplyButtonType.RequestLocation -> "geo"
                ReplyButtonType.RequestPoll -> "poll"
                ReplyButtonType.RequestPeer -> "peer"
                ReplyButtonType.Disabled -> "off"
                ReplyButtonType.Other -> "other"
            },
        )
        append("\",\"x\":\"").append(escape(button.text)).append('"')
        button.url?.let { append(",\"u\":\"").append(escape(it)).append('"') }
        button.dataHex?.let { append(",\"d\":\"").append(escape(it)).append('"') }
        button.query?.let { append(",\"q\":\"").append(escape(it)).append('"') }
        if (button.samePeer) append(",\"same\":true")
        button.copyText?.let { append(",\"c\":\"").append(escape(it)).append('"') }
        if (button.requiresPassword) append(",\"pw\":true")
        append('}')
    }

    private fun parseRows(raw: Any?): List<List<ReplyButton>> {
        val rows = raw as? List<*> ?: return emptyList()
        return rows.map { row ->
            val buttons = row as? List<*> ?: return@map emptyList()
            buttons.mapNotNull { parseButton(it as? Map<*, *>) }
        }
    }

    private fun parseButton(obj: Map<*, *>?): ReplyButton? {
        val text = obj.string("x") ?: return null
        val type = when (obj.string("t")) {
            "text" -> ReplyButtonType.Text
            "url" -> ReplyButtonType.Url
            "cb" -> ReplyButtonType.Callback
            "sw" -> ReplyButtonType.SwitchInline
            "copy" -> ReplyButtonType.Copy
            "web" -> ReplyButtonType.WebApp
            "login" -> ReplyButtonType.Login
            "game" -> ReplyButtonType.Game
            "pay" -> ReplyButtonType.Pay
            "user" -> ReplyButtonType.User
            "contact" -> ReplyButtonType.RequestContact
            "geo" -> ReplyButtonType.RequestLocation
            "poll" -> ReplyButtonType.RequestPoll
            "peer" -> ReplyButtonType.RequestPeer
            "off" -> ReplyButtonType.Disabled
            else -> ReplyButtonType.Other
        }
        return ReplyButton(
            type = type,
            text = text,
            url = obj.string("u"),
            dataHex = obj.string("d"),
            query = obj.string("q"),
            samePeer = obj.bool("same"),
            copyText = obj.string("c"),
            requiresPassword = obj.bool("pw"),
        )
    }

    private fun Map<*, *>?.string(name: String): String? =
        this?.get(name) as? String

    private fun Map<*, *>?.bool(name: String): Boolean =
        this?.get(name) == true

    private fun escape(value: String): String = CompactJson.escape(value)
}

/** Small JSON reader for the compact reply-markup payload. Malformed input yields null. */
object CompactJson {
    fun parse(raw: String): Any? = runCatching { Reader(raw).parseValue() }.getOrNull()

    fun escape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private class Reader(private val src: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            val ch = peek()
            return when {
                ch == '{' -> parseObject()
                ch == '[' -> parseArray()
                ch == '"' -> parseString()
                ch == 't' -> parseLiteral("true", true)
                ch == 'f' -> parseLiteral("false", false)
                ch == 'n' -> parseLiteral("null", null)
                ch == '-' || ch in '0'..'9' -> parseNumber()
                else -> error("value:$ch")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> error("object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> error("array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (i < src.length) {
                val ch = src[i++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= src.length) error("escape")
                        when (val esc = src[i++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > src.length) error("unicode")
                                val hex = src.substring(i, i + 4)
                                out.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(esc)
                        }
                    }
                    else -> out.append(ch)
                }
            }
            error("string")
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek() == '-') i++
            while (peek() in '0'..'9') i++
            if (peek() == '.') {
                i++
                while (peek() in '0'..'9') i++
            }
            val raw = src.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun parseLiteral(token: String, value: Any?): Any? {
            if (!src.startsWith(token, i)) error(token)
            i += token.length
            return value
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun peek(): Char? = src.getOrNull(i)

        private fun expect(ch: Char) {
            skipWs()
            if (peek() != ch) error(ch.toString())
            i++
        }
    }
}
