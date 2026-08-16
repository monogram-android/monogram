package org.monogram.mtproto.transport

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.monogram.mtproto.codec.TlBinaryCodec
import org.monogram.mtproto.codec.TlBinaryWriter
import org.monogram.mtproto.codec.TlGzip
import org.monogram.mtproto.tl.generated.cloud.layer223.registry.CloudLayer223ConstructorRegistry
import org.monogram.mtproto.tl.generated.transport.BadMsgNotification_96e011accc
import org.monogram.mtproto.tl.generated.transport.BadServerSalt
import org.monogram.mtproto.tl.generated.transport.GzipPacked
import org.monogram.mtproto.tl.generated.transport.MsgContainer
import org.monogram.mtproto.tl.generated.transport.MsgsAck_3546e430bb
import org.monogram.mtproto.tl.generated.transport.Message_48a7e89a1b
import org.monogram.mtproto.tl.generated.transport.NewSessionCreated
import org.monogram.mtproto.tl.generated.transport.RpcError_134c3d92c4
import org.monogram.mtproto.tl.generated.transport.RpcError_134c3d92c4Codec
import org.monogram.mtproto.tl.generated.transport.RpcResult_f5247d1af6
import org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlMethodRegistry
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind

interface MtProtoRpcTransport {
    suspend fun <R> execute(method: TlMethod<R>): R
}

class MtProtoRpcException(
    val errorCode: Int,
    val rpcMessage: String,
) : IllegalStateException("MTProto RPC failed with code $errorCode: $rpcMessage")

