package org.monogram.mtproto.transport

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.mtproto.handshake.MtProtoHandshakeTransport
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind

class IntermediateTcpHandshakeTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
) : MtProtoHandshakeTransport, AutoCloseable {
    private val requestMutex = Mutex()
    private val messageIds = ClientMessageIdGenerator()
    private val stateLock = Any()
    private var socket: Socket? = null
    private var preambleSent = false
    private var closed = false

    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be within 1..65535" }
        require(connectTimeoutMillis in 1..120_000) { "connectTimeoutMillis must be within 1..120000" }
        require(readTimeoutMillis in 1..120_000) { "readTimeoutMillis must be within 1..120000" }
    }

    override suspend fun <R : TlObject> execute(method: TlMethod<R>): R = requestMutex.withLock {
        val job = currentCoroutineContext().job
        val cancellationHandle = job.invokeOnCompletion { cause ->
            if (cause is CancellationException) disconnect()
        }
        try {
            withContext(Dispatchers.IO) { exchange(method) }
        } finally {
            cancellationHandle.dispose()
        }
    }

    private fun <R : TlObject> exchange(method: TlMethod<R>): R {
        val activeSocket = connection()
        val envelope = UnencryptedMessageCodec.encodeMethod(method, messageIds.next())
        val frame = try {
            val includePreamble = synchronized(stateLock) { !preambleSent }
            IntermediateTransportFraming.encode(envelope, includePreamble)
        } finally {
            envelope.fill(0)
        }
        try {
            activeSocket.getOutputStream().apply {
                write(frame)
                flush()
            }
            synchronized(stateLock) { preambleSent = true }
            val response = readMessage(activeSocket)
            return try {
                UnencryptedMessageCodec.decodeResult(method, response, DECODE_CONTEXT)
            } finally {
                response.fill(0)
            }
        } catch (failure: IOException) {
            disconnect()
            throw failure
        } finally {
            frame.fill(0)
        }
    }

    private fun readMessage(activeSocket: Socket): ByteArray {
        val input = activeSocket.getInputStream()
        repeat(MAX_QUICK_ACKS + 1) { index ->
            val header = readFully(input, 4)
            val expectedBytes = try {
                IntermediateTransportFraming.expectedFrameBytes(header)
            } finally {
                if (header.size != 4) header.fill(0)
            }
            if (expectedBytes == 4) {
                header.fill(0)
                if (index == MAX_QUICK_ACKS) throw IOException("Too many intermediate quick acknowledgements")
                return@repeat
            }
            val payload = readFully(input, expectedBytes - 4)
            header.fill(0)
            return payload
        }
        error("Unreachable quick-ack loop")
    }

    private fun readFully(input: java.io.InputStream, byteCount: Int): ByteArray {
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = input.read(result, offset, byteCount - offset)
            if (read < 0) {
                result.fill(0)
                throw EOFException("Intermediate TCP stream ended mid-frame")
            }
            offset += read
        }
        return result
    }

    private fun connection(): Socket = synchronized(stateLock) {
        check(!closed) { "Transport is closed" }
        socket?.takeIf { it.isConnected && !it.isClosed } ?: Socket().also { created ->
            try {
                created.soTimeout = readTimeoutMillis
                created.tcpNoDelay = true
                created.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                socket = created
                preambleSent = false
            } catch (failure: IOException) {
                runCatching { created.close() }
                throw failure
            }
        }
    }

    private fun disconnect() = synchronized(stateLock) {
        val current = socket
        socket = null
        preambleSent = false
        runCatching { current?.close() }
        Unit
    }

    override fun close() = synchronized(stateLock) {
        closed = true
        val current = socket
        socket = null
        preambleSent = false
        runCatching { current?.close() }
        Unit
    }

    private companion object {
        const val MAX_QUICK_ACKS = 16
        val DECODE_CONTEXT = TlDecodeContext(
            TlSchemaIdentity(TlSchemaKind.TRANSPORT, null),
            0,
            TlLimits.DEFAULT,
        )
    }
}
