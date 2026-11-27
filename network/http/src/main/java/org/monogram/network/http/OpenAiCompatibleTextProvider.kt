package org.monogram.network.http

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.monogram.core.common.TextToolsProviderConfig
import org.monogram.core.models.AiTextResult
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import java.net.URI

private fun createProviderClient(): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        install(HttpRequestRetry) {
            maxRetries = 1
            retryIf { _, response -> response.status.value == 429 || response.status.value in 500..599 }
            delayMillis { attempt -> 500L * attempt }
        }
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
            }
        }
    }

class OpenAiCompatibleTextProvider(
    private val httpClient: HttpClient = createProviderClient(),
) {
    suspend fun translate(
        text: TextToolText,
        language: String,
        config: TextToolsProviderConfig,
    ): TextToolText =
        complete(
            config,
            operation = "translate",
            system = "Translate the supplied text to the requested language. Return only JSON.",
            instruction = "Translate to $language. Preserve meaning and formatting.",
            text = text,
        ).result

    suspend fun summarize(
        text: TextToolText,
        language: String?,
        config: TextToolsProviderConfig,
        tone: String? = null,
    ): TextToolText =
        complete(
            config,
            operation = "summarize",
            system = "Summarize the supplied text briefly. Return only JSON.",
            instruction =
                buildString {
                    append("Summarize in ${language ?: "the source language"}. Keep the key facts and do not invent details.")
                    tone?.takeIf { it.isNotBlank() }?.let { append(" Use tone $it.") }
                },
            text = text.copy(entities = emptyList()),
        ).result

    suspend fun compose(
        text: TextToolText,
        proofread: Boolean,
        emojify: Boolean,
        language: String?,
        tone: String?,
        config: TextToolsProviderConfig,
    ): AiTextResult =
        complete(
            config,
            operation = "compose",
            system = "Transform the supplied text and return only JSON.",
            instruction =
                buildString {
                    append("Apply: ")
                    if (proofread) append("proofread; ")
                    if (emojify) append("emojify; ")
                    language?.let { append("translate to $it; ") }
                    tone?.let { append("use tone $it; ") }
                    append("Preserve the author's meaning.")
                },
            text = text,
        )

    private suspend fun complete(
        config: TextToolsProviderConfig,
        operation: String,
        system: String,
        instruction: String,
        text: TextToolText,
    ): AiTextResult {
        validate(config)
        val request =
            ChatRequest(
                model = config.model.trim(),
                messages =
                    listOf(
                        ChatMessage(
                            "system",
                            "$system Preserve every private-use formatting marker in the input text exactly once and keep each opening marker before its matching closing marker. Do not invent markers. Return {\"text\":string,\"entities\":array,\"diff\":object|null}. For marked input return entities:[] and diff:null. Otherwise use UTF-16 entity offsets in the returned text.",
                        ),
                        ChatMessage(
                            "user",
                            "Operation: $operation\n$instruction\nInput JSON: ${
                                text.withEntityMarkers().toJson()
                            }"
                        ),
                    ),
                temperature = 0.0,
            )
        val response =
            try {
                withTimeoutOrNull(45_000) {
                    httpClient
                        .post(config.baseUrl.trimEnd('/') + "/chat/completions") {
                            contentType(ContentType.Application.Json)
                            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                            setBody(request)
                        }.let { httpResponse ->
                            if (!httpResponse.status.isSuccess()) {
                                throw TextProviderException(
                                    "Custom text provider request failed (${httpResponse.status.value})",
                                    retryable = httpResponse.status.value == 429 || httpResponse.status.value in 500..599,
                                )
                            }
                            httpResponse.body<ChatResponse>()
                        }
                } ?: throw TextProviderException(
                    "Custom text provider request timed out",
                    retryable = true
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (providerError: TextProviderException) {
                throw providerError
            } catch (error: Exception) {
                throw TextProviderException(
                    "Custom text provider request failed",
                    retryable = true,
                    cause = error
                )
            }
        val content =
            response.choices
                .firstOrNull()
                ?.message
                ?.content
                ?: throw TextProviderException(
                    "Custom text provider returned no result",
                    retryable = false
                )
        return parseResult(content, text.entities)
    }

    private fun validate(config: TextToolsProviderConfig) {
        if (!config.isCustom || config.apiKey.isBlank() || config.model.isBlank()) {
            throw TextProviderException(
                "Custom text provider configuration is incomplete",
                retryable = false
            )
        }
        val endpoint = config.baseUrl.trim()
        if (endpoint.length > 200 || endpoint.any { it.isWhitespace() || it.isISOControl() }) {
            throw TextProviderException(
                "Custom text provider endpoint is invalid",
                retryable = false
            )
        }
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw TextProviderException(
                "Custom text provider endpoint must be HTTPS",
                retryable = false
            )
        }
    }

    private fun parseResult(
        raw: String,
        sourceEntities: List<TextEntity>,
    ): AiTextResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw TextProviderException(
            "Custom text provider returned invalid JSON",
            retryable = false
        )
        return try {
            val body = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            val resultJson =
                body["textResult"] as? JsonObject ?: body["result"] as? JsonObject ?: body
            val output = resultJson.toText()
            val result = if (sourceEntities.isEmpty()) output else decodeEntityMarkers(
                output.text,
                sourceEntities
            )
            val diff = (body["diff"] as? JsonObject)?.toText()
            require(result.text.isNotBlank())
            AiTextResult(result, diff)
        } catch (error: TextProviderException) {
            throw error
        } catch (error: Exception) {
            throw TextProviderException(
                "Custom text provider returned invalid text",
                retryable = false,
                cause = error
            )
        }
    }

    private fun JsonObject.toText(): TextToolText {
        val value = this["text"] as? JsonPrimitive
        require(value?.isString == true)
        val text = value.content
        require(text.isNotBlank())
        val entities = TextEntities.parse(this["entities"]?.toString())
        require(entities.all { it.offset >= 0 && it.length > 0 && it.offset <= text.length && it.length <= text.length - it.offset })
        return TextToolText(text, entities)
    }

    private fun TextToolText.withEntityMarkers(): TextToolText {
        if (entities.isEmpty()) return this
        val opens = entities.withIndex().groupBy { it.value.offset }
        val closes = entities.withIndex().groupBy { it.value.offset + it.value.length }
        val marked = StringBuilder(text.length + entities.size * 12)
        for (position in 0..text.length) {
            closes[position].orEmpty().sortedByDescending { it.index }.forEach {
                marked
                    .append('\uE000')
                    .append('/')
                    .append(it.index)
                    .append('\uE001')
            }
            opens[position].orEmpty().sortedByDescending { it.value.length }.forEach {
                marked.append('\uE000').append(it.index).append('\uE001')
            }
            if (position < text.length) marked.append(text[position])
        }
        return copy(text = marked.toString(), entities = emptyList())
    }

    private fun decodeEntityMarkers(
        marked: String,
        sourceEntities: List<TextEntity>,
    ): TextToolText {
        val marker = Regex("\\uE000(/?)(\\d+)\\uE001")
        val output = StringBuilder(marked.length)
        val offsets = HashMap<Int, Int>()
        val ends = HashMap<Int, Int>()
        val closed = HashSet<Int>()
        var cursor = 0
        marker.findAll(marked).forEach { match ->
            output.append(marked, cursor, match.range.first)
            val index =
                match.groupValues[2].toIntOrNull()
                    ?: throw TextProviderException(
                        "Custom provider changed formatting markers",
                        false
                    )
            if (index !in sourceEntities.indices) throw TextProviderException(
                "Custom provider returned unknown formatting markers",
                false
            )
            if (match.groupValues[1].isEmpty()) {
                if (index in offsets) throw TextProviderException(
                    "Custom provider duplicated formatting markers",
                    false
                )
                offsets[index] = output.length
            } else {
                if (index !in offsets ||
                    !closed.add(index)
                ) {
                    throw TextProviderException(
                        "Custom provider returned invalid formatting markers",
                        false
                    )
                }
                ends[index] = output.length
            }
            cursor = match.range.last + 1
        }
        output.append(marked, cursor, marked.length)
        if (closed.size != sourceEntities.size) throw TextProviderException(
            "Custom provider dropped formatting markers",
            false
        )
        val entities =
            sourceEntities.mapIndexed { index, entity ->
                val start = offsets.getValue(index)
                val end = ends.getValue(index)
                require(end > start)
                entity.copy(offset = start, length = end - start)
            }
        return TextToolText(output.toString(), entities)
    }

    private fun TextToolText.toJson(): String =
        buildJsonObject {
            put("text", text)
            put("entities", Json.parseToJsonElement(TextEntities.serialize(entities) ?: "[]"))
        }.toString()
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
private data class ChatChoice(
    val message: ChatMessage = ChatMessage("assistant", ""),
)

class TextProviderException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)
