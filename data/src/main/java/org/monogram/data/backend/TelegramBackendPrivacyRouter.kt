package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.monogram.domain.models.PrivacyRule
import org.monogram.domain.repository.PrivacyKey
import org.monogram.domain.repository.PrivacyRepository

internal class TelegramBackendPrivacyRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> PrivacyRepository,
    private val mtProtoFactory: () -> PrivacyRepository,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : PrivacyRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }

    override fun getPrivacyRules(key: PrivacyKey): Flow<List<PrivacyRule>> = flow { emitAll(selected().getPrivacyRules(key)) }
    override suspend fun setPrivacyRule(key: PrivacyKey, rules: List<PrivacyRule>) = selected().setPrivacyRule(key, rules)
    override suspend fun getBlockedUsers() = selected().getBlockedUsers()
    override suspend fun blockUser(userId: Long) = selected().blockUser(userId)
    override suspend fun unblockUser(userId: Long) = selected().unblockUser(userId)
    override suspend fun deleteAccount(reason: String, password: String) = selected().deleteAccount(reason, password)
    override suspend fun getAccountTtl() = selected().getAccountTtl()
    override suspend fun setAccountTtl(days: Int) = selected().setAccountTtl(days)
    override suspend fun getPasswordState() = selected().getPasswordState()
    override suspend fun canShowSensitiveContent() = selected().canShowSensitiveContent()
    override suspend fun isShowSensitiveContentEnabled() = selected().isShowSensitiveContentEnabled()
    override suspend fun setShowSensitiveContent(enabled: Boolean) = selected().setShowSensitiveContent(enabled)

    private fun selected(): PrivacyRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }
    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
