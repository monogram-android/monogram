package org.monogram.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.domain.models.TdLibLimitOptionNames
import org.monogram.domain.models.TdLibLimits
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.TdLibLimitsRepository

class TdLibLimitsRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope
) : TdLibLimitsRepository {
    private val _limits = MutableStateFlow(TdLibLimits.DEFAULTS)
    override val limits: StateFlow<TdLibLimits> = _limits.asStateFlow()

    private val refreshMutex = Mutex()
    private val limitsMutex = Mutex()
    private var isAuthorized = false
    private var premiumAccount: Boolean? = null
    private var cachedPremiumTextLimit: TdApi.PremiumLimit? = null
    private var cachedPremiumCaptionLimit: TdApi.PremiumLimit? = null

    init {
        scope.launch {
            updates.option.collect { update ->
                if (update.name in OPTION_NAMES) {
                    limitsMutex.withLock {
                        _limits.update {
                            it.withOption(
                                update.name,
                                resolveOptionUpdateValue(update.name, update.value.toIntOrNull())
                            )
                        }
                    }
                } else if (update.name == TdLibLimitOptionNames.IS_PREMIUM) {
                    refresh()
                }
            }
        }
        scope.launch {
            authRepository.authState.collect { authState ->
                when (authState) {
                    is AuthStep.Ready -> {
                        if (!isAuthorized) {
                            isAuthorized = true
                            refresh()
                        }
                    }

                    else -> {
                        isAuthorized = false
                        limitsMutex.withLock {
                            premiumAccount = null
                            cachedPremiumTextLimit = null
                            cachedPremiumCaptionLimit = null
                            _limits.value = TdLibLimits.DEFAULTS
                        }
                    }
                }
            }
        }
    }

    override suspend fun refresh() {
        refreshMutex.withLock {
            val values = supervisorScope {
                OPTION_NAMES.map { name ->
                    async { name to readIntegerOption(name) }
                }.awaitAll().toMap()
            }
            val isPremium = readBooleanOption(TdLibLimitOptionNames.IS_PREMIUM)
            val premiumTextLimit = readPremiumLimit(TdApi.PremiumLimitTypeMessageTextLength())
            val premiumCaptionLimit = readPremiumLimit(TdApi.PremiumLimitTypeCaptionLength())
            val resolvedValues = values.toMutableMap().apply {
                this[TdLibLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX] = resolvePremiumLimit(
                    optionValue = values[TdLibLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX],
                    premiumLimit = premiumTextLimit,
                    isPremium = isPremium,
                    premiumFallback = TdLibLimits.DEFAULT_PREMIUM_MESSAGE_TEXT_LENGTH_MAX
                )
                this[TdLibLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX] = resolvePremiumLimit(
                    optionValue = values[TdLibLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX],
                    premiumLimit = premiumCaptionLimit,
                    isPremium = isPremium,
                    premiumFallback = TdLibLimits.DEFAULT_PREMIUM_MESSAGE_CAPTION_LENGTH_MAX
                )
            }
            Log.d(
                TAG,
                "TDLib limits loaded: ${resolvedValues.toSortedMap()} " +
                        "premium=$isPremium " +
                        "premiumLimits={message_text_length_max=${premiumTextLimit?.defaultValue}/${premiumTextLimit?.premiumValue}, " +
                        "message_caption_length_max=${premiumCaptionLimit?.defaultValue}/${premiumCaptionLimit?.premiumValue}}"
            )
            limitsMutex.withLock {
                premiumAccount = isPremium
                cachedPremiumTextLimit = premiumTextLimit
                cachedPremiumCaptionLimit = premiumCaptionLimit
                _limits.update { current ->
                    resolvedValues.entries.fold(current) { limits, (name, value) ->
                        limits.withOption(name, value)
                    }
                }
            }
        }
    }

    private fun resolveOptionUpdateValue(name: String, optionValue: Int?): Int? = when (name) {
        TdLibLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX -> resolvePremiumLimit(
            optionValue = optionValue,
            premiumLimit = cachedPremiumTextLimit,
            isPremium = premiumAccount,
            premiumFallback = TdLibLimits.DEFAULT_PREMIUM_MESSAGE_TEXT_LENGTH_MAX
        )

        TdLibLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX -> resolvePremiumLimit(
            optionValue = optionValue,
            premiumLimit = cachedPremiumCaptionLimit,
            isPremium = premiumAccount,
            premiumFallback = TdLibLimits.DEFAULT_PREMIUM_MESSAGE_CAPTION_LENGTH_MAX
        )

        else -> optionValue
    }

    private suspend fun readIntegerOption(name: String): Int? =
        runCatching { remote.getOption(name) }
            .getOrNull()
            .toIntOrNull()

    private suspend fun readBooleanOption(name: String): Boolean? =
        runCatching { remote.getOption(name) }
            .getOrNull()
            ?.let { (it as? TdApi.OptionValueBoolean)?.value }

    private suspend fun readPremiumLimit(limitType: TdApi.PremiumLimitType): TdApi.PremiumLimit? =
        runCatching { remote.getPremiumLimit(limitType) }.getOrNull()

    private fun resolvePremiumLimit(
        optionValue: Int?,
        premiumLimit: TdApi.PremiumLimit?,
        isPremium: Boolean?,
        premiumFallback: Int
    ): Int? = when (isPremium) {
        true -> premiumLimit?.premiumValue ?: premiumFallback
        false -> premiumLimit?.defaultValue ?: optionValue
        null -> optionValue
    }

    private fun TdApi.OptionValue?.toIntOrNull(): Int? {
        val value = (this as? TdApi.OptionValueInteger)?.value ?: return null
        return value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val TAG = "TdLibLimits"
        val OPTION_NAMES = TdLibLimitOptionNames.ALL
    }
}
