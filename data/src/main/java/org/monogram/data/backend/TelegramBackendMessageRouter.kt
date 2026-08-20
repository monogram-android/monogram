package org.monogram.data.backend

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.RichTextParsingRepository
import org.monogram.data.mtproto.MtProtoDeleteMessageRepository
import org.monogram.data.mtproto.MtProtoDraftRepository
import org.monogram.data.mtproto.MtProtoPinnedMessageRepository

/**
 * Keeps TDLib-owned message commands unavailable when the account uses the Kotlin MTProto
 * backend. The snapshot repositories own the currently supported MTProto read path instead.
 */
internal class TelegramBackendMessageRouter(
    selectionStore: TelegramBackendSelectionStore,
    legacyFactory: () -> MessageRepository,
    private val draftFactory: () -> MtProtoDraftRepository,
    private val deleteFactory: () -> MtProtoDeleteMessageRepository = {
        MtProtoDeleteMessageRepository { _, _, _ -> }
    },
    private val pinnedFactory: () -> MtProtoPinnedMessageRepository = {
        MtProtoPinnedMessageRepository { _, _, _ -> }
    },
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val drafts by lazy(LazyThreadSafetyMode.NONE, draftFactory)
    private val deletion by lazy(LazyThreadSafetyMode.NONE, deleteFactory)
    private val pinned by lazy(LazyThreadSafetyMode.NONE, pinnedFactory)

    val repository: MessageRepository = Proxy.newProxyInstance(
        MessageRepository::class.java.classLoader,
        arrayOf(MessageRepository::class.java, RichTextParsingRepository::class.java),
        MessageInvocationHandler()
    ) as MessageRepository

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    private inner class MessageInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TelegramBackendMessageRouter"
                    else -> error("Unsupported Object method: ${method.name}")
                }
            }

            return when (selectedBackend.value) {
                TelegramBackendKind.LEGACY -> invokeLegacy(method, args)
                TelegramBackendKind.KOTLIN_MTPROTO -> when (method.name) {
                    "getChatDraft" -> invokeDraft(method, args) { values ->
                        drafts.getDraft(values[0] as Long, values[1] as Long?)
                    }
                    "saveChatDraft" -> invokeDraft(method, args) { values ->
                        drafts.saveDraft(values[0] as Long, values[1] as String, values[2] as Long?, values[3] as Long?)
                    }
                    "deleteMessage" -> invokeDraft(method, args) { values ->
                        deletion.delete(values[0] as Long, values[1] as List<Long>, values[2] as Boolean)
                    }
                    "pinMessage" -> invokeDraft(method, args) { values ->
                        pinned.setPinned(values[0] as Long, values[1] as Long, pinned = true)
                    }
                    "unpinMessage" -> invokeDraft(method, args) { values ->
                        pinned.setPinned(values[0] as Long, values[1] as Long, pinned = false)
                    }
                    else -> if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>() else unsupported(method)
                }

                null -> error("Telegram backend selection is not loaded")
            }
        }
    }

    private fun invokeDraft(
        method: Method,
        args: Array<out Any?>?,
        operation: suspend (Array<out Any?>) -> Any?,
    ): Any? {
        val values = requireNotNull(args) { "Missing arguments for ${method.name}" }
        @Suppress("UNCHECKED_CAST")
        val continuation = values.last() as? Continuation<Any?>
            ?: error("Missing continuation for ${method.name}")
        suspend { operation(values) }.startCoroutine(continuation)
        return COROUTINE_SUSPENDED
    }

    private fun invokeLegacy(method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(legacy, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun unsupported(method: Method): Nothing = throw UnsupportedOperationException(
        "MTProto does not support ${method.name} through MessageRepository"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
