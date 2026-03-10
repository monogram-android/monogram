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
                    title = "Doubled Limits",
                    description = "Up to $channels channels, $folders chat folders, $pins pins, $publicLinks public links and more.",
                    color = 0xFFAF52DE
                )
            }

            PremiumFeatureType.VOICE_TO_TEXT -> PremiumComponent.PremiumFeature(
                icon = "mic",
                title = "Voice-to-Text Conversion",
                description = "Read the transcript of any voice message by tapping the button next to it.",
                color = 0xFF4285F4
            )

            PremiumFeatureType.FASTER_DOWNLOAD -> PremiumComponent.PremiumFeature(
                icon = "download",
                title = "Faster Download Speed",
                description = "No more limits on the speed with which media and documents are downloaded.",
                color = 0xFF34A853
            )

            PremiumFeatureType.TRANSLATION -> PremiumComponent.PremiumFeature(
                icon = "translate",
                title = "Real-Time Translation",
                description = "Translate entire chats in real time with a single tap.",
                color = 0xFFF9AB00
            )

            PremiumFeatureType.ANIMATED_EMOJI -> PremiumComponent.PremiumFeature(
                icon = "face",
                title = "Animated Emojis",
                description = "Include animated emojis from hundreds of packs in your messages.",
                color = 0xFFFF6D66
            )

            PremiumFeatureType.ADVANCED_CHAT_MANAGEMENT -> PremiumComponent.PremiumFeature(
                icon = "folder",
                title = "Advanced Chat Management",
                description = "Tools to set default folder, auto-archive and hide new chats from non-contacts.",
                color = 0xFF536DFE
            )

            PremiumFeatureType.NO_ADS -> PremiumComponent.PremiumFeature(
                icon = "block",
                title = "No Ads",
                description = "Public channels sometimes show ads, but they will no longer appear for you.",
                color = 0xFF00BFA5
            )

            PremiumFeatureType.INFINITE_REACTIONS -> PremiumComponent.PremiumFeature(
                icon = "heart",
                title = "Infinite Reactions",
                description = "React with thousands of emojis — using up to 3 per message.",
                color = 0xFFFF6D66
            )

            PremiumFeatureType.BADGE -> PremiumComponent.PremiumFeature(
                icon = "verified",
                title = "Premium Badge",
                description = "A special badge next to your name showing that you subscribe to Telegram Premium.",
                color = 0xFF24A1DE
            )

            PremiumFeatureType.PROFILE_BADGE -> PremiumComponent.PremiumFeature(
                icon = "face",
                title = "Emoji Statuses",
                description = "Choose from thousands of emojis to show next to your name.",
                color = 0xFFF9AB00
            )

            PremiumFeatureType.APP_ICONS -> PremiumComponent.PremiumFeature(
                icon = "settings",
                title = "Premium App Icons",
                description = "Choose from a selection of Telegram app icons for your home screen.",
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