/** Owns [session]; closing the transport closes the session and destroys its auth key. */
class IntermediateTcpEncryptedTransport(
    private val host: String,
    private val port: Int,
    private val session: MtProtoEncryptedSession,
    private val methodRegistry: TlMethodRegistry = CloudLayer223ConstructorRegistry,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
) : MtProtoRpcTransport, AutoCloseable {
    private val requestMutex = Mutex()
    private val stateLock = Any()
    private var socket: Socket? = null
    private var preambleSent = false
    private var closed = false
    private val methodContext = TlDecodeContext(methodRegistry.schema, 0, TlLimits.DEFAULT)

    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be within 1..65535" }
        require(connectTimeoutMillis in 1..120_000) { "connectTimeoutMillis must be within 1..120000" }
        require(readTimeoutMillis in 1..120_000) { "readTimeoutMillis must be within 1..120000" }
    }

    override suspend fun <R> execute(method: TlMethod<R>): R = requestMutex.withLock {
        supervisorScope {
            val exchange = async(Dispatchers.IO) { exchange(method) }
            try {
                exchange.await()
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("MTProto RPC was cancelled").apply { initCause(failure) }
                }
                throw failure
            } finally {
                if (!currentCoroutineContext().isActive) disconnect()
                withContext(NonCancellable) { exchange.join() }
            }
        }
    }

    private fun <R> exchange(method: TlMethod<R>): R {
        var saltRetries = 0
        while (true) {
            val requestBody = encodeMethod(method)
            val request = try {
                session.encodeTracked(requestBody, contentRelated = true)
            } finally {
                requestBody.fill(0)
            }
            try {
                writePacket(request.packet)
                when (val outcome = readOutcome(method, request.metadata)) {
                    is RpcOutcome.Success -> return outcome.value
                    RpcOutcome.Continue -> error("readOutcome returned without a terminal result")
                    RpcOutcome.RetrySalt -> {
                        if (saltRetries++ >= MAX_SALT_RETRIES) {
                            throw protocolFailure("Repeated bad_server_salt for one request")
                        }
                    }
                }
            } catch (rpc: MtProtoRpcException) {
                if (rpc.suppressed.isNotEmpty()) disconnect()
                throw rpc
            } catch (failure: Throwable) {
                disconnect()
                throw failure
            } finally {
                request.packet.fill(0)
            }
        }
    }

    private fun <R> readOutcome(method: TlMethod<R>, request: MtProtoEncryptedMessageMetadata): RpcOutcome<R> {
        repeat(MAX_SERVICE_ENVELOPES) {
            val packet = readMessage(connection())
            val decoded = try {
                session.decodeTracked(packet)
            } finally {
                packet.fill(0)
            }
            val metadata = decoded.message.metadata
            if (decoded.duplicate) {
                val acknowledgements = if (metadata.sequenceNumber and 1 == 1) {
                    listOf(metadata.messageId)
                } else {
                    emptyList()
                }
                decoded.message.close()
                sendAcknowledgements(acknowledgements)
                return@repeat
            }
            val body = try {
                decoded.message.copyBody()
            } finally {
                decoded.message.close()
            }
            val acknowledgements = mutableListOf<Long>()
            var outcome: RpcOutcome<R>? = null
            var processingFailure: Exception? = null
            try {
                outcome = processBody(method, request, metadata, body, acknowledgements, depth = 0)
            } catch (failure: Exception) {
                processingFailure = failure
            } finally {
                body.fill(0)
            }
            try {
                sendAcknowledgements(acknowledgements)
            } catch (acknowledgementFailure: Exception) {
                processingFailure?.addSuppressed(acknowledgementFailure)
                throw processingFailure ?: acknowledgementFailure
            }
            processingFailure?.let { throw it }
            if (outcome !is RpcOutcome.Continue) return checkNotNull(outcome)
        }
        throw protocolFailure("Too many service messages before RPC result")
    }

    private fun <R> processBody(
        method: TlMethod<R>,
        request: MtProtoEncryptedMessageMetadata,
        metadata: MtProtoEncryptedMessageMetadata,
        body: ByteArray,
        acknowledgements: MutableList<Long>,
        depth: Int,
    ): RpcOutcome<R> {
        require(depth <= MAX_CONTAINER_DEPTH) { "MTProto container nesting exceeds the limit" }
        if (metadata.sequenceNumber and 1 == 1) acknowledgements += metadata.messageId
        return when (val value = decodeTransportObject(body)) {
            is RpcResult_f5247d1af6 -> {
                require(value.reqMsgId == request.messageId) { "RPC result does not match the active request" }
                RpcOutcome.Success(decodeResult(method, value.result, depth))
            }
            is MsgContainer -> processContainer(method, request, metadata, value, acknowledgements, depth + 1)
            is GzipPacked -> {
                val unpacked = TlGzip.decompress(value.packedData, TRANSPORT_CONTEXT).toByteArray()
                try {
                    processBody(method, request, metadata, unpacked, acknowledgements, depth + 1)
                } finally {
                    unpacked.fill(0)
                }
            }
            is NewSessionCreated -> {
                session.updateServerSalt(value.serverSalt)
                RpcOutcome.Continue
            }
            is BadServerSalt -> {
                require(value.badMsgId == request.messageId && value.badMsgSeqno == request.sequenceNumber) {
                    "bad_server_salt does not match the active request"
                }
                session.updateServerSalt(value.newServerSalt)
                RpcOutcome.RetrySalt
            }
            is BadMsgNotification_96e011accc -> {
                require(value.badMsgId == request.messageId && value.badMsgSeqno == request.sequenceNumber) {
                    "bad_msg_notification does not match the active request"
                }
                throw protocolFailure("Server rejected the active request with code ${value.errorCode}")
            }
            is MsgsAck_3546e430bb -> RpcOutcome.Continue
            else -> throw protocolFailure("Unsupported MTProto service object ${value.constructorId}")
        }
    }

    private fun <R> processContainer(
        method: TlMethod<R>,
        request: MtProtoEncryptedMessageMetadata,
        outer: MtProtoEncryptedMessageMetadata,
        container: MsgContainer,
        acknowledgements: MutableList<Long>,
        depth: Int,
    ): RpcOutcome<R> {
        var terminal: RpcOutcome<R>? = null
        container.messages.forEach { nested ->
            val nestedMessage = nested as? Message_48a7e89a1b
                ?: throw protocolFailure("Unsupported message representation in container")
            val nestedMetadata = MtProtoEncryptedMessageMetadata(
                serverSalt = outer.serverSalt,
                sessionId = outer.sessionId,
                messageId = nestedMessage.msgId,
                sequenceNumber = nestedMessage.seqno,
            )
            if (!session.admitNested(nestedMetadata)) {
                if (nestedMetadata.sequenceNumber and 1 == 1) acknowledgements += nestedMetadata.messageId
                return@forEach
            }
            val bytes = nestedMessage.body.toByteArray()
            val outcome = try {
                processBody(method, request, nestedMetadata, bytes, acknowledgements, depth)
            } finally {
                bytes.fill(0)
            }
            if (outcome !is RpcOutcome.Continue) {
                require(terminal == null) { "Container contains multiple terminal RPC outcomes" }
                terminal = outcome
            }
        }
        return terminal ?: RpcOutcome.Continue
    }

    private fun <R> decodeResult(method: TlMethod<R>, deferred: TlDeferredObject, depth: Int): R {
        require(depth <= MAX_CONTAINER_DEPTH) { "MTProto result nesting exceeds the limit" }
        val bytes = deferred.toByteArray()
        try {
            return when (constructorId(bytes)) {
                RpcError_134c3d92c4.CONSTRUCTOR_ID -> {
                    val error = TlBinaryCodec.decode(RpcError_134c3d92c4Codec, bytes, TRANSPORT_CONTEXT)
                    throw MtProtoRpcException(error.errorCode, error.errorMessage)
                }
                GzipPacked.CONSTRUCTOR_ID -> {
                    val gzip = TlBinaryCodec.decode(
                        org.monogram.mtproto.tl.generated.transport.GzipPackedCodec,
                        bytes,
                        TRANSPORT_CONTEXT,
                    )
                    val unpacked = TlGzip.decompress(gzip.packedData, methodContext).toByteArray()
                    try {
                        decodeResult(method, TlDeferredObject.copyOf(unpacked, methodContext.limits.maxObjectBytes), depth + 1)
                    } finally {
                        unpacked.fill(0)
                    }
                }
                else -> TlBinaryCodec.decode(method.resultCodec, bytes, methodContext)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun decodeTransportObject(body: ByteArray): TlObject =
        TlBinaryCodec.decodeObject(TransportConstructorRegistry, body, TRANSPORT_CONTEXT)

    private fun encodeMethod(method: TlMethod<*>): ByteArray =
        TlBinaryWriter().also { methodRegistry.encodeMethod(it, method) }.toByteArray()

    private fun sendAcknowledgements(messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        val writer = TlBinaryWriter()
        TransportConstructorRegistry.encode(writer, MsgsAck_3546e430bb(messageIds.distinct()))
        val body = writer.toByteArray()
        val acknowledgement = try {
            session.encodeTracked(body, contentRelated = false)
        } finally {
            body.fill(0)
        }
        try {
            writePacket(acknowledgement.packet)
        } finally {
            acknowledgement.packet.fill(0)
        }
    }

    private fun writePacket(packet: ByteArray) {
        val activeSocket = connection()
        val frame = IntermediateTransportFraming.encode(
            packet,
            includePreamble = synchronized(stateLock) { !preambleSent },
        )
        try {
            activeSocket.getOutputStream().apply {
                write(frame)
                flush()
            }
            synchronized(stateLock) { preambleSent = true }
        } finally {
            frame.fill(0)
        }
    }

    private fun readMessage(activeSocket: Socket): ByteArray {
        val input = activeSocket.getInputStream()
        repeat(MAX_QUICK_ACKS + 1) { index ->
            val header = readFully(input, 4)
            try {
                val expectedBytes = IntermediateTransportFraming.expectedFrameBytes(header)
                if (expectedBytes == 4) {
                    if (index == MAX_QUICK_ACKS) throw protocolFailure("Too many intermediate quick acknowledgements")
                    return@repeat
                }
                return readFully(input, expectedBytes - 4)
            } finally {
                header.fill(0)
            }
        }
        error("Unreachable quick-ack loop")
    }

    private fun readFully(input: java.io.InputStream, byteCount: Int): ByteArray {
        val result = ByteArray(byteCount)
        try {
            var offset = 0
            while (offset < byteCount) {
                val read = input.read(result, offset, byteCount - offset)
                if (read < 0) throw EOFException("Intermediate TCP stream ended mid-frame")
                offset += read
            }
            return result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        }
    }

    private fun connection(): Socket {
        val activeSocket = synchronized(stateLock) {
            check(!closed) { "Transport is closed" }
            socket?.takeIf { !it.isClosed } ?: Socket().also { created ->
                socket = created
                preambleSent = false
            }
        }
        if (activeSocket.isConnected) return activeSocket
        try {
            activeSocket.soTimeout = readTimeoutMillis
            activeSocket.tcpNoDelay = true
            activeSocket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            synchronized(stateLock) {
                check(!closed && socket === activeSocket && !activeSocket.isClosed) {
                    "Transport was closed while connecting"
                }
            }
            return activeSocket
        } catch (failure: Throwable) {
            synchronized(stateLock) {
                if (socket === activeSocket) socket = null
            }
            runCatching { activeSocket.close() }
            throw failure
        }
    }

    private fun disconnect() = synchronized(stateLock) {
        val current = socket
        socket = null
        preambleSent = false
        runCatching { current?.close() }
        Unit
    }

    override fun close() {
        synchronized(stateLock) {
            closed = true
            val current = socket
            socket = null
            preambleSent = false
            runCatching { current?.close() }
        }
        session.close()
    }

    private fun constructorId(bytes: ByteArray): UInt {
        require(bytes.size >= Int.SIZE_BYTES) { "Truncated MTProto object" }
        return ByteBuffer.wrap(bytes, 0, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
    }

    private fun protocolFailure(message: String): IllegalArgumentException = IllegalArgumentException(message)

    private sealed interface RpcOutcome<out R> {
        data object Continue : RpcOutcome<Nothing>
        data object RetrySalt : RpcOutcome<Nothing>
        data class Success<R>(val value: R) : RpcOutcome<R>
    }

    private companion object {
        const val MAX_QUICK_ACKS = 16
        const val MAX_SERVICE_ENVELOPES = 64
        const val MAX_CONTAINER_DEPTH = 8
        const val MAX_SALT_RETRIES = 1
        val TRANSPORT_CONTEXT = TlDecodeContext(
            TlSchemaIdentity(TlSchemaKind.TRANSPORT, null),
            0,
            TlLimits.DEFAULT,
        )
    }
}
