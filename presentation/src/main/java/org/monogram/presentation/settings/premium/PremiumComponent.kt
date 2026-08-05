package org.monogram.presentation.settings.premium

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.models.PremiumFeatureModel
import org.monogram.domain.models.PremiumFeatureType
import org.monogram.domain.models.PremiumFeaturesModel
import org.monogram.domain.models.PremiumLimitModel
import org.monogram.domain.models.PremiumLimitType
import org.monogram.domain.models.PremiumPaymentOptionModel
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.repository.PremiumRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface PremiumComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onSubscribeClicked()
    fun onShowSponsoredMessagesChanged(enabled: Boolean)

    data class State(
        val features: List<PremiumFeature> = emptyList(),
        val limits: List<PremiumLimit> = emptyList(),
        val paymentOptions: List<PremiumPaymentOptionModel> = emptyList(),
        val paymentLink: String? = null,
        val isLoading: Boolean = false,
        val isPremium: Boolean = false,
        val statusText: String? = null,
        val showSponsoredMessagesForPremium: Boolean = false
    )

    data class PremiumFeature(
        val icon: String,
        val title: String,
        val description: String,
        val color: Long
    )

    data class PremiumLimit(
        val title: String,
        val subtitle: String? = null,
        val defaultValue: Int,
        val premiumValue: Int
    )
}

class DefaultPremiumComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : PremiumComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    private val premiumRepository: PremiumRepository = container.repositories.premiumRepository
    private val appPreferences: AppPreferences = container.preferences.appPreferences
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

        appPreferences.showSponsoredMessagesForPremium
            .onEach { enabled ->
                _state.update { it.copy(showSponsoredMessagesForPremium = enabled) }
            }
            .launchIn(scope)

        loadPremiumState()
    }

    private fun loadPremiumState() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }

            val premiumState = runCatching { premiumRepository.getPremiumState() }.getOrNull()
            val premiumFeatures = runCatching {
                premiumRepository.getPremiumFeatures(PremiumSource.SETTINGS)
            }.getOrNull() ?: PremiumFeaturesModel()

            val mappedFeatures = premiumFeatures.features.map { feature ->
                mapToPremiumFeature(feature, premiumFeatures.limits)
            }
            val mappedLimits = premiumFeatures.limits.map(::mapToPremiumLimit)

            _state.update {
                it.copy(
                    features = mappedFeatures,
                    limits = mappedLimits,
                    paymentOptions = premiumState?.paymentOptions.orEmpty(),
                    paymentLink = premiumFeatures.paymentLink,
                    statusText = premiumState?.state?.takeIf(String::isNotBlank),
                    isLoading = false
                )
            }
        }
    }

    private fun mapToPremiumFeature(
        feature: PremiumFeatureModel,
        limits: List<PremiumLimitModel>
    ): PremiumComponent.PremiumFeature {
        if (feature.type == PremiumFeatureType.DOUBLE_LIMITS) {
            val channels = limits.valueFor(PremiumLimitType.SUPERGROUP_COUNT)
            val folders = limits.valueFor(PremiumLimitType.CHAT_FOLDER_COUNT)
            val pins = limits.valueFor(PremiumLimitType.PINNED_CHAT_COUNT)
            val publicLinks = limits.valueFor(PremiumLimitType.CREATED_PUBLIC_CHAT_COUNT)
            val descriptionKey = if (channels > 0 || folders > 0 || pins > 0 || publicLinks > 0) {
                "premium_feature_doubled_limits_description"
            } else {
                "premium_feature_doubled_limits_description_generic"
            }
            return PremiumComponent.PremiumFeature(
                icon = "star",
                title = stringProvider.getString("premium_feature_doubled_limits_title"),
                description = stringProvider.getString(
                    descriptionKey,
                    channels,
                    folders,
                    pins,
                    publicLinks
                ),
                color = 0xFFAF52DE
            )
        }

        val presentation = when (feature.type) {
            PremiumFeatureType.INCREASED_UPLOAD_FILE_SIZE -> FeaturePresentation(
                "premium_feature_increased_upload_file_size_title",
                "premium_feature_increased_upload_file_size_description",
                "download",
                0xFF34A853
            )

            PremiumFeatureType.VOICE_TO_TEXT -> FeaturePresentation(
                "premium_feature_voice_to_text_title",
                "premium_feature_voice_to_text_description",
                "mic",
                0xFF4285F4
            )

            PremiumFeatureType.FASTER_DOWNLOAD -> FeaturePresentation(
                "premium_feature_faster_download_title",
                "premium_feature_faster_download_description",
                "download",
                0xFF34A853
            )

            PremiumFeatureType.TRANSLATION -> FeaturePresentation(
                "premium_feature_translation_title",
                "premium_feature_translation_description",
                "translate",
                0xFFF9AB00
            )

            PremiumFeatureType.ANIMATED_EMOJI -> FeaturePresentation(
                "premium_feature_animated_emoji_title",
                "premium_feature_animated_emoji_description",
                "face",
                0xFFFF6D66
            )

            PremiumFeatureType.ADVANCED_CHAT_MANAGEMENT -> FeaturePresentation(
                "premium_feature_chat_management_title",
                "premium_feature_chat_management_description",
                "folder",
                0xFF536DFE
            )

            PremiumFeatureType.NO_ADS -> FeaturePresentation(
                "premium_feature_no_ads_title",
                "premium_feature_no_ads_description",
                "block",
                0xFF00BFA5
            )

            PremiumFeatureType.INFINITE_REACTIONS -> FeaturePresentation(
                "premium_feature_infinite_reactions_title",
                "premium_feature_infinite_reactions_description",
                "heart",
                0xFFFF6D66
            )

            PremiumFeatureType.UNIQUE_STICKERS -> FeaturePresentation(
                "premium_feature_unique_stickers_title",
                "premium_feature_unique_stickers_description",
                "favorite",
                0xFFFF8A65
            )

            PremiumFeatureType.BADGE -> FeaturePresentation(
                "premium_feature_badge_title",
                "premium_feature_badge_description",
                "verified",
                0xFF24A1DE
            )

            PremiumFeatureType.PROFILE_BADGE -> FeaturePresentation(
                "premium_feature_emoji_status_title",
                "premium_feature_emoji_status_description",
                "face",
                0xFFF9AB00
            )

            PremiumFeatureType.ANIMATED_PROFILE_PHOTO -> FeaturePresentation(
                "premium_feature_animated_profile_photo_title",
                "premium_feature_animated_profile_photo_description",
                "face",
                0xFFAB47BC
            )

            PremiumFeatureType.FORUM_TOPIC_ICON -> FeaturePresentation(
                "premium_feature_forum_topic_icon_title",
                "premium_feature_forum_topic_icon_description",
                "folder",
                0xFF5C6BC0
            )

            PremiumFeatureType.APP_ICONS -> FeaturePresentation(
                "premium_feature_app_icons_title",
                "premium_feature_app_icons_description",
                "settings",
                0xFF673AB7
            )

            PremiumFeatureType.UPGRADED_STORIES -> FeaturePresentation(
                "premium_feature_upgraded_stories_title",
                "premium_feature_upgraded_stories_description",
                "star",
                0xFFFFB300
            )

            PremiumFeatureType.CHAT_BOOST -> FeaturePresentation(
                "premium_feature_chat_boost_title",
                "premium_feature_chat_boost_description",
                "heart",
                0xFFEC407A
            )

            PremiumFeatureType.ACCENT_COLOR -> FeaturePresentation(
                "premium_feature_accent_color_title",
                "premium_feature_accent_color_description",
                "settings",
                0xFF26A69A
            )

            PremiumFeatureType.BACKGROUND_FOR_BOTH -> FeaturePresentation(
                "premium_feature_background_for_both_title",
                "premium_feature_background_for_both_description",
                "settings",
                0xFF42A5F5
            )

            PremiumFeatureType.SAVED_MESSAGES_TAGS -> FeaturePresentation(
                "premium_feature_saved_messages_tags_title",
                "premium_feature_saved_messages_tags_description",
                "folder",
                0xFF7E57C2
            )

            PremiumFeatureType.MESSAGE_PRIVACY -> FeaturePresentation(
                "premium_feature_message_privacy_title",
                "premium_feature_message_privacy_description",
                "verified",
                0xFF26A69A
            )

            PremiumFeatureType.LAST_SEEN_TIMES -> FeaturePresentation(
                "premium_feature_last_seen_times_title",
                "premium_feature_last_seen_times_description",
                "verified",
                0xFF42A5F5
            )

            PremiumFeatureType.BUSINESS -> FeaturePresentation(
                "premium_feature_business_title",
                "premium_feature_business_description",
                "settings",
                0xFF5C6BC0
            )

            PremiumFeatureType.MESSAGE_EFFECTS -> FeaturePresentation(
                "premium_feature_message_effects_title",
                "premium_feature_message_effects_description",
                "favorite",
                0xFFFF7043
            )

            PremiumFeatureType.CHECKLISTS -> FeaturePresentation(
                "premium_feature_checklists_title",
                "premium_feature_checklists_description",
                "settings",
                0xFF26A69A
            )

            PremiumFeatureType.PAID_MESSAGES -> FeaturePresentation(
                "premium_feature_paid_messages_title",
                "premium_feature_paid_messages_description",
                "star",
                0xFFFFB300
            )

            PremiumFeatureType.PROTECT_PRIVATE_CHAT_CONTENT -> FeaturePresentation(
                "premium_feature_protect_private_chat_content_title",
                "premium_feature_protect_private_chat_content_description",
                "block",
                0xFFEF5350
            )

            PremiumFeatureType.TEXT_COMPOSITION -> FeaturePresentation(
                "premium_feature_text_composition_title",
                "premium_feature_text_composition_description",
                "translate",
                0xFF7E57C2
            )

            PremiumFeatureType.RICH_MESSAGES -> FeaturePresentation(
                "premium_feature_rich_messages_title",
                "premium_feature_rich_messages_description",
                "settings",
                0xFF42A5F5
            )
            PremiumFeatureType.UNKNOWN -> null
            PremiumFeatureType.DOUBLE_LIMITS -> null
        }

        return presentation?.let {
            PremiumComponent.PremiumFeature(
                icon = it.icon,
                title = stringProvider.getString(it.titleKey),
                description = stringProvider.getString(it.descriptionKey),
                color = it.color
            )
        } ?: PremiumComponent.PremiumFeature(
            icon = "star",
            title = stringProvider.getString("premium_feature_unknown_title"),
            description = stringProvider.getString(
                "premium_feature_unknown_description",
                feature.apiName
            ),
            color = 0xFFAF52DE
        )
    }

    private fun mapToPremiumLimit(limit: PremiumLimitModel): PremiumComponent.PremiumLimit {
        val titleKey = when (limit.type) {
            PremiumLimitType.SUPERGROUP_COUNT -> "premium_limit_supergroup_count"
            PremiumLimitType.PINNED_CHAT_COUNT -> "premium_limit_pinned_chat_count"
            PremiumLimitType.CREATED_PUBLIC_CHAT_COUNT -> "premium_limit_created_public_chat_count"
            PremiumLimitType.SAVED_ANIMATION_COUNT -> "premium_limit_saved_animation_count"
            PremiumLimitType.FAVORITE_STICKER_COUNT -> "premium_limit_favorite_sticker_count"
            PremiumLimitType.CHAT_FOLDER_COUNT -> "premium_limit_chat_folder_count"
            PremiumLimitType.CHAT_FOLDER_CHOSEN_CHAT_COUNT -> "premium_limit_chat_folder_chosen_chat_count"
            PremiumLimitType.PINNED_ARCHIVED_CHAT_COUNT -> "premium_limit_pinned_archived_chat_count"
            PremiumLimitType.PINNED_SAVED_MESSAGES_TOPIC_COUNT -> "premium_limit_pinned_saved_messages_topic_count"
            PremiumLimitType.MESSAGE_TEXT_LENGTH -> "premium_limit_message_text_length"
            PremiumLimitType.CAPTION_LENGTH -> "premium_limit_caption_length"
            PremiumLimitType.BIO_LENGTH -> "premium_limit_bio_length"
            PremiumLimitType.CHAT_FOLDER_INVITE_LINK_COUNT -> "premium_limit_chat_folder_invite_link_count"
            PremiumLimitType.SHAREABLE_CHAT_FOLDER_COUNT -> "premium_limit_shareable_chat_folder_count"
            PremiumLimitType.ACTIVE_STORY_COUNT -> "premium_limit_active_story_count"
            PremiumLimitType.WEEKLY_POSTED_STORY_COUNT -> "premium_limit_weekly_posted_story_count"
            PremiumLimitType.MONTHLY_POSTED_STORY_COUNT -> "premium_limit_monthly_posted_story_count"
            PremiumLimitType.STORY_CAPTION_LENGTH -> "premium_limit_story_caption_length"
            PremiumLimitType.STORY_SUGGESTED_REACTION_AREA_COUNT -> "premium_limit_story_suggested_reaction_area_count"
            PremiumLimitType.SIMILAR_CHAT_COUNT -> "premium_limit_similar_chat_count"
            PremiumLimitType.OWNED_BOT_COUNT -> "premium_limit_owned_bot_count"
            PremiumLimitType.CUSTOM_TEXT_COMPOSITION_STYLE_COUNT -> "premium_limit_custom_text_composition_style_count"
            PremiumLimitType.UNKNOWN -> "premium_limit_unknown_title"
        }
        return PremiumComponent.PremiumLimit(
            title = stringProvider.getString(titleKey),
            subtitle = limit.apiName.takeIf { limit.type == PremiumLimitType.UNKNOWN },
            defaultValue = limit.defaultValue,
            premiumValue = limit.premiumValue
        )
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onSubscribeClicked() {
    }

    override fun onShowSponsoredMessagesChanged(enabled: Boolean) {
        appPreferences.setShowSponsoredMessagesForPremium(enabled)
        scope.launch {
            runCatching {
                premiumRepository.setSponsoredMessagesEnabled(enabled)
            }
        }
    }

    private data class FeaturePresentation(
        val titleKey: String,
        val descriptionKey: String,
        val icon: String,
        val color: Long
    )
}

private fun List<PremiumLimitModel>.valueFor(type: PremiumLimitType): Int =
    firstOrNull { it.type == type }?.premiumValue ?: 0
