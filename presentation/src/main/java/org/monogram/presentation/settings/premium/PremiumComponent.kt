package org.monogram.presentation.settings.premium

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.models.PremiumFeatureType
import org.monogram.domain.models.PremiumLimitType
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface PremiumComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onSubscribeClicked()

    data class State(
        val features: List<PremiumFeature> = emptyList(),
        val isLoading: Boolean = false,
        val isPremium: Boolean = false,
        val statusText: String? = null
    )

    data class PremiumFeature(
        val icon: String,
        val title: String,
        val description: String,
        val color: Long
    )
}

class DefaultPremiumComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : PremiumComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    private val stringProvider = container.utils.stringProvider()
    private val scope = componentScope

    private val _state = MutableValue(PremiumComponent.State())
    override val state: Value<PremiumComponent.State> = _state

    init {
        userRepository.currentUserFlow.onEach { user ->
            if (user != null) {
                _state.update { it.copy(isPremium = user.isPremium) }
            }
        }.launchIn(scope)

        loadPremiumState()
    }

    private fun loadPremiumState() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }

            val premiumState = userRepository.getPremiumState()
            val features = userRepository.getPremiumFeatures(PremiumSource.SETTINGS)

            val mappedFeatures = features.mapNotNull { featureType ->
                mapToPremiumFeature(featureType)
            }

            _state.update {
                it.copy(
                    features = mappedFeatures,
                    statusText = premiumState?.state,
                    isLoading = false
                )
            }
        }
    }

    private suspend fun mapToPremiumFeature(featureType: PremiumFeatureType): PremiumComponent.PremiumFeature? {
        return when (featureType) {
            PremiumFeatureType.DOUBLE_LIMITS -> {
                val channels = userRepository.getPremiumLimit(PremiumLimitType.SUPERGROUP_COUNT)
                val folders = userRepository.getPremiumLimit(PremiumLimitType.CHAT_FOLDER_COUNT)
                val pins = userRepository.getPremiumLimit(PremiumLimitType.PINNED_CHAT_COUNT)
                val publicLinks = userRepository.getPremiumLimit(PremiumLimitType.CREATED_PUBLIC_CHAT_COUNT)
                PremiumComponent.PremiumFeature(
                    icon = "star",
                    title = stringProvider.getString("premium_feature_doubled_limits_title"),
                    description = stringProvider.getString(
                        "premium_feature_doubled_limits_description",
                        channels,
                        folders,
                        pins,
                        publicLinks
                    ),
                    color = 0xFFAF52DE
                )
            }

            PremiumFeatureType.VOICE_TO_TEXT -> PremiumComponent.PremiumFeature(
                icon = "mic",
                title = stringProvider.getString("premium_feature_voice_to_text_title"),
                description = stringProvider.getString("premium_feature_voice_to_text_description"),
                color = 0xFF4285F4
            )

            PremiumFeatureType.FASTER_DOWNLOAD -> PremiumComponent.PremiumFeature(
                icon = "download",
                title = stringProvider.getString("premium_feature_faster_download_title"),
                description = stringProvider.getString("premium_feature_faster_download_description"),
                color = 0xFF34A853
            )

            PremiumFeatureType.TRANSLATION -> PremiumComponent.PremiumFeature(
                icon = "translate",
                title = stringProvider.getString("premium_feature_translation_title"),
                description = stringProvider.getString("premium_feature_translation_description"),
                color = 0xFFF9AB00
            )

            PremiumFeatureType.ANIMATED_EMOJI -> PremiumComponent.PremiumFeature(
                icon = "face",
                title = stringProvider.getString("premium_feature_animated_emoji_title"),
                description = stringProvider.getString("premium_feature_animated_emoji_description"),
                color = 0xFFFF6D66
            )

            PremiumFeatureType.ADVANCED_CHAT_MANAGEMENT -> PremiumComponent.PremiumFeature(
                icon = "folder",
                title = stringProvider.getString("premium_feature_chat_management_title"),
                description = stringProvider.getString("premium_feature_chat_management_description"),
                color = 0xFF536DFE
            )

            PremiumFeatureType.NO_ADS -> PremiumComponent.PremiumFeature(
                icon = "block",
                title = stringProvider.getString("premium_feature_no_ads_title"),
                description = stringProvider.getString("premium_feature_no_ads_description"),
                color = 0xFF00BFA5
            )

            PremiumFeatureType.INFINITE_REACTIONS -> PremiumComponent.PremiumFeature(
                icon = "heart",
                title = stringProvider.getString("premium_feature_infinite_reactions_title"),
                description = stringProvider.getString("premium_feature_infinite_reactions_description"),
                color = 0xFFFF6D66
            )

            PremiumFeatureType.BADGE -> PremiumComponent.PremiumFeature(
                icon = "verified",
                title = stringProvider.getString("premium_feature_badge_title"),
                description = stringProvider.getString("premium_feature_badge_description"),
                color = 0xFF24A1DE
            )

            PremiumFeatureType.PROFILE_BADGE -> PremiumComponent.PremiumFeature(
                icon = "face",
                title = stringProvider.getString("premium_feature_emoji_status_title"),
                description = stringProvider.getString("premium_feature_emoji_status_description"),
                color = 0xFFF9AB00
            )

            PremiumFeatureType.APP_ICONS -> PremiumComponent.PremiumFeature(
                icon = "settings",
                title = stringProvider.getString("premium_feature_app_icons_title"),
                description = stringProvider.getString("premium_feature_app_icons_description"),
                color = 0xFF673AB7
            )

            PremiumFeatureType.UNKNOWN -> null
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onSubscribeClicked() {
    }
}