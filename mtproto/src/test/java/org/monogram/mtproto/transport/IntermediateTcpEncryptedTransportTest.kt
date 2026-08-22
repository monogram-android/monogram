package org.monogram.mtproto.transport

import java.io.InputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.codec.TlBinaryCodec
import org.monogram.mtproto.codec.TlBinaryWriter
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.MtProtoKeyDerivation
import org.monogram.mtproto.handshake.MtProtoAuthKey
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortSentMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.registry.CloudLayer223ConstructorRegistry
import org.monogram.mtproto.tl.generated.transport.BadServerSalt
import org.monogram.mtproto.tl.generated.transport.Message_48a7e89a1b
import org.monogram.mtproto.tl.generated.transport.MsgContainer
import org.monogram.mtproto.tl.generated.transport.MsgsAck_3546e430bb
import org.monogram.mtproto.tl.generated.transport.MsgsStateInfo_0ad1af2039
import org.monogram.mtproto.tl.generated.transport.NewSessionCreated
import org.monogram.mtproto.tl.generated.transport.Ping
import org.monogram.mtproto.tl.generated.transport.Pong_fbc65fe5b1
import org.monogram.mtproto.tl.generated.transport.RpcResult_f5247d1af6
import org.monogram.mtproto.tl.generated.transport.RpcError_134c3d92c4
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlLimits

class IntermediateTcpEncryptedTransportTest {
    @Test
    fun parsesKnownDcMigrationErrorsIntoTypedFailures() {
        listOf(
            "PHONE_MIGRATE_2" to MtProtoDcMigrationKind.PHONE,
            "NETWORK_MIGRATE_3" to MtProtoDcMigrationKind.NETWORK,
            "USER_MIGRATE_4" to MtProtoDcMigrationKind.USER,
            "FILE_MIGRATE_5" to MtProtoDcMigrationKind.FILE,
        ).forEach { (message, kind) ->
            val error = MtProtoRpcException.from(303, message) as MtProtoDcMigrationException
            assertEquals(kind, error.kind)
            assertEquals(message.substringAfterLast('_').toInt(), error.targetDcId)
        }
        assertTrue(MtProtoRpcException.from(303, "PHONE_MIGRATE_0") !is MtProtoDcMigrationException)
        assertTrue(MtProtoRpcException.from(400, "PHONE_MIGRATE_2") !is MtProtoDcMigrationException)
        assertTrue(MtProtoRpcException.from(303, "MIGRATE_2") !is MtProtoDcMigrationException)
    }

    @Test
    fun retainsIndependentEnvelopeCopiesUntilAcknowledged() {
        val registry = SentMessageRegistry(capacity = 2)
        val original = byteArrayOf(1, 2, 3, 4)
        try {
            registry.track(7L, original)
            original.fill(0)

            val replay = registry.copiesFor(listOf(7L))
            try {
                assertEquals(1, replay.size)
                assertTrue(replay.single().contentEquals(byteArrayOf(1, 2, 3, 4)))
            } finally {
                replay.forEach { it.fill(0) }
            }

            registry.removeAll(listOf(7L))
            assertTrue(registry.copiesFor(listOf(7L)).isEmpty())
        } finally {
            original.fill(0)
            registry.clear()
        }
    }

    @Test
    fun rejectsUnboundedUnacknowledgedEnvelopeGrowth() {
        val registry = SentMessageRegistry(capacity = 1)
        try {
            registry.track(7L, byteArrayOf(1))
            assertThrows(IllegalStateException::class.java) {
                registry.track(9L, byteArrayOf(2))
            }
        } finally {
            registry.clear()
        }
    }

