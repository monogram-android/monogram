package org.monogram.mtproto.transport

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.mtproto.tl.generated.cloud.layer223.InitConnection
import org.monogram.mtproto.tl.generated.cloud.layer223.InvokeWithLayer
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonNumber
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObject
import org.monogram.mtproto.tl.generated.cloud.layer223.JsonObjectValue_c7a772e90b
import org.monogram.mtproto.tl.runtime.TlMethod

data class CloudLayer223ConnectionConfig(
    val apiId: Int,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
    val systemLanguageCode: String,
    val languagePack: String = "",
    val languageCode: String = "",
    val timeZoneOffsetSeconds: Int? = null,
) {
    init {
        require(apiId > 0) { "apiId must be positive" }
        require(deviceModel.isNotBlank()) { "deviceModel must not be blank" }
        require(systemVersion.isNotBlank()) { "systemVersion must not be blank" }
        require(applicationVersion.isNotBlank()) { "applicationVersion must not be blank" }
        require(systemLanguageCode.isNotBlank()) { "systemLanguageCode must not be blank" }
        require(timeZoneOffsetSeconds == null || timeZoneOffsetSeconds in MIN_TIME_ZONE_OFFSET..MAX_TIME_ZONE_OFFSET) {
            "timeZoneOffsetSeconds is outside the supported range"
        }
    }

    private companion object {
        const val MIN_TIME_ZONE_OFFSET = -18 * 60 * 60
        const val MAX_TIME_ZONE_OFFSET = 18 * 60 * 60
    }
}

/** Adds the layer-223 connection header until the server accepts an API request. */
class CloudLayer223RpcTransport(
    private val delegate: MtProtoRpcTransport,
    private val config: CloudLayer223ConnectionConfig,
) : MtProtoRpcTransport {
    private val mutex = Mutex()
    private val closed = AtomicBoolean()
    private var headerRequired = true
    private val languagePack = if (config.languageCode.startsWith(CUSTOM_LANGUAGE_PREFIX)) "" else config.languagePack
    private val languageCode = when {
        languagePack.isEmpty() -> ""
        config.languageCode.isEmpty() -> DEFAULT_LANGUAGE_CODE
        else -> config.languageCode
    }

    override val updates: MtProtoApiUpdateInbox?
        get() = delegate.updates

    override suspend fun <R> execute(method: TlMethod<R>): R = mutex.withLock {
        check(!closed.get()) { "Transport is closed" }
        val includeHeader = headerRequired
        MtProtoTransportLog.debug {
            "send api method=${method.debugName()} connectionHeader=$includeHeader"
        }
        try {
            delegate.execute(if (includeHeader) wrap(method) else method).also { result ->
                headerRequired = false
                MtProtoTransportLog.debug {
                    "receive api method=${method.debugName()} result=${result.debugName()}"
                }
            }
        } catch (rpc: MtProtoRpcException) {
            if (includeHeader || !rpc.requiresConnectionHeader()) {
                MtProtoTransportLog.warn {
                    "api failure method=${method.debugName()} code=${rpc.errorCode} error=${MtProtoTransportLog.sanitizeRpcError(rpc.rpcMessage)}"
                }
                throw rpc
            }
            MtProtoTransportLog.debug {
                "retry api method=${method.debugName()} with connection header"
            }
            headerRequired = true
            check(!closed.get()) { "Transport is closed" }
            delegate.execute(wrap(method)).also { result ->
                headerRequired = false
                MtProtoTransportLog.debug {
                    "receive api method=${method.debugName()} result=${result.debugName()}"
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }

    private fun org.monogram.mtproto.tl.runtime.TlObject.debugName(): String =
        "${this::class.java.simpleName}#${constructorId.toString(16)}"

    private fun Any?.debugName(): String = when (this) {
        null -> "null"
        is org.monogram.mtproto.tl.runtime.TlObject -> debugName()
        else -> this::class.java.simpleName
    }

    private fun <R> wrap(method: TlMethod<R>): TlMethod<R> = InvokeWithLayer(
        layer = LAYER,
        query = InitConnection(
            apiId = config.apiId,
            deviceModel = config.deviceModel,
            systemVersion = config.systemVersion,
            appVersion = config.applicationVersion,
            systemLangCode = config.systemLanguageCode,
            langPack = languagePack,
            langCode = languageCode,
            proxy = null,
            params = config.timeZoneOffsetSeconds?.let { offset ->
                JsonObject(listOf(JsonObjectValue_c7a772e90b(TIME_ZONE_OFFSET_KEY, JsonNumber(offset.toDouble()))))
            },
            query = method,
        ),
    )

    private fun MtProtoRpcException.requiresConnectionHeader(): Boolean =
        errorCode == CONNECTION_ERROR_CODE &&
            (rpcMessage == CONNECTION_NOT_INITED || rpcMessage == CONNECTION_LAYER_INVALID)

    private companion object {
        const val LAYER = 223
        const val CONNECTION_ERROR_CODE = 400
        const val CONNECTION_NOT_INITED = "CONNECTION_NOT_INITED"
        const val CONNECTION_LAYER_INVALID = "CONNECTION_LAYER_INVALID"
        const val CUSTOM_LANGUAGE_PREFIX = "X"
        const val DEFAULT_LANGUAGE_CODE = "en"
        const val TIME_ZONE_OFFSET_KEY = "tz_offset"
    }
}
