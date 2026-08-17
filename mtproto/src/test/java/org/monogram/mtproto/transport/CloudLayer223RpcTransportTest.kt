package org.monogram.mtproto.transport

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.codec.TlBinaryWriter
import org.monogram.mtproto.tl.generated.cloud.layer223.InitConnection
import org.monogram.mtproto.tl.generated.cloud.layer223.InvokeWithLayer
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonNumber
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObject
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObjectValue_c7a772e90b
import org.monogram.mtproto.tl.generated.cloud.layer223.help.GetConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.registry.CloudLayer223ConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlReader
import org.monogram.mtproto.tl.runtime.TlWriter

class CloudLayer223RpcTransportTest {
    @Test
    fun wrapsFirstMethodWithExactLayerAndConnectionMetadataThenSendsBare() = runBlocking {
        val delegate = ScriptedTransport(Outcome.Success(null), Outcome.Success("second"))
        val transport = CloudLayer223RpcTransport(delegate, config(timeZoneOffsetSeconds = 10_800))
        val second = TestMethod("second")

        transport.execute(GetConfig)
        assertEquals("second", transport.execute(second))

        val layer = delegate.calls[0] as InvokeWithLayer<*>
        assertEquals(223, layer.layer)
        val connection = layer.query as InitConnection<*>
        assertEquals(12345, connection.apiId)
        assertEquals("Monogram Device", connection.deviceModel)
        assertEquals("Android 16", connection.systemVersion)
        assertEquals("2.0", connection.appVersion)
        assertEquals("en", connection.systemLangCode)
        assertEquals("android", connection.langPack)
        assertEquals("en", connection.langCode)
        assertNull(connection.proxy)
        val params = connection.params as JsonObject
        val timeZone = params.value_.single() as JsonObjectValue_c7a772e90b
        assertEquals("tz_offset", timeZone.key)
        assertEquals(10_800.0, (timeZone.value_ as JsonNumber).value_, 0.0)
        assertSame(GetConfig, connection.query)
        assertSame(second, delegate.calls[1])

        val writer = TlBinaryWriter()
        CloudLayer223ConstructorRegistry.encodeMethod(writer, layer)
        assertTrue(writer.toByteArray().isNotEmpty())
    }

    @Test
    fun defaultsLanguageCodeForNonEmptyLanguagePack() = runBlocking {
        val delegate = ScriptedTransport(Outcome.Success("ready"))
        val transport = CloudLayer223RpcTransport(
            delegate,
            config(languagePack = "android", languageCode = ""),
        )

        transport.execute(TestMethod("first"))

        val connection = (delegate.calls.single() as InvokeWithLayer<*>).query as InitConnection<*>
        assertEquals("android", connection.langPack)
        assertEquals("en", connection.langCode)
    }

    @Test
    fun suppressesLanguagePackForCustomLanguageCode() = runBlocking {
        val delegate = ScriptedTransport(Outcome.Success("ready"))
        val transport = CloudLayer223RpcTransport(
            delegate,
            config(languagePack = "android", languageCode = "Xcustom"),
        )

        transport.execute(TestMethod("first"))

        val connection = (delegate.calls.single() as InvokeWithLayer<*>).query as InitConnection<*>
        assertEquals("", connection.langPack)
        assertEquals("", connection.langCode)
    }

