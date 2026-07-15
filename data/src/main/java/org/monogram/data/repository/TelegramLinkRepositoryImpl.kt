package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.telegram.TelegramLinkDomains
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.domain.repository.TelegramLinkRepository

class TelegramLinkRepositoryImpl(
    private val gateway: TelegramGateway,
    private val updates: UpdateDispatcher,
    private val keyValueDao: KeyValueDao,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider
) : TelegramLinkRepository {

    private val _baseUrl = MutableStateFlow(TelegramLinkDomains.DEFAULT_BASE_URL)
    override val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()
    private val initialCacheLoad = CompletableDeferred<Unit>()
    private val refreshMutex = Mutex()

    @Volatile
    private var hasLoadedOptionValue = false

    @Volatile
    private var startupFetchRequested = false

    init {
        scope.launch(dispatchers.io) {
            combine(
                keyValueDao.observeValue(TELEGRAM_KEY_T_ME_URL),
                keyValueDao.observeValue(TELEGRAM_KEY_T_ME_URL_LOADED)
            ) { baseUrlEntity, loadedEntity ->
                CachedTelegramBaseUrl(
                    baseUrl = baseUrlEntity?.value.normalizeTelegramBaseUrl()
                        ?: TelegramLinkDomains.DEFAULT_BASE_URL,
                    isLoadedFromOption = loadedEntity?.value.toTelegramLinkLoadedFlag()
                )
            }.collect { cached ->
                _baseUrl.value = cached.baseUrl
                hasLoadedOptionValue = cached.isLoadedFromOption
                if (!initialCacheLoad.isCompleted) {
                    initialCacheLoad.complete(Unit)
                }
            }
        }

        scope.launch(dispatchers.io) {
            gateway.isAuthenticated.collect { authenticated ->
                if (authenticated && !startupFetchRequested) {
                    startupFetchRequested = true
                    fetchBaseUrlFromOption()
                }
            }
        }

        scope.launch(dispatchers.io) {
            updates.all.collect { update ->
                if (update is TdApi.UpdateOption && update.name == TELEGRAM_OPTION_T_ME_URL) {
                    val value = (update.value as? TdApi.OptionValueString)?.value
                    persistBaseUrl(
                        baseUrl = value.normalizeTelegramBaseUrl(),
                        isLoadedFromOption = value != null
                    )
                }
            }
        }
    }

    private suspend fun fetchBaseUrlFromOption(): String {
        return refreshMutex.withLock {
            val option = coRunCatching {
                gateway.execute(TdApi.GetOption(TELEGRAM_OPTION_T_ME_URL))
            }.getOrNull()
            val resolved = (option as? TdApi.OptionValueString)?.value.normalizeTelegramBaseUrl()
            persistBaseUrl(
                baseUrl = resolved,
                isLoadedFromOption = resolved != null
            )
            _baseUrl.value
        }
    }

    override suspend fun buildUrl(path: String): String {
        val resolvedBaseUrl = ensureBaseUrl()
        return buildTelegramUrl(resolvedBaseUrl, path)
    }

    private suspend fun ensureBaseUrl(): String {
        if (!initialCacheLoad.isCompleted) {
            initialCacheLoad.await()
        }
        return _baseUrl.value
    }

    private suspend fun persistBaseUrl(
        baseUrl: String?,
        isLoadedFromOption: Boolean
    ) {
        val normalized = baseUrl.normalizeTelegramBaseUrl() ?: TelegramLinkDomains.DEFAULT_BASE_URL
        _baseUrl.value = normalized
        hasLoadedOptionValue = isLoadedFromOption
        coRunCatching {
            keyValueDao.persistTelegramBaseUrl(normalized, isLoadedFromOption)
        }
    }
}

private data class CachedTelegramBaseUrl(
    val baseUrl: String,
    val isLoadedFromOption: Boolean
)

internal fun String?.normalizeTelegramBaseUrl(): String? {
    val value = this?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}"
        else -> "https://$value"
    }
}

internal fun buildTelegramUrl(baseUrl: String, path: String): String {
    val normalizedBaseUrl =
        baseUrl.normalizeTelegramBaseUrl() ?: TelegramLinkDomains.DEFAULT_BASE_URL
    val normalizedPath = path.trim().trimStart('/')
    return if (normalizedPath.isEmpty()) normalizedBaseUrl else "$normalizedBaseUrl/$normalizedPath"
}