    @Test
    fun disconnectsWithoutAcknowledgingUnsupportedAuthenticatedObject() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-unsupported-object-loopback") {
                try {
                    server.accept().use { peer ->
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, true).close()
                        val unsupported = encodeObject(RpcError_134c3d92c4(400, "UNSUPPORTED_SERVICE"))
                        try {
                            writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(1), 1, unsupported)
                        } finally {
                            unsupported.fill(0)
                        }
                        assertEquals(-1, peer.getInputStream().read())
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
            )
            try {
                val failure = assertThrows(MtProtoUncertainDeliveryException::class.java) {
                    runBlocking { transport.execute(Ping(PING_ID)) }
                }
                assertTrue(failure.cause != null)
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    @Test
    fun classifiesMatchingMessageStateInfoAsUncertainDelivery() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-message-state-info-loopback") {
                try {
                    server.accept().use { peer ->
                        val request = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, true)
                        try {
                            val stateInfo = encodeObject(
                                MsgsStateInfo_0ad1af2039(request.metadata.messageId, org.monogram.mtproto.tl.runtime.TlBytes.copyOf(byteArrayOf(0x03))),
                            )
                            try {
                                writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(1), 1, stateInfo)
                            } finally {
                                stateInfo.fill(0)
                            }
                        } finally {
                            request.close()
                        }
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
            )
            try {
                val thrown = assertThrows(MtProtoUncertainDeliveryException::class.java) {
                    runBlocking { transport.execute(Ping(PING_ID)) }
                }
                assertTrue(thrown.cause != null)
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    @Test
    fun retainsUpdatesBeforeResultAndWhileIdleInWireOrder() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val rpcReturned = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-update-inbox-loopback") {
                try {
                    server.accept().use { peer ->
                        val request = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, true)
                        request.use {
                            val firstUpdate = encodeCloudObject(UpdatesTooLong)
                            try {
                                writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(1), 1, firstUpdate)
                            } finally {
                                firstUpdate.fill(0)
                            }
                            readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()

                            val pong = encodeObject(Pong_fbc65fe5b1(request.metadata.messageId, PING_ID))
                            val result = encodeObject(
                                RpcResult_f5247d1af6(
                                    request.metadata.messageId,
                                    TlDeferredObject.copyOf(pong, TlLimits.DEFAULT.maxObjectBytes),
                                ),
                            )
                            try {
                                writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(3), 1, result)
                            } finally {
                                pong.fill(0)
                                result.fill(0)
                            }
                            readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()
                        }

                        assertTrue(rpcReturned.await(5, TimeUnit.SECONDS))
                        val idleUpdate = encodeCloudObject(
                            UpdateShortSentMessage(true, 17, 23, 1, NOW_SECONDS.toInt(), null, null, null),
                        )
                        try {
                            writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(5), 1, idleUpdate)
                        } finally {
                            idleUpdate.fill(0)
                        }
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    rpcReturned.countDown()
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
            )
            try {
                runBlocking {
                    val call = async(Dispatchers.Default) { transport.execute(Ping(PING_ID)) }
                    assertEquals(UpdatesTooLong, withTimeout(5_000) { transport.updates.receive() })
                    val pong = call.await() as Pong_fbc65fe5b1
                    assertEquals(PING_ID, pong.pingId)
                    rpcReturned.countDown()
                    val idle = withTimeout(5_000) { transport.updates.receive() } as UpdateShortSentMessage
                    assertEquals(17, idle.id)
                    assertEquals(MtProtoApiUpdateInboxMetrics(2, 2, 0), transport.updates.metrics())
                }
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    @Test
    fun cancellationAbandonsWaiterAndNextCallReusesConnection() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val firstRequestRead = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-cancellation-loopback") {
                try {
                    server.accept().use { peer ->
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, true).close()
                        firstRequestRead.countDown()
                        val request = readClientMessage(
                            peer.getInputStream(),
                            serverAuth,
                            session.sessionId,
                            false,
                        )
                        request.use {
                            val pong = encodeObject(Pong_fbc65fe5b1(request.metadata.messageId, PING_ID))
                            val result = encodeObject(
                                RpcResult_f5247d1af6(
                                    request.metadata.messageId,
                                    TlDeferredObject.copyOf(pong, TlLimits.DEFAULT.maxObjectBytes),
                                ),
                            )
                            try {
                                writeServerMessage(
                                    peer,
                                    serverAuth,
                                    session.sessionId,
                                    serverMessageId(1),
                                    1,
                                    result,
                                )
                            } finally {
                                pong.fill(0)
                                result.fill(0)
                            }
                        }
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    firstRequestRead.countDown()
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
                readTimeoutMillis = 10_000,
            )
            try {
                runBlocking {
                    val blocked = async(Dispatchers.Default) { transport.execute(Ping(PING_ID)) }
                    assertTrue(firstRequestRead.await(5, TimeUnit.SECONDS))
                    withTimeout(2_000) { blocked.cancelAndJoin() }
                    val pong = transport.execute(Ping(PING_ID)) as Pong_fbc65fe5b1
                    assertEquals(PING_ID, pong.pingId)
                }
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    @Test
    fun exposesTypedRpcErrorsAndStillAcknowledgesTheResponse() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-rpc-error-loopback") {
                try {
                    server.accept().use { peer ->
                        val request = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, true)
                        request.use {
                            val error = encodeObject(RpcError_134c3d92c4(400, "PING_REJECTED"))
                            val result = encodeObject(
                                RpcResult_f5247d1af6(
                                    request.metadata.messageId,
                                    TlDeferredObject.copyOf(error, TlLimits.DEFAULT.maxObjectBytes),
                                ),
                            )
                            try {
                                writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(1), 1, result)
                            } finally {
                                error.fill(0)
                                result.fill(0)
                            }
                        }
                        val acknowledgement = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false)
                        acknowledgement.use {
                            val body = acknowledgement.copyBody()
                            try {
                                val ack = TlBinaryCodec.decodeObject(TransportConstructorRegistry, body, TRANSPORT_CONTEXT)
                                    as MsgsAck_3546e430bb
                                assertEquals(listOf(serverMessageId(1)), ack.msgIds)
                            } finally {
                                body.fill(0)
                            }
                        }
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
            )
            try {
                val error = assertThrows(MtProtoRpcException::class.java) {
                    runBlocking { transport.execute(Ping(PING_ID)) }
                }
                assertEquals(400, error.errorCode)
                assertEquals("PING_REJECTED", error.rpcMessage)
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    @Test
    fun decodesMixedContainerUpdatesSaltCorrelatesResultAndAcknowledgesContent() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val observedSalts = mutableListOf<Long>()
            val firstRequestMessageId = AtomicReference<Long>()
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-encrypted-loopback") {
                try {
                    server.accept().use { peer ->
                        val request = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, expectPreamble = true)
                        request.use {
                            firstRequestMessageId.set(request.metadata.messageId)
                            assertEquals(Ping.CONSTRUCTOR_ID, constructorId(request.copyBodyAndWipe()))
                            val pong = encodeObject(Pong_fbc65fe5b1(request.metadata.messageId, PING_ID))
                            val rpc = encodeObject(
                                RpcResult_f5247d1af6(
                                    request.metadata.messageId,
                                    TlDeferredObject.copyOf(pong, TlLimits.DEFAULT.maxObjectBytes),
                                ),
                            )
                            val created = encodeObject(NewSessionCreated(request.metadata.messageId, 77L, UPDATED_SALT))
                            val update = encodeCloudObject(UpdatesTooLong)
                            val container = encodeObject(
                                MsgContainer(
                                    listOf(
                                        nestedMessage(serverMessageId(3), 1, created),
                                        nestedMessage(serverMessageId(5), 1, update),
                                        nestedMessage(serverMessageId(7), 1, rpc),
                                    ),
                                ),
                            )
                            try {
                                writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(1), 1, container)
                            } finally {
                                pong.fill(0)
                                rpc.fill(0)
                                created.fill(0)
                                update.fill(0)
                                container.fill(0)
                            }
                        }
                        val acknowledgement = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false)
                        acknowledgement.use {
                            val body = acknowledgement.copyBody()
                            try {
                                val ack = TlBinaryCodec.decodeObject(TransportConstructorRegistry, body, TRANSPORT_CONTEXT)
                                    as MsgsAck_3546e430bb
                                assertEquals(
                                    listOf(
                                        serverMessageId(1),
                                        serverMessageId(3),
                                        serverMessageId(5),
                                        serverMessageId(7),
                                    ),
                                    ack.msgIds,
                                )
                            } finally {
                                body.fill(0)
                            }
                        }
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
                onServerSaltChanged = { observedSalts += it },
            )
            try {
                val pong = runBlocking { transport.execute(Ping(PING_ID)) } as Pong_fbc65fe5b1
                assertEquals(PING_ID, pong.pingId)
                assertEquals(UPDATED_SALT, session.serverSalt)
                assertEquals(listOf(UPDATED_SALT), observedSalts)
                val newSession = runBlocking { transport.newSessions!!.first() }
                assertEquals(firstRequestMessageId.get(), newSession.firstMessageId)
                assertEquals(77L, newSession.uniqueId)
                assertEquals(UpdatesTooLong, runBlocking { withTimeout(5_000) { transport.updates.receive() } })
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    @Test
    fun retriesOnceWithUpdatedSaltAfterMatchingBadServerSalt() {
        ServerSocket(0).use { server ->
            val clientAuth = authKey()
            val serverAuth = authKey()
            val session = MtProtoEncryptedSession(clientAuth, CounterEntropy(), NOW_MILLIS)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "mtproto-bad-salt-loopback") {
                try {
                    server.accept().use { peer ->
                        val first = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, true)
                        val firstId = first.metadata.messageId
                        val firstSeq = first.metadata.sequenceNumber
                        first.close()
                        val badSalt = encodeObject(BadServerSalt(firstId, firstSeq, 48, UPDATED_SALT))
                        try {
                            writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(1), 1, badSalt)
                        } finally {
                            badSalt.fill(0)
                        }
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()

                        val retried = readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false)
                        retried.use {
                            assertNotEquals(firstId, retried.metadata.messageId)
                            assertEquals(UPDATED_SALT, retried.metadata.serverSalt)
                            val pong = encodeObject(Pong_fbc65fe5b1(retried.metadata.messageId, PING_ID))
                            val result = encodeObject(
                                RpcResult_f5247d1af6(
                                    retried.metadata.messageId,
                                    TlDeferredObject.copyOf(pong, TlLimits.DEFAULT.maxObjectBytes),
                                ),
                            )
                            try {
                                writeServerMessage(peer, serverAuth, session.sessionId, serverMessageId(5), 1, result)
                            } finally {
                                pong.fill(0)
                                result.fill(0)
                            }
                        }
                        readClientMessage(peer.getInputStream(), serverAuth, session.sessionId, false).close()
                    }
                } catch (problem: Throwable) {
                    failure.set(problem)
                } finally {
                    serverAuth.close()
                }
            }

            val transport = IntermediateTcpEncryptedTransport(
                "127.0.0.1",
                server.localPort,
                session,
                TransportConstructorRegistry,
            )
            try {
                val pong = runBlocking { transport.execute(Ping(PING_ID)) } as Pong_fbc65fe5b1
                assertEquals(PING_ID, pong.pingId)
                assertEquals(UPDATED_SALT, session.serverSalt)
            } finally {
                transport.close()
            }
            worker.join(5_000)
            assertTrue("Loopback worker did not finish", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    private fun readClientMessage(
        input: InputStream,
        authKey: MtProtoAuthKey,
        sessionId: Long,
        expectPreamble: Boolean,
    ): MtProtoEncryptedMessage {
        if (expectPreamble) {
            val preamble = readFully(input, 4)
            try {
                assertTrue(preamble.contentEquals(IntermediateTransportFraming.preamble))
            } finally {
                preamble.fill(0)
            }
        }
        val header = readFully(input, 4)
        val payload = try {
            readFully(input, IntermediateTransportFraming.expectedFrameBytes(header) - 4)
        } finally {
            header.fill(0)
        }
        return try {
            EncryptedMessageCodec.decode(authKey, sessionId, payload, EncryptedMessageCodec.CLIENT_X)
        } finally {
            payload.fill(0)
        }
    }

    private fun writeServerMessage(
        peer: java.net.Socket,
        authKey: MtProtoAuthKey,
        sessionId: Long,
        messageId: Long,
        sequenceNumber: Int,
        body: ByteArray,
    ) {
        val packet = EncryptedMessageCodec.encode(
            authKey,
            MtProtoEncryptedMessageMetadata(INITIAL_SALT, sessionId, messageId, sequenceNumber),
            body,
            CounterEntropy(),
            EncryptedMessageCodec.SERVER_X,
        )
        val frame = try {
            IntermediateTransportFraming.encode(packet)
        } finally {
            packet.fill(0)
        }
        try {
            peer.getOutputStream().apply { write(frame); flush() }
        } finally {
            frame.fill(0)
        }
    }

    private fun nestedMessage(messageId: Long, sequenceNumber: Int, body: ByteArray): Message_48a7e89a1b =
        Message_48a7e89a1b(
            messageId,
            sequenceNumber,
            body.size,
            TlDeferredObject.copyOf(body, TlLimits.DEFAULT.maxObjectBytes),
        )

    private fun encodeObject(value: org.monogram.mtproto.tl.runtime.TlObject): ByteArray =
        TlBinaryWriter().also { TransportConstructorRegistry.encode(it, value) }.toByteArray()

    private fun encodeCloudObject(value: org.monogram.mtproto.tl.runtime.TlObject): ByteArray =
        TlBinaryWriter().also { CloudLayer223ConstructorRegistry.encode(it, value) }.toByteArray()

    private fun MtProtoEncryptedMessage.copyBodyAndWipe(): ByteArray {
        val body = copyBody()
        return try {
            body.copyOf()
        } finally {
            body.fill(0)
        }
    }

    private fun constructorId(body: ByteArray): UInt = try {
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
    } finally {
        body.fill(0)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray = ByteArray(count).also { bytes ->
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            check(read >= 0)
            offset += read
        }
    }

    private fun authKey(): MtProtoAuthKey {
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        val idBytes = MtProtoKeyDerivation.authKeyIdBytes(material)
        return try {
            val id = ByteBuffer.wrap(idBytes).order(ByteOrder.LITTLE_ENDIAN).long
            MtProtoAuthKey.restore(material, id, INITIAL_SALT, NOW_SECONDS.toInt())
        } finally {
            idBytes.fill(0)
            material.fill(0)
        }
    }

    private class CounterEntropy : EntropySource {
        private var call = 0
        override fun nextBytes(destination: ByteArray) {
            call++
            destination.fill(call.toByte())
        }
    }

    private companion object {
        const val PING_ID = 42L
        const val INITIAL_SALT = 73L
        const val UPDATED_SALT = 91L
        const val NOW_SECONDS = 1_700_000_000L
        val NOW_MILLIS = { NOW_SECONDS * 1_000L }
        val TRANSPORT_CONTEXT = org.monogram.mtproto.tl.runtime.TlDecodeContext(
            org.monogram.mtproto.tl.runtime.TlSchemaIdentity(
                org.monogram.mtproto.tl.runtime.TlSchemaKind.TRANSPORT,
                null,
            ),
            0,
            TlLimits.DEFAULT,
        )

        fun serverMessageId(lowBits: Long): Long = (NOW_SECONDS shl 32) or lowBits
    }
}
