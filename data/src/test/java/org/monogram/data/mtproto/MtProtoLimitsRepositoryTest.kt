package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.TelegramLimits
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonNumber
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObject
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObjectValue_c7a772e90b
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonString
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonValue
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AppConfigNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AppConfig_3a7ef1187f
import org.monogram.mtproto.tl.generated.cloud.layer223.help.GetAppConfig
import org.monogram.mtproto.tl.runtime.TlMethod

class MtProtoLimitsRepositoryTest {
    private fun entry(key: String, value: Int) =
        JsonObjectValue_c7a772e90b(key, JsonNumber(value.toDouble()))

    private fun config(vararg entries: Pair<String, Int>) = JsonObject(
        entries.map { entry(it.first, it.second) },
    )

    @Test
    fun `maps app config keys onto limits and caches the hash`() = runBlocking {
        var closed = false
        val requests = mutableListOf<GetAppConfig>()
        val transport = object : MtProtoRpcTransport() {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R {
                requests += method as GetAppConfig
                return AppConfig_3a7ef1187f(
                    hash = 77,
                    config = config(
                        "forwarded_message_count_max" to 50,
                        "quote_length_max" to 200,
                        "poll_answers_max" to 12,
                        "rich_message_length_limit" to 4000,
                        "todo_items_max" to 30,
                        "unrelated_key" to 1,
                    ),
                ) as R
            }
            override fun close() { closed = true }
        }
        val repository = MtProtoLimitsRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
        )

        repository.refresh()

        val limits = repository.limits.value
        assertEquals(50, limits.forwardedMessageCountMax)
        assertEquals(200, limits.messageReplyQuoteLengthMax)
        assertEquals(12, limits.pollAnswerCountMax)
        assertEquals(4000, limits.richMessageTextLengthMax)
        assertEquals(30, limits.checklistTaskCountMax)
        // Unmapped keys keep their protocol-neutral defaults.
        assertEquals(TelegramLimits.DEFAULTS.bioLengthMax, limits.bioLengthMax)
        assertEquals(listOf(0), requests.map { it.hash })
        assertTrue(closed)
    }

    @Test
    fun `sends cached hash and keeps values on not modified`() = runBlocking {
        var call = 0
        val transport = object : MtProtoRpcTransport() {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R {
                call++
                val result: Any = if (call == 1) {
                    AppConfig_3a7ef1187f(hash = 77, config = config("poll_answers_max" to 12))
                } else {
                    AppConfigNotModified
                }
                @Suppress("UNCHECKED_CAST")
                return result as R
            }
            override fun close() = Unit
        }
        val repository = MtProtoLimitsRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
        )
        repository.refresh()

        repository.refresh()

        assertEquals(12, repository.limits.value.pollAnswerCountMax)
    }

    @Test
    fun `non numeric entries keep defaults`() {
        val repository = MtProtoLimitsRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { error("no transport") },
        )
        val mixed = JsonObject(
            listOf(
                JsonObjectValue_c7a772e90b("poll_answers_max", JsonString("twelve")),
                JsonObjectValue_c7a772e90b("quote_length_max", JsonNumber(64.0)),
            ),
        )

        repository.apply(mixed)

        assertEquals(null, repository.limits.value.pollAnswerCountMax)
        assertEquals(64, repository.limits.value.messageReplyQuoteLengthMax)
    }

    private abstract class MtProtoRpcTransport : org.monogram.mtproto.transport.MtProtoRpcTransport {
        override val updates get() = null
        override val newSessions get() = null
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = org.monogram.mtproto.handshake.MtProtoHandshakeConfig(2, listOf("k")),
        cloud = org.monogram.mtproto.transport.CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )
}
