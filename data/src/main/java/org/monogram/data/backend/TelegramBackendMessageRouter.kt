package org.monogram.data.backend

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.RichTextParsingRepository

/**
 * Keeps TDLib-owned message commands unavailable when the account uses the Kotlin MTProto
 * backend. The snapshot repositories own the currently supported MTProto read path instead.
 */
internal class TelegramBackendMessageRouter(
    selectionStore: TelegramBackendSelectionStore,
    legacyFactory: () -> MessageRepository,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

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
                TelegramBackendKind.KOTLIN_MTPROTO -> {
                    if (Flow::class.java.isAssignableFrom(method.returnType)) emptyFlow<Any>()
                    else unsupported(method)
                }

                null -> error("Telegram backend selection is not loaded")
            }
        }
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