    @Test
    fun closesDelegateExactlyOnceAndRejectsFurtherCalls() {
        val delegate = ScriptedTransport()
        val transport = CloudLayer223RpcTransport(delegate, config())

        transport.close()
        transport.close()

        assertEquals(1, delegate.closeCalls)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { transport.execute(TestMethod("closed")) }
        }
    }

    @Test
    fun doesNotRetryWhenClosedAfterConnectionError() = runBlocking {
        lateinit var transport: CloudLayer223RpcTransport
        val delegate = ScriptedTransport(
            Outcome.Success("ready"),
            Outcome.Action {
                transport.close()
                throw MtProtoRpcException(400, "CONNECTION_NOT_INITED")
            },
        )
        transport = CloudLayer223RpcTransport(delegate, config())

        assertEquals("ready", transport.execute(TestMethod("first")))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { transport.execute(TestMethod("second")) }
        }

        assertEquals(2, delegate.calls.size)
        assertEquals(1, delegate.closeCalls)
    }

    @Test
    fun keepsHeaderRequiredAfterInitialRpcError() {
        val delegate = ScriptedTransport(
            Outcome.Failure(MtProtoRpcException(400, "PHONE_NUMBER_INVALID")),
            Outcome.Success("ok"),
        )
        val transport = CloudLayer223RpcTransport(delegate, config())

        assertThrows(MtProtoRpcException::class.java) {
            runBlocking { transport.execute(TestMethod("first")) }
        }
        assertEquals("ok", runBlocking { transport.execute(TestMethod("second")) })
        assertTrue(delegate.calls.all { it is InvokeWithLayer<*> })
    }

    @Test
    fun retriesBareRequestOnceWithHeaderAfterConnectionErrors() = runBlocking {
        listOf("CONNECTION_NOT_INITED", "CONNECTION_LAYER_INVALID").forEach { message ->
            val first = TestMethod("first")
            val second = TestMethod("second")
            val delegate = ScriptedTransport(
                Outcome.Success("ready"),
                Outcome.Failure(MtProtoRpcException(400, message)),
                Outcome.Success("retried"),
            )
            val transport = CloudLayer223RpcTransport(delegate, config())

            assertEquals("ready", transport.execute(first))
            assertEquals("retried", transport.execute(second))

            assertWrapped(delegate.calls[0], first)
            assertSame(second, delegate.calls[1])
            assertWrapped(delegate.calls[2], second)
        }
    }

    @Test
    fun doesNotRetryOtherErrorsAndKeepsEstablishedState() = runBlocking {
        val delegate = ScriptedTransport(
            Outcome.Success("ready"),
            Outcome.Failure(MtProtoRpcException(420, "FLOOD_WAIT_3")),
            Outcome.Success("next"),
        )
        val transport = CloudLayer223RpcTransport(delegate, config())
        val first = TestMethod("first")
        val second = TestMethod("second")
        val third = TestMethod("third")

        assertEquals("ready", transport.execute(first))
        assertThrows(MtProtoRpcException::class.java) { runBlocking { transport.execute(second) } }
        assertEquals("next", transport.execute(third))
        assertWrapped(delegate.calls[0], first)
        assertSame(second, delegate.calls[1])
        assertSame(third, delegate.calls[2])
    }

    @Test
    fun cancellationDoesNotChangeHeaderState() {
        val firstDelegate = ScriptedTransport(
            Outcome.Failure(CancellationException("cancelled")),
            Outcome.Success("ready"),
        )
        val firstTransport = CloudLayer223RpcTransport(firstDelegate, config())
        assertThrows(CancellationException::class.java) {
            runBlocking { firstTransport.execute(TestMethod("cancel")) }
        }
        assertEquals("ready", runBlocking { firstTransport.execute(TestMethod("retry")) })
        assertTrue(firstDelegate.calls.all { it is InvokeWithLayer<*> })

        val establishedDelegate = ScriptedTransport(
            Outcome.Success("ready"),
            Outcome.Failure(CancellationException("cancelled")),
            Outcome.Success("next"),
        )
        val established = CloudLayer223RpcTransport(establishedDelegate, config())
        runBlocking { established.execute(TestMethod("first")) }
        assertThrows(CancellationException::class.java) {
            runBlocking { established.execute(TestMethod("cancel")) }
        }
        assertEquals("next", runBlocking { established.execute(TestMethod("next")) })
        assertTrue(establishedDelegate.calls.drop(1).all { it is TestMethod })
    }

    private fun config(
        timeZoneOffsetSeconds: Int? = null,
        languagePack: String = "android",
        languageCode: String = "en",
    ) = CloudLayer223ConnectionConfig(
        apiId = 12345,
        deviceModel = "Monogram Device",
        systemVersion = "Android 16",
        applicationVersion = "2.0",
        systemLanguageCode = "en",
        languagePack = languagePack,
        languageCode = languageCode,
        timeZoneOffsetSeconds = timeZoneOffsetSeconds,
    )

    private fun assertWrapped(actual: TlMethod<*>, expected: TlMethod<*>) {
        val layer = actual as InvokeWithLayer<*>
        assertEquals(223, layer.layer)
        assertSame(expected, (layer.query as InitConnection<*>).query)
    }

    private data class TestMethod(val name: String) : TlMethod<String> {
        override val constructorId: UInt = name.hashCode().toUInt()
        override val resultCodec: TlCodec<String> = StringCodec
    }

    private object StringCodec : TlCodec<String> {
        override fun read(reader: TlReader, context: TlDecodeContext): String = error("Not used")
        override fun write(writer: TlWriter, value: String) = error("Not used")
    }

    private sealed interface Outcome {
        data class Success(val value: Any?) : Outcome
        data class Failure(val throwable: Throwable) : Outcome
        data class Action(val block: () -> Any?) : Outcome
    }

    private class ScriptedTransport(vararg outcomes: Outcome) : MtProtoRpcTransport {
        val calls = mutableListOf<TlMethod<*>>()
        var closeCalls = 0
            private set
        private val outcomes = ArrayDeque(outcomes.toList())

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            calls += method
            return when (val outcome = outcomes.removeFirst()) {
                is Outcome.Success -> outcome.value as R
                is Outcome.Failure -> throw outcome.throwable
                is Outcome.Action -> outcome.block() as R
            }
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
