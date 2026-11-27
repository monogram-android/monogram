package org.monogram.network.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.TextToolsProviderConfig
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText

class OpenAiCompatibleTextProviderTest {
    @Test
    fun rejectsNonHttpsAndMalformedEndpointsBeforeNetwork() =
        runBlocking {
            val provider = OpenAiCompatibleTextProvider()
            val config =
                TextToolsProviderConfig(
                    provider = "openai-compatible",
                    baseUrl = "http://api.example.test/v1",
                    model = "test-model",
                    apiKey = "secret",
                )

            val error =
                runCatching {
                    provider.translate(TextToolText("hello"), "de", config)
                }.exceptionOrNull()

            assertTrue(error is TextProviderException)
            assertTrue(error?.message?.contains("HTTPS") == true)
        }

    @Test
    fun providerConfigRedactsApiKey() {
        val config =
            TextToolsProviderConfig(
                provider = "openai-compatible",
                apiKey = "secret-api-key",
            )

        assertTrue(config.toString().contains("[REDACTED]"))
        assertTrue(!config.toString().contains("secret-api-key"))
    }

    @Test
    fun parsesRichTextAndPreservesEntityMetadata() =
        runBlocking {
            val source =
                TextToolText("hello", listOf(TextEntity("bold", 0, 5, "https://example.test")))
            val marked = "\uE0000\uE001bonjour\uE000/0\uE001"
            val client = mockClient { _, _ -> response("{\"text\":\"$marked\",\"entities\":[]}") }
            val result =
                OpenAiCompatibleTextProvider(client).translate(
                    source,
                    "fr",
                    config(),
                )

            assertEquals("bonjour", result.text)
            assertEquals(listOf(TextEntity("bold", 0, 7, "https://example.test")), result.entities)
        }

    @Test
    fun rejectsDroppedFormattingMarkers() =
        runBlocking {
            val source = TextToolText("hello", listOf(TextEntity("bold", 0, 5)))
            val client = mockClient { _, _ -> response("{\"text\":\"bonjour\",\"entities\":[]}") }
            val error =
                runCatching {
                    OpenAiCompatibleTextProvider(client).translate(source, "fr", config())
                }.exceptionOrNull()

            assertTrue(error is TextProviderException)
            assertTrue(error?.message?.contains("formatting markers") == true)
        }

    @Test
    fun includesSummaryToneInDedicatedPrompt() =
        runBlocking {
            var requestBody = ""
            val client =
                mockClient { _, body ->
                    requestBody = body
                    response("{\"text\":\"brief\",\"entities\":[]}")
                }

            val result = OpenAiCompatibleTextProvider(client).summarize(
                TextToolText("hello"),
                "de",
                config(),
                "friendly"
            )

            assertEquals("brief", result.text)
            assertTrue(requestBody.contains("Use tone friendly"))
        }

    @Test
    fun mapsQuotaFailureAsRetryableAndRetriesOnce() =
        runBlocking {
            var calls = 0
            val client =
                mockClient { _, _ ->
                    calls++
                    respond(
                        "quota",
                        HttpStatusCode.TooManyRequests,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                    )
                }

            val error =
                runCatching {
                    OpenAiCompatibleTextProvider(
                        client,
                    ).translate(TextToolText("hello"), "fr", config())
                }.exceptionOrNull()

            assertTrue(error is TextProviderException)
            assertTrue((error as TextProviderException).retryable)
            assertEquals(2, calls)
        }

    @Test
    fun retriesServerFailureOnce() =
        runBlocking {
            var calls = 0
            val client =
                mockClient { _, _ ->
                    calls++
                    if (calls == 1) {
                        respond(
                            "busy",
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )
                    } else {
                        response("{\"text\":\"bonjour\",\"entities\":[]}")
                    }
                }
            val result = OpenAiCompatibleTextProvider(client).translate(
                TextToolText("hello"),
                "fr",
                config()
            )

            assertEquals("bonjour", result.text)
            assertEquals(2, calls)
        }

    private fun config() =
        TextToolsProviderConfig(
            provider = "openai-compatible",
            baseUrl = "https://api.example.test/v1",
            model = "test-model",
            apiKey = "secret",
        )

    private fun mockClient(handler: suspend MockRequestHandleScope.(String, String) -> HttpResponseData): HttpClient {
        val engine =
            io.ktor.client.engine.mock.MockEngine { request ->
                handler(
                    request.url.toString(),
                    (request.body as? TextContent)?.text ?: request.body.toString(),
                )
            }
        return HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json() }
            install(HttpRequestRetry) {
                maxRetries = 1
                retryIf { _, response -> response.status.value == 429 || response.status.value in 500..599 }
                delayMillis { 1 }
            }
        }
    }

    private fun MockRequestHandleScope.response(content: String) =
        respond(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":${JsonPrimitive(content)}}}]}",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
