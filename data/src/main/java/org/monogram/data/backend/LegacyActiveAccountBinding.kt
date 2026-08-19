package org.monogram.data.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LegacyActiveAccountBinding(
    initialAccountId: String = DEFAULT_ACCOUNT_ID,
) {
    private val _accountId = MutableStateFlow<String?>(validate(initialAccountId))
    val accountId: StateFlow<String?> = _accountId.asStateFlow()

    fun bind(accountId: String) {
        _accountId.value = validate(accountId)
    }

    fun bindDefault() = bind(DEFAULT_ACCOUNT_ID)

    fun clear(accountId: String) {
        _accountId.compareAndSet(validate(accountId), null)
    }

    fun clearDefault() = clear(DEFAULT_ACCOUNT_ID)

    fun requireActive(accountId: String) {
        check(_accountId.value == validate(accountId)) {
            "Requested account is not bound to the active legacy session"
        }
    }

    private fun validate(accountId: String): String {
        require(ACCOUNT_ID.matches(accountId)) { "Invalid Telegram account id" }
        return accountId
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
        val ACCOUNT_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

internal class LegacyBackendAccessGuard(
    private val activeAccountBinding: LegacyActiveAccountBinding,
    private val backendSelectionStore: TelegramBackendSelectionStore,
) {
    suspend fun requireAccess(accountId: String) {
        activeAccountBinding.requireActive(accountId)
        check(backendSelectionStore.get(accountId) == TelegramBackendKind.LEGACY) {
            "Requested account is not assigned to the legacy backend"
        }
    }
}
