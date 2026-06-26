package org.monogram.domain.models

data class AdvertisementSponsorModel(
    val url: String,
    val photoPath: String? = null,
    val info: String? = null
)

data class SponsoredMessageModel(
    val messageId: Long,
    val isRecommended: Boolean,
    val canBeReported: Boolean,
    val title: String? = null,
    val buttonText: String? = null,
    val additionalInfo: String? = null,
    val accentColorId: Int = 0,
    val backgroundCustomEmojiId: Long = 0L,
    val content: MessageContent,
    val sponsor: AdvertisementSponsorModel
)

data class SponsoredMessagesFeedModel(
    val messages: List<SponsoredMessageModel>,
    val messagesBetween: Int
)